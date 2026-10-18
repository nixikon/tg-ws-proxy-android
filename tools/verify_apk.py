"""Verify that a built APK carries the current Python sources and runtime.

Checks performed:
  * every .py under the Chaquopy source dir is present in assets/chaquopy/app.imy
    and byte-identical to the file on disk;
  * the bundled CA store inside the archive matches certs/cacert.pem;
  * the native runtime for each expected ABI is present;
  * both en and ru string resources are compiled into resources.arsc;
  * the APK is signed with the fork's release key (see EXPECTED_SIGNER below).

The signer check is not cosmetic: Android refuses to install an update whose
signing certificate differs from the installed app's
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and a release build silently signs with
whatever debug keystore the Android user home happens to hold. Releases up to
1.11.1-a1 were signed with `ab887caa94e0...`; an APK showing any other digest
cannot be installed over them.

Usage:  python tools/verify_apk.py <apk> <python-source-dir>
            [--signer <sha256> | --any-signer]
"""
from __future__ import annotations

import argparse
import hashlib
import io
import os
import struct
import zipfile

# The certificate every release has been signed with since the fork started
# shipping APKs (the debug key in the build workspace's `.android-home`).
EXPECTED_SIGNER = "ab887caa94e0b74dc801d99452fc27973e1234a09668e8dd69c83b94b1c2114a"

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _signer_certificate(apk_path: str) -> bytes | None:
    """First signing certificate of the APK Signature Scheme v2 block.

    The release APKs carry no v1 (JAR) signature, so the certificate has to come
    from the signing block that sits between the file entries and the central
    directory.
    """
    with open(apk_path, "rb") as apk:
        apk.seek(0, os.SEEK_END)
        size = apk.tell()
        tail_len = min(size, 65536 + 22)
        apk.seek(size - tail_len)
        tail = apk.read()
        eocd = tail.rfind(b"PK\x05\x06")
        if eocd < 0:
            return None
        cd_offset = int.from_bytes(tail[eocd + 16:eocd + 20], "little")

        apk.seek(cd_offset - 24)
        trailer = apk.read(24)
        if trailer[8:] != SIG_BLOCK_MAGIC:
            return None
        block_size = int.from_bytes(trailer[:8], "little")
        start = cd_offset - block_size - 8
        if start < 0:
            return None
        apk.seek(start)
        block = apk.read(block_size + 8)

    pos, end = 8, len(block) - 24
    value = None
    while pos + 12 <= end:
        pair_len = int.from_bytes(block[pos:pos + 8], "little")
        pair_id = _u32(block, pos + 8)
        if pair_id == V2_BLOCK_ID:
            value = block[pos + 12:pos + 8 + pair_len]
            break
        pos += 8 + pair_len
    if value is None:
        return None

    # value := length-prefixed sequence of signers; every signer is itself
    # length-prefixed and holds the signed data, the signatures and the public
    # key. Inside the signed data come the digests, the certificates and the
    # additional attributes — each again length-prefixed.
    signers_len = _u32(value, 0)
    signer = value[4:4 + signers_len]
    signed_len = _u32(signer, 4)
    signed = signer[8:8 + signed_len]
    digests_len = _u32(signed, 0)
    certs_len = _u32(signed, 4 + digests_len)
    certs = signed[8 + digests_len:8 + digests_len + certs_len]
    first_len = _u32(certs, 0)
    return certs[4:4 + first_len]


def signer_sha256(apk_path: str) -> str | None:
    """SHA-256 of the signing certificate, or None when the APK is unsigned."""
    cert = _signer_certificate(apk_path)
    return _sha(cert) if cert else None


def main() -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("apk", nargs="?")
    parser.add_argument("source_dir", nargs="?")
    parser.add_argument("--signer", default=EXPECTED_SIGNER)
    parser.add_argument("--any-signer", action="store_true")
    parser.add_argument("-h", "--help", action="store_true")
    args = parser.parse_args()
    if args.help or not args.apk or not args.source_dir:
        print(__doc__)
        return 2

    apk_path, src_dir = args.apk, args.source_dir
    failures: list[str] = []

    with zipfile.ZipFile(apk_path) as apk:
        names = apk.namelist()

        imy_name = "assets/chaquopy/app.imy"
        if imy_name not in names:
            print("FAIL: %s missing from APK" % imy_name)
            return 1
        imy_bytes = apk.read(imy_name)

        # The .imy is itself a zip archive.
        with zipfile.ZipFile(io.BytesIO(imy_bytes)) as imy:
            imy_names = imy.namelist()
            packed = {n: imy.read(n) for n in imy_names if not n.endswith("/")}

        checked = 0
        for root, dirs, files in os.walk(src_dir):
            # Bytecode caches are never sources: `pyc { src = false }` keeps
            # them out of the APK, and the walk must not look for them.
            dirs[:] = [d for d in dirs if d != "__pycache__"]
            for fname in files:
                path = os.path.join(root, fname)
                rel = os.path.relpath(path, src_dir).replace(os.sep, "/")
                with open(path, "rb") as fh:
                    disk = fh.read()
                if rel not in packed:
                    failures.append("missing from app.imy: %s" % rel)
                    continue
                if _sha(disk) != _sha(packed[rel]):
                    failures.append("content differs: %s" % rel)
                else:
                    checked += 1

        print("Python files verified byte-identical: %d" % checked)

        # `pyc { src = false }` means no bytecode may travel in the APK — not
        # even the empty directory a host-side import leaves behind.
        stale = [n for n in imy_names if "pycache" in n or n.endswith(".pyc")]
        if stale:
            failures.append(
                "bytecode cached in app.imy (build from a clean source tree): %s"
                % ", ".join(sorted(stale)[:3])
            )
        else:
            print("No bytecode caches inside app.imy")

        ca = packed.get("certs/cacert.pem")
        if ca is None:
            failures.append("certs/cacert.pem missing from app.imy")
        else:
            with open(os.path.join(src_dir, "certs", "cacert.pem"), "rb") as fh:
                if _sha(fh.read()) != _sha(ca):
                    failures.append("bundled CA store differs from source")
                else:
                    print("CA store verified (%d bytes)" % len(ca))

        libs = [n for n in names if n.startswith("lib/")]
        for abi in ("arm64-v8a", "x86_64"):
            count = sum(1 for n in libs if n.startswith("lib/%s/" % abi))
            if count == 0:
                failures.append("no native libraries for ABI %s" % abi)
            else:
                print("ABI %-11s native libraries: %d" % (abi, count))

        arsc = apk.read("resources.arsc") if "resources.arsc" in names else b""
        # Locale codes are stored packed in the resource table, so instead look
        # for distinctive string content from each catalog. Android's string
        # pool is UTF-8, but check UTF-16 too in case that changes.
        probes = {
            "ru": "Автозапуск",
            "en": "Verbose logging",
        }
        for locale, probe in probes.items():
            found = False
            for encoding in ("utf-8", "utf-16-le"):
                if probe.encode(encoding) in arsc:
                    found = True
                    break
            if not found:
                failures.append("locale %s strings not present in resources.arsc" % locale)
        if not any("locale" in f for f in failures):
            print("Locales ru/en strings present in resources.arsc")

    digest = signer_sha256(apk_path)
    if digest is None:
        failures.append("no APK Signature Scheme v2 signing block found")
    else:
        if args.any_signer:
            print("Signer certificate SHA-256: %s" % digest)
        elif digest.lower() == args.signer.lower().replace(":", ""):
            print("Signer certificate matches the fork's release key")
        else:
            failures.append(
                "signer certificate is %s, expected %s — an APK signed with "
                "another key cannot be installed over an existing copy" % (
                    digest, args.signer.lower().replace(":", ""))
            )

    if failures:
        print("\nFAILURES:")
        for item in failures:
            print("  - " + item)
        return 1
    print("\nAPK verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
