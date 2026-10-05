"""Fetch a standalone CPython 3.13 for Windows.

The Android build embeds CPython 3.13.9; the machine's own interpreter is 3.12,
so the ported code is additionally exercised on 3.13 before shipping. Uses the
python-build-standalone `install_only` archives.

Usage:  python tools/fetch_python313.py <target-dir>
"""
from __future__ import annotations

import io
import json
import os
import sys
import tarfile
import urllib.request

API = "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest"
_UA = {"User-Agent": "tg-ws-proxy-android-build/1.0"}


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    target = sys.argv[1]
    os.makedirs(target, exist_ok=True)

    req = urllib.request.Request(API, headers=_UA)
    with urllib.request.urlopen(req, timeout=60) as resp:
        release = json.loads(resp.read().decode("utf-8"))

    candidates = [
        a for a in release.get("assets", [])
        if a["name"].startswith("cpython-3.13.")
        and a["name"].endswith("-x86_64-pc-windows-msvc-install_only.tar.gz")
    ]
    if not candidates:
        names = [a["name"] for a in release.get("assets", [])]
        print("No 3.13 Windows asset in release", release.get("tag_name"))
        print("Sample assets:", names[:10])
        return 1

    asset = sorted(candidates, key=lambda a: a["name"])[-1]
    print("Downloading", asset["name"], f"({asset['size'] // 1024 // 1024} MiB)")

    req = urllib.request.Request(asset["browser_download_url"], headers=_UA)
    with urllib.request.urlopen(req, timeout=600) as resp:
        blob = resp.read()

    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tf:
        tf.extractall(target)

    for root, dirs, files in os.walk(target):
        if "python.exe" in files:
            print("Interpreter:", os.path.join(root, "python.exe"))
            return 0
    print("python.exe not found after extraction")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
