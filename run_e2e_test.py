"""Run `tools/e2e_proxy_test.py` with the port's core and the APK libraries.

The embedded CPython in `.py313` ignores `PYTHONPATH`, so `httpx`/`h2` (taken
from the built APK) are put on `sys.path` here. Any extra arguments are passed
through to the end-to-end test.

Usage:  .py313\\python.exe run_e2e_test.py <data-dir> [--dc N] [--timeout SEC]
"""
import os
import runpy
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
for path in (os.path.join(ROOT, "dsh-pylibs"),
             os.path.join(ROOT, "android", "app", "src", "main", "python"),
             os.path.join(ROOT, "tools")):
    if path not in sys.path:
        sys.path.insert(0, path)

sys.argv = [os.path.join(ROOT, "tools", "e2e_proxy_test.py")] + sys.argv[1:]
runpy.run_path(sys.argv[0], run_name="__main__")
