"""Verify that a built APK carries the current Python sources and runtime.

Checks performed:
  * every .py under the Chaquopy source dir is present in assets/chaquopy/app.imy
    and byte-identical to the file on disk;
  * the bundled CA store inside the archive matches certs/cacert.pem;
  * the native runtime for each expected ABI is present;
  * both en and ru string resources are compiled into resources.arsc.

Usage:  python tools/verify_apk.py <apk> <python-source-dir>
"""
from __future__ import annotations

import hashlib
import os
import sys
import zipfile


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    apk_path, src_dir = sys.argv[1], sys.argv[2]
    failures: list[str] = []

    with zipfile.ZipFile(apk_path) as apk:
        names = apk.namelist()

        imy_name = "assets/chaquopy/app.imy"
        if imy_name not in names:
            print("FAIL: %s missing from APK" % imy_name)
            return 1
        imy_bytes = apk.read(imy_name)

        # The .imy is itself a zip archive.
        import io
        with zipfile.ZipFile(io.BytesIO(imy_bytes)) as imy:
            packed = {
                n: imy.read(n) for n in imy.namelist()
                if not n.endswith("/")
            }

        checked = 0
        for root, _dirs, files in os.walk(src_dir):
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

    if failures:
        print("\nFAILURES:")
        for item in failures:
            print("  - " + item)
        return 1
    print("\nAPK verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
