"""Map a listen-socket bind failure to a stable, localisable error code.

The desktop build returns ready-made translated strings here; Android returns
codes so the Kotlin UI can look them up in its own string resources.
"""
from __future__ import annotations

import errno
from typing import Optional

# Windows WinSock error codes (exc.winerror); errno may differ from POSIX.
_WSA_EACCES = 10013
_WSA_EFAULT = 10014
_WSA_EADDRINUSE = 10048
_WSA_EADDRNOTAVAIL = 10049


def classify_listen_error(exc: BaseException) -> Optional[str]:
    """Return one of ``port_busy`` / ``permission`` / ``bad_address`` or None."""
    if not isinstance(exc, OSError):
        return None

    err = exc.errno
    winerror = getattr(exc, "winerror", None)

    if err == errno.EADDRINUSE or winerror == _WSA_EADDRINUSE:
        return "port_busy"
    if err == errno.EACCES or winerror == _WSA_EACCES:
        return "permission"
    if (winerror in (_WSA_EFAULT, _WSA_EADDRNOTAVAIL)
            or err in (errno.EADDRNOTAVAIL, errno.EFAULT)):
        return "bad_address"
    return None
