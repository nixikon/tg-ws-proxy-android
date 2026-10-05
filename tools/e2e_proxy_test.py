"""End-to-end smoke test for the ported proxy core (runs on the host).

Drives the same entry point the Android UI uses (`android_entry`), then acts as
a Telegram client: builds a real obfuscated2 handshake, sends a genuine
`req_pq_multi` and checks that a decrypted MTProto reply comes back. That
exercises the handshake crypto, the relay re-encryption and the WebSocket
bridge in one go.

Usage:  python tools/e2e_proxy_test.py <data-dir> [--dc N] [--timeout SEC]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import struct
import sys
import time

HANDSHAKE_LEN = 64
SKIP_LEN = 8
PREKEY_LEN = 32
IV_LEN = 16
PROTO_TAG_POS = 56
DC_IDX_POS = 60

PROTO_TAG_INTERMEDIATE = b'\xee\xee\xee\xee'
RESERVED_FIRST_BYTES = {0xEF}
RESERVED_STARTS = {
    b'\x48\x45\x41\x44', b'\x50\x4F\x53\x54', b'\x47\x45\x54\x20',
    b'\xee\xee\xee\xee', b'\xdd\xdd\xdd\xdd', b'\x16\x03\x01\x02',
}
RESERVED_CONTINUE = b'\x00\x00\x00\x00'

REQ_PQ_MULTI = 0xbe7e8ef1
RES_PQ = 0x05162463


def _read_exact(sock: socket.socket, n: int) -> bytes:
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("connection closed")
        buf += chunk
    return buf


def build_handshake(secret: bytes, dc: int):
    """Return (init_bytes, encryptor, decryptor) for the obfuscated2 handshake."""
    from proxy._aes import Cipher, algorithms, modes

    while True:
        rnd = bytearray(os.urandom(HANDSHAKE_LEN))
        if rnd[0] in RESERVED_FIRST_BYTES:
            continue
        if bytes(rnd[:4]) in RESERVED_STARTS:
            continue
        if bytes(rnd[4:8]) == RESERVED_CONTINUE:
            continue
        break

    rnd[PROTO_TAG_POS:PROTO_TAG_POS + 4] = PROTO_TAG_INTERMEDIATE
    rnd[DC_IDX_POS:DC_IDX_POS + 2] = struct.pack('<h', dc)
    rnd[DC_IDX_POS + 2:HANDSHAKE_LEN] = os.urandom(2)

    prekey = bytes(rnd[SKIP_LEN:SKIP_LEN + PREKEY_LEN])
    iv = bytes(rnd[SKIP_LEN + PREKEY_LEN:SKIP_LEN + PREKEY_LEN + IV_LEN])

    # What the client sends is encrypted with SHA256(prekey + secret).
    enc_key = hashlib.sha256(prekey + secret).digest()
    enc = Cipher(algorithms.AES(enc_key), modes.CTR(iv)).encryptor()

    # obfuscated2 keeps bytes 0..56 in the clear and only encrypts the 8-byte
    # tail; the whole 64-byte block still advances the keystream, which is what
    # the following MTProto payload continues from. This mirrors the proxy's own
    # `_generate_relay_init`.
    tail_plain = bytes(rnd[PROTO_TAG_POS:HANDSHAKE_LEN])
    encrypted_full = enc.update(bytes(rnd))
    keystream_tail = bytes(
        encrypted_full[i] ^ rnd[i] for i in range(PROTO_TAG_POS, HANDSHAKE_LEN))
    encrypted_tail = bytes(
        tail_plain[i] ^ keystream_tail[i] for i in range(len(tail_plain)))
    init = bytes(rnd[:PROTO_TAG_POS]) + encrypted_tail

    # What the client receives is encrypted with the reversed prekey/iv.
    rev = (prekey + iv)[::-1]
    dec_key = hashlib.sha256(rev[:PREKEY_LEN] + secret).digest()
    dec = Cipher(algorithms.AES(dec_key), modes.CTR(rev[PREKEY_LEN:])).encryptor()

    return init, enc, dec


def build_req_pq_multi() -> bytes:
    """A well-formed `req_pq_multi` wrapped in the intermediate transport.

    Unencrypted messages (auth_key_id == 0) carry no salt/session_id/seq_no
    envelope — that wrapper only exists once an auth key is in place — so the
    payload is just the TL-serialised body.
    """
    nonce = os.urandom(16)
    body = struct.pack('<I', REQ_PQ_MULTI) + nonce

    msg_id = (int(time.time()) << 32) & ~3
    payload = (
        b'\x00' * 8                            # auth_key_id
        + struct.pack('<q', msg_id)
        + struct.pack('<I', len(body))
        + body
    )
    # Intermediate transport: 4-byte little-endian length prefix.
    return struct.pack('<I', len(payload)) + payload


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("data_dir")
    ap.add_argument("--dc", type=int, default=2)
    ap.add_argument("--timeout", type=float, default=30.0)
    args = ap.parse_args()

    import android_entry
    from proxy._aes import _select_backend  # noqa: F401  (ensures AES is up)

    print("init:", android_entry.init(args.data_dir))

    cfg = {
        "host": "127.0.0.1",
        "port": 14431,
        "secret": os.urandom(16).hex(),
        "dc_ip": ["2:149.154.167.220", "4:149.154.167.220"],
        "verbose": True,
        "buf_kb": 256,
        "pool_size": 2,
        "cfproxy": True,
        "cfproxy_user_domain_enabled": False,
        "cfproxy_user_domain": [],
        "cfproxy_worker_enabled": False,
        "cfproxy_worker_domain": [],
        "no_secure": False,
        "fake_tls_domain": "",
        "force_test_dc": False,
    }
    print("configure:", android_entry.configure(json.dumps(cfg)))
    print("start:", android_entry.start())
    print("listening:", android_entry.is_listening())
    print("link:", android_entry.proxy_link())

    if not android_entry.is_listening():
        print("FAIL: proxy never started listening")
        return 1

    secret = bytes.fromhex(cfg["secret"])
    sock = socket.create_connection(("127.0.0.1", cfg["port"]), timeout=10)
    sock.settimeout(args.timeout)

    try:
        init, enc, dec = build_handshake(secret, args.dc)
        sock.sendall(init)
        print("sent obfuscated2 handshake (%d bytes)" % len(init))

        sock.sendall(enc.update(build_req_pq_multi()))
        print("sent req_pq_multi")

        # Read framed replies until something MTProto-shaped arrives.
        plain = b''
        deadline = time.monotonic() + args.timeout
        result = None
        while time.monotonic() < deadline:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                print("upstream closed the connection")
                break
            plain += dec.update(chunk)

            while len(plain) >= 4:
                length = struct.unpack('<I', plain[:4])[0] & 0x7FFFFFFF
                if length <= 0 or length > 1 << 20:
                    print("unexpected frame length %d (stream desync)" % length)
                    plain = b''
                    break
                if len(plain) < 4 + length:
                    break
                frame = plain[4:4 + length]
                plain = plain[4 + length:]
                if len(frame) >= 24:
                    inner = frame[20:]
                    if len(inner) >= 4:
                        ctor = struct.unpack('<I', inner[:4])[0]
                        result = (ctor, len(frame))
                        break
            if result:
                break

        if result is None:
            print("FAIL: no MTProto reply received")
            print("stats:", android_entry.stats_summary())
            return 1

        ctor, size = result
        name = "resPQ" if ctor == RES_PQ else hex(ctor)
        print("GOT MTProto reply: constructor=%s frame=%d bytes" % (name, size))
        print("stats:", android_entry.stats_summary())
        return 0 if ctor == RES_PQ else 1
    finally:
        try:
            sock.close()
        except Exception:
            pass
        print("stop:", android_entry.stop())


if __name__ == "__main__":
    raise SystemExit(main())
