"""
Default values shared by the tray applications.

The language is not auto-detected here as it is on the desktop: the Android UI
resolves it from the system locale and stores it in the config.
"""
from __future__ import annotations

import os
from typing import Any, Dict

_TRAY_DEFAULTS_COMMON: Dict[str, Any] = {
    "port": 1443,
    "host": "127.0.0.1",
    "dc_ip": ["2:149.154.167.220", "4:149.154.167.220"],
    "verbose": False,
    "log_max_mb": 5,
    "buf_kb": 256,
    "pool_size": 4,
    "cfproxy": True,
    "h2": True,
    "cfproxy_user_domain_enabled": False,
    "cfproxy_user_domain": [],
    "cfproxy_worker_enabled": False,
    "cfproxy_worker_domain": [],
    "force_test_dc": False,
    "no_secure": False,
    "fake_tls_domain": "",
    "autostart": False,
    "appearance": "auto",
}


def default_tray_config(language: str = "en") -> Dict[str, Any]:
    cfg = dict(_TRAY_DEFAULTS_COMMON)
    cfg["secret"] = os.urandom(16).hex()
    cfg["language"] = language
    return cfg
