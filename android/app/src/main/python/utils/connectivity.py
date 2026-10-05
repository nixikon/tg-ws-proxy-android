"""Connectivity probes for the Cloudflare proxy and Cloudflare Worker paths.

Ported from the desktop settings dialog (``ui/ctk_tray_ui.py``) so both front
ends test exactly the same endpoints. Unlike the desktop version this module
returns machine-readable values instead of translated strings: a result is
either ``True`` or an error token (``"timeout"``, ``"no_response"``) which the
Kotlin UI maps to its own string resources. Raw socket errors are passed
through verbatim.

Each probe is synchronous and blocks for up to ~5s per data centre; callers run
it off the UI thread.
"""
from __future__ import annotations

import base64
import os
import socket as _socket
from contextlib import nullcontext
from typing import Any, Dict, List, Tuple

from proxy.utils import create_ssl_context

CFPROXY_TEST_DCS: List[int] = [1, 2, 3, 4, 5, 203]

CFWORKER_TEST_DST: Dict[int, str] = {
    1: '149.154.175.50',
    2: '149.154.167.51',
    3: '149.154.175.100',
    4: '149.154.167.91',
    5: '149.154.171.5',
    203: '91.105.192.100',
}

# (dc, connect_host, sni_host, request_host, path)
Case = Tuple[int, str, str, str, str]


def _run_connectivity_test(cases: List[Case], *, secure: bool = True
                           ) -> Dict[int, Any]:
    ctx = create_ssl_context() if secure else None
    port = 443 if secure else 80
    results: Dict[int, Any] = {}
    for dc, connect_host, sni_host, req_host, path in cases:
        try:
            with _socket.create_connection((connect_host, port), timeout=5) as raw:
                connection = (ctx.wrap_socket(raw, server_hostname=sni_host)
                              if secure else nullcontext(raw))
                with connection as ssock:
                    ws_key = base64.b64encode(os.urandom(16)).decode()
                    req = (
                        f"GET {path} HTTP/1.1\r\n"
                        f"Host: {req_host}\r\n"
                        f"Upgrade: websocket\r\n"
                        f"Connection: Upgrade\r\n"
                        f"Sec-WebSocket-Key: {ws_key}\r\n"
                        f"Sec-WebSocket-Version: 13\r\n"
                        f"Sec-WebSocket-Protocol: binary\r\n"
                        f"\r\n"
                    ).encode()
                    ssock.sendall(req)
                    ssock.settimeout(5)
                    buf = b""
                    while b"\r\n\r\n" not in buf:
                        chunk = ssock.recv(512)
                        if not chunk:
                            break
                        buf += chunk
                    first = buf.decode("utf-8", errors="replace").split("\r\n")[0]
                    if "101" in first:
                        results[dc] = True
                    else:
                        results[dc] = first or "no_response"
                    ssock.close()
                raw.close()
        except _socket.timeout:
            results[dc] = "timeout"
        except OSError as exc:
            msg = str(exc)
            results[dc] = msg[:60] if len(msg) > 60 else msg
    return results


def _run_cfproxy_connectivity_test(domain: str, *, secure: bool = True
                                   ) -> Dict[int, Any]:
    cases: List[Case] = []
    for dc in CFPROXY_TEST_DCS:
        host = f"kws{dc}.{domain}"
        cases.append((dc, host, host, host, "/apiws"))
    return _run_connectivity_test(cases, secure=secure)


def _run_cfworker_connectivity_test(domain: str, *, secure: bool = True
                                    ) -> Dict[int, Any]:
    cases: List[Case] = []
    for dc in CFPROXY_TEST_DCS:
        dst = CFWORKER_TEST_DST[dc]
        path = f"/apiws?dst={dst}&dc={dc}&media=0"
        cases.append((dc, domain, domain, domain, path))
    return _run_connectivity_test(cases, secure=secure)


def run_cfproxy_multi_test(domains: List[str], *, secure: bool = True
                           ) -> Dict[str, Dict[int, Any]]:
    return {domain: _run_cfproxy_connectivity_test(domain, secure=secure)
            for domain in domains}


def run_cfworker_multi_test(domains: List[str], *, secure: bool = True
                            ) -> Dict[str, Dict[int, Any]]:
    return {domain: _run_cfworker_connectivity_test(domain, secure=secure)
            for domain in domains}


def run_cfproxy_auto_test(domains: List[str], *, secure: bool = True
                          ) -> Tuple[str, Dict[int, Any]]:
    """Mirror of the desktop auto mode: prefer a domain that answers for all DCs."""
    merged: Dict[int, Any] = {}
    best_domain = ""
    for domain in reversed(domains):
        res = _run_cfproxy_connectivity_test(domain, secure=secure)
        if all(v is True for v in res.values()):
            return domain, res
        for dc, v in res.items():
            if v is True:
                merged[dc] = True
                best_domain = domain
            elif dc not in merged:
                merged[dc] = v
    return best_domain, merged
