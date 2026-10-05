"""Writable application directories.

The desktop builds derive these from ``APPDATA`` / ``XDG_CONFIG_HOME``. On
Android the Kotlin side calls :func:`configure_app_dir` with
``Context.getFilesDir()`` during startup, which must happen before any log or
update-check cache file is touched.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Optional

APP_NAME = "TgWsProxy"

_app_dir: Optional[Path] = None


def configure_app_dir(path: str) -> Path:
    """Point every data file at ``path`` (called once from Kotlin)."""
    global _app_dir
    resolved = Path(path)
    resolved.mkdir(parents=True, exist_ok=True)
    _app_dir = resolved
    return resolved


def _fallback_app_dir() -> Path:
    if sys.platform == "win32":
        base = os.environ.get("APPDATA") or str(Path.home())
    elif sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / APP_NAME
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / APP_NAME


def app_dir() -> Path:
    global _app_dir
    if _app_dir is None:
        _app_dir = _fallback_app_dir()
        try:
            _app_dir.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass
    return _app_dir


def config_file() -> Path:
    return app_dir() / "config.json"


def log_file() -> Path:
    return app_dir() / "proxy.log"
