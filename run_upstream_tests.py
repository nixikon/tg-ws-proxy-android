"""Run the upstream test suite against the core that ships inside the APK.

The embedded CPython in `.py313` ignores PYTHONPATH (it is a `._pth` build), so
the paths are inserted here explicitly. `--import-mode=importlib` keeps pytest
from putting the upstream checkout on `sys.path`, which is what makes `import
proxy` resolve to the Android port's core instead of upstream's own copy.

Usage:
    .py313\\python.exe run_upstream_tests.py <upstream-dir> [pytest args...]
"""
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = os.path.join(ROOT, "android", "app", "src", "main", "python")
PIP = os.path.join(ROOT, "dsh-pylibs")
LIBS = os.path.join(ROOT, ".pylibs313")

for path in (LIBS, PIP, PORT):
    if path not in sys.path:
        sys.path.insert(0, path)

# The DSH file sandbox denies writes inside directories created with `mkdtemp`'s
# owner-only mode (0o700), which is what several upstream H2 tests need for
# their self-signed certificates. Creating those directories with the default
# mode is enough for the suite to run here; nothing else is patched.
_os_mkdir = os.mkdir


def _mkdir(path, mode=0o777, *args, **kwargs):
    return _os_mkdir(path, 0o777, *args, **kwargs)


os.mkdir = _mkdir

upstream = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "upstream-1111")
extra = sys.argv[2:]

import pytest  # noqa: E402  (paths must be set first)

os.chdir(upstream)
args = ["tests", "-q", "--import-mode=importlib"] + extra
raise SystemExit(pytest.main(args))
