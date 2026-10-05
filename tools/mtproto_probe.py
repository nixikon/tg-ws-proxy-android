"""Client-only MTProto probe: verify that a *running* proxy relays traffic.

Unlike `e2e_proxy_test.py` this does not start a proxy — it connects to one that
is already listening (for example the Android app on an emulator reached through
`adb forward`) and checks that a genuine `resPQ` comes back.

Usage:  python tools/mtproto_probe.py <host> <port> <secret-hex> [--dc N] [--timeout SEC]
"""
from __future__ import annotations

import argparse
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from e2e_proxy_test import (  # noqa: E402
    RES_PQ,
    build_handshake,
    build_req_pq_multi,
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("port", type=int)
    ap.add_argument("secret")
    ap.add_argument("--dc", type=int, default=2)
    ap.add_argument("--timeout", type=float, default=30.0)
    args = ap.parse_args()

    secret = bytes.fromhex(args.secret.strip())
    print("connecting to %s:%d (dc %d)" % (args.host, args.port, args.dc))

    sock = socket.create_connection((args.host, args.port), timeout=10)
    sock.settimeout(args.timeout)
    try:
        init, enc, dec = build_handshake(secret, args.dc)
        sock.sendall(init)
        sock.sendall(enc.update(build_req_pq_multi()))
        print("handshake + req_pq_multi sent")

        plain = b""
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                print("RESULT: upstream closed the connection")
                return 1
            plain += dec.update(chunk)

            while len(plain) >= 4:
                length = struct.unpack("<I", plain[:4])[0] & 0x7FFFFFFF
                if length <= 0 or length > 1 << 20:
                    plain = b""
                    break
                if len(plain) < 4 + length:
                    break
                frame = plain[4:4 + length]
                plain = plain[4 + length:]
                if len(frame) >= 24:
                    ctor = struct.unpack("<I", frame[20:24])[0]
                    if ctor == RES_PQ:
                        print("RESULT: resPQ received (%d byte frame) — proxy relays traffic" % len(frame))
                        return 0
                    print("RESULT: unexpected constructor 0x%08X" % ctor)
                    return 1

        print("RESULT: no MTProto reply within %.0fs" % args.timeout)
        return 1
    finally:
        try:
            sock.close()
        except Exception:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
