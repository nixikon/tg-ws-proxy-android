"""Minimal wheel fetcher used only to provision host-side test tooling.

`pip install` cannot be used in this environment: writing into a directory
created by `tempfile.mkdtemp()` is denied, and pip unpacks every wheel into one.
Wheels are plain zip archives, so fetch the right ones from the PyPI JSON API
and extract them directly into the target directory.

Usage:  python tools/fetch_wheels.py <target-dir> [python-tag]
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request
import zipfile
from io import BytesIO

PACKAGES = [
    "cryptography",
    "cffi",
    "pycparser",
    "pytest",
    "iniconfig",
    "packaging",
    "pluggy",
    "colorama",
    "pygments",
    "certifi",
    # Upstream 1.11.0 HTTP/2 media path.
    "httpx",
    "httpcore",
    "h11",
    "h2",
    "hpack",
    "hyperframe",
    "anyio",
    "sniffio",
    "idna",
    "typing_extensions",
]

PY_TAG = "cp312"
PLATFORM = "win_amd64"

_UA = {"User-Agent": "tg-ws-proxy-android-build/1.0"}


def _get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers=_UA)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _wheel_rank(filename: str) -> int:
    """Lower is better; -1 means unusable on this interpreter."""
    if not filename.endswith(".whl"):
        return -1
    name = filename[:-4]
    parts = name.split("-")
    if len(parts) < 5:
        return -1
    pythons, abis, platform = parts[-3], parts[-2], parts[-1]

    if platform != "any" and platform != PLATFORM:
        return -1

    py_tags = pythons.split(".")
    if PY_TAG in py_tags:
        py_rank = 0
    elif abis == "abi3" and any(t.startswith("cp3") for t in py_tags):
        # e.g. cryptography-*-cp311-abi3-win_amd64.whl: stable ABI, valid for
        # every CPython >= 3.11, so it runs on our 3.12.
        py_rank = 1
    elif "py3" in py_tags:
        py_rank = 2
    elif pythons == "py2.py3":
        py_rank = 3
    else:
        return -1

    # Prefer a platform-specific build over a pure-python fallback.
    plat_rank = 0 if platform == PLATFORM else 1
    return py_rank * 10 + plat_rank


def install(pkg: str, target: str) -> str:
    data = _get_json(f"https://pypi.org/pypi/{pkg}/json")
    version = data["info"]["version"]
    candidates = []
    for f in data.get("urls") or []:
        if f.get("packagetype") != "bdist_wheel":
            continue
        rank = _wheel_rank(f["filename"])
        if rank >= 0:
            candidates.append((rank, f["filename"], f["url"]))
    if not candidates:
        raise RuntimeError(f"no usable wheel for {pkg} {version}")

    candidates.sort(key=lambda c: c[0])
    _, filename, url = candidates[0]

    req = urllib.request.Request(url, headers=_UA)
    with urllib.request.urlopen(req, timeout=300) as resp:
        blob = resp.read()

    with zipfile.ZipFile(BytesIO(blob)) as zf:
        zf.extractall(target)

    return f"{pkg} {version} <- {filename} ({len(blob) // 1024} KiB)"


def main() -> int:
    global PY_TAG
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    target = sys.argv[1]
    if len(sys.argv) > 2:
        PY_TAG = sys.argv[2]
    os.makedirs(target, exist_ok=True)
    failures = []
    for pkg in PACKAGES:
        try:
            print("OK   " + install(pkg, target))
        except Exception as exc:
            failures.append((pkg, repr(exc)))
            print("FAIL " + pkg + ": " + repr(exc))
    if failures:
        return 1
    print("\nAll packages extracted into " + target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
