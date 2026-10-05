"""Kotlin <-> Python entry point for the Android build.

The proxy core itself (the ``proxy`` package) is the upstream code, unmodified
apart from the AES backend and the CA-bundle lookup. This module is the thin
adapter the Android UI talks to: it owns the asyncio loop that the desktop
build runs in a background thread, and exposes JSON-in/JSON-out functions so
nothing but primitive types crosses the Chaquopy boundary.

Threading model: the proxy runs on its own thread with its own event loop, the
same way ``utils/tray_common.py`` does it on the desktop. ``start()`` blocks
briefly so a bind failure (port busy, permission denied) can be reported to the
UI instead of only landing in the log.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import threading
import time
from typing import Any, Dict, Optional

log = logging.getLogger('tg-mtproto-proxy')

# How long start() waits for the listener to come up before giving up. Binding
# a local socket is immediate; the wait only matters for error reporting.
_START_TIMEOUT = 6.0

# How long stop() waits for the proxy thread to unwind.
_STOP_TIMEOUT = 8.0

_thread: Optional[threading.Thread] = None
_loop: Optional[asyncio.AbstractEventLoop] = None
_stop_event: Optional[asyncio.Event] = None
_start_error: Optional[str] = None
_start_error_detail: str = ""
_last_config: Dict[str, Any] = {}
_logging_ready = False


def _ok(**extra: Any) -> str:
    payload: Dict[str, Any] = {"ok": True}
    payload.update(extra)
    return json.dumps(payload)


def _err(code: str, detail: str = "", **extra: Any) -> str:
    payload: Dict[str, Any] = {"ok": False, "error": code, "detail": detail}
    payload.update(extra)
    return json.dumps(payload)


# --------------------------------------------------------------------------
# logging
# --------------------------------------------------------------------------

def _setup_logging(verbose: bool, log_max_mb: float) -> None:
    """(Re)install the rotating file handler used by the in-app log viewer."""
    from proxy.utils import DomainCensorFilter
    from utils.app_paths import log_file
    from utils.logging_setup import build_log_handler

    root = logging.getLogger()
    root.setLevel(logging.DEBUG if verbose else logging.INFO)

    for handler in list(root.handlers):
        root.removeHandler(handler)
        try:
            handler.close()
        except Exception:
            pass

    handler = build_log_handler(str(log_file()), log_max_mb=log_max_mb,
                                backups=1)
    handler.setLevel(logging.DEBUG)
    handler.setFormatter(logging.Formatter(
        "%(asctime)s  %(levelname)-5s  %(name)s  %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"))
    handler.addFilter(DomainCensorFilter())
    root.addHandler(handler)

    logging.getLogger('asyncio').setLevel(logging.WARNING)


def init(app_dir: str, ca_bundle: str = "") -> str:
    """One-shot startup: point data files at the app's private directory.

    ``ca_bundle`` is the absolute path of the trust store the Kotlin side
    extracted from the APK assets; it must be registered before any module that
    builds an SSL context is imported.
    """
    global _logging_ready
    from proxy.utils import set_ca_bundle
    from utils.app_paths import configure_app_dir, log_file

    if ca_bundle:
        set_ca_bundle(ca_bundle)

    configure_app_dir(app_dir)
    # The desktop tray deletes the log on every start (tray_common.bootstrap);
    # keep that behaviour so the in-app viewer always shows the current run.
    try:
        log_file().unlink(missing_ok=True)
    except OSError:
        pass
    _setup_logging(False, 5)
    _logging_ready = True
    from proxy import __version__
    log.info("TG WS Proxy %s (Android) starting", __version__)
    return _ok(version=__version__)


def version() -> str:
    from proxy import __version__
    return __version__


# --------------------------------------------------------------------------
# configuration
# --------------------------------------------------------------------------

def _apply_config(cfg: Dict[str, Any]) -> Dict[str, Any]:
    """Mirror of ``utils/tray_common.apply_proxy_config``."""
    from proxy.config import (coerce_domain_list, parse_dc_ip_list,
                              proxy_config)
    from utils.default_config import default_tray_config

    defaults = default_tray_config()

    dc_ip_list = cfg.get("dc_ip", defaults["dc_ip"])
    try:
        dc_redirects = parse_dc_ip_list(list(dc_ip_list))
    except ValueError as exc:
        log.error("Bad config dc_ip: %s", exc)
        return {"ok": False, "error": "dc_config", "detail": str(exc)}

    proxy_config.port = int(cfg.get("port", defaults["port"]))
    proxy_config.host = str(cfg.get("host", defaults["host"]))
    proxy_config.secret = str(cfg.get("secret", defaults["secret"]))
    proxy_config.dc_redirects = dc_redirects
    proxy_config.buffer_size = max(4, int(cfg.get("buf_kb", defaults["buf_kb"]))) * 1024
    proxy_config.pool_size = max(0, int(cfg.get("pool_size", defaults["pool_size"])))
    proxy_config.fallback_cfproxy = bool(cfg.get("cfproxy", defaults["cfproxy"]))
    proxy_config.cfproxy_h2_media = bool(cfg.get("h2", defaults["h2"]))

    user_domains = coerce_domain_list(
        cfg.get("cfproxy_user_domain", defaults["cfproxy_user_domain"]))
    worker_domains = coerce_domain_list(
        cfg.get("cfproxy_worker_domain", defaults["cfproxy_worker_domain"]))

    proxy_config.cfproxy_user_domains = (
        user_domains
        if cfg.get("cfproxy_user_domain_enabled", bool(user_domains))
        else []
    )
    proxy_config.cfproxy_worker_domains = (
        worker_domains
        if cfg.get("cfproxy_worker_enabled", bool(worker_domains))
        else []
    )
    proxy_config.force_test_dc = bool(cfg.get("force_test_dc", defaults["force_test_dc"]))
    proxy_config.disable_secure = bool(cfg.get("no_secure", defaults["no_secure"]))
    proxy_config.fake_tls_domain = str(
        cfg.get("fake_tls_domain", defaults["fake_tls_domain"])).strip()

    return {"ok": True}


def configure(config_json: str) -> str:
    global _last_config, _logging_ready
    try:
        cfg = json.loads(config_json)
    except (ValueError, TypeError) as exc:
        return _err("bad_config", str(exc))

    if not _logging_ready:
        _setup_logging(bool(cfg.get("verbose", False)),
                       float(cfg.get("log_max_mb", 5)))
        _logging_ready = True
    else:
        _setup_logging(bool(cfg.get("verbose", False)),
                       float(cfg.get("log_max_mb", 5)))

    _last_config = dict(cfg)
    result = _apply_config(cfg)
    return json.dumps(result)


# --------------------------------------------------------------------------
# proxy lifecycle
# --------------------------------------------------------------------------

def _server_instance():
    from proxy import tg_ws_proxy
    return tg_ws_proxy._server_instance


def is_listening() -> bool:
    return _server_instance() is not None


def is_running() -> bool:
    return _thread is not None and _thread.is_alive()


def _run_thread() -> None:
    global _loop, _stop_event, _start_error, _start_error_detail

    from proxy.tg_ws_proxy import _run
    from utils.diagnostics import classify_listen_error

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _loop = loop
    stop_ev = asyncio.Event()
    _stop_event = stop_ev

    try:
        loop.run_until_complete(_run(stop_event=stop_ev))
    except Exception as exc:
        _start_error = classify_listen_error(exc) or "unknown"
        _start_error_detail = repr(exc)
        log.error("Proxy thread crashed: %s", _start_error_detail)
    finally:
        try:
            pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(
                    asyncio.gather(*pending, return_exceptions=True))
            loop.run_until_complete(loop.shutdown_asyncgens())
        except Exception:
            pass
        try:
            loop.close()
        except Exception:
            pass
        _loop = None
        _stop_event = None
        log.info("Proxy stopped")


def start() -> str:
    global _thread, _start_error, _start_error_detail

    if is_running() and is_listening():
        return _ok(already_running=True)

    if is_running():
        # A previous stop is still unwinding.
        stop()
        if is_running():
            return _err("stopping", "previous instance is still shutting down")

    from proxy.config import proxy_config

    _start_error = None
    _start_error_detail = ""

    log.info("Starting proxy on %s:%d ...", proxy_config.host, proxy_config.port)
    _thread = threading.Thread(target=_run_thread, daemon=True, name="proxy")
    _thread.start()

    deadline = time.monotonic() + _START_TIMEOUT
    while time.monotonic() < deadline:
        if _start_error:
            return _err(_start_error, _start_error_detail)
        if is_listening():
            return _ok()
        if not _thread.is_alive():
            # Thread finished without reporting and without listening.
            return _err(_start_error or "unknown", _start_error_detail)
        time.sleep(0.05)

    if is_listening():
        return _ok()
    return _err("timeout", "listener did not come up in time")


def stop() -> str:
    global _thread
    loop = _loop
    stop_ev = _stop_event
    if loop is not None and stop_ev is not None:
        try:
            loop.call_soon_threadsafe(stop_ev.set)
        except RuntimeError:
            pass
    if _thread is not None:
        _thread.join(timeout=_STOP_TIMEOUT)
        if _thread.is_alive():
            log.warning("Proxy thread did not stop within timeout")
            return _err("stop_timeout", "proxy thread still alive")
    _thread = None
    return _ok()


def restart() -> str:
    stop()
    time.sleep(0.6)
    return start()


# --------------------------------------------------------------------------
# status / link
# --------------------------------------------------------------------------

def stats_summary() -> str:
    from proxy.stats import stats
    return stats.summary()


def state() -> str:
    """Single snapshot the UI polls once a second."""
    from proxy.config import proxy_config
    from proxy.stats import stats
    return json.dumps({
        "running": is_running(),
        "listening": is_listening(),
        "host": proxy_config.host,
        "port": proxy_config.port,
        "stats": stats.summary(),
    })


def proxy_link(config_json: str = "") -> str:
    """The tg:// link for the configuration that is actually in effect.

    Mirrors the link logic in ``proxy/tg_ws_proxy.py``: with a Fake TLS domain
    the ee-secret (secret + hex-encoded SNI) is used, otherwise the plain dd
    secret.

    ``config_json`` is the caller's saved config. While the proxy is stopped it
    is applied first, because ``proxy_config`` would otherwise still hold the
    random secret generated when the module was imported — which is not the
    secret that a subsequent start would use. When the proxy is running its
    configuration is authoritative and is left alone.
    """
    from proxy.config import proxy_config
    from proxy.utils import get_link_host

    if config_json and not is_listening():
        try:
            _apply_config(json.loads(config_json))
        except Exception as exc:
            log.warning("proxy_link: ignoring unusable config: %r", exc)

    link_host = get_link_host(proxy_config.host)
    base = f"tg://proxy?server={link_host}&port={proxy_config.port}"
    ftls = proxy_config.fake_tls_domain
    if ftls:
        return f"{base}&secret=ee{proxy_config.secret}{ftls.encode('ascii').hex()}"
    return f"{base}&secret=dd{proxy_config.secret}"


def link_host() -> str:
    from proxy.config import proxy_config
    from proxy.utils import get_link_host
    return str(get_link_host(proxy_config.host))


# --------------------------------------------------------------------------
# connectivity tests
# --------------------------------------------------------------------------

def _seed_balancer_if_empty() -> list:
    from proxy.balancer import balancer
    from proxy.config import CFPROXY_DEFAULT_DOMAINS
    if not balancer.domains:
        balancer.update_domains_list(CFPROXY_DEFAULT_DOMAINS)
    return list(balancer.domains)


def test_cfproxy(domains_json: str, secure: bool = True) -> str:
    from proxy.config import coerce_domain_list
    from utils.connectivity import (run_cfproxy_auto_test,
                                    run_cfproxy_multi_test)

    try:
        domains = coerce_domain_list(json.loads(domains_json))
    except (ValueError, TypeError):
        domains = []

    if domains:
        per_domain = run_cfproxy_multi_test(domains, secure=bool(secure))
        return json.dumps({
            "auto": False,
            "per_domain": {d: {str(k): v for k, v in res.items()}
                           for d, res in per_domain.items()},
        })

    best, results = run_cfproxy_auto_test(_seed_balancer_if_empty(),
                                          secure=bool(secure))
    return json.dumps({
        "auto": True,
        "best_domain": best or "",
        "results": {str(k): v for k, v in results.items()},
    })


def test_cfworker(domains_json: str, secure: bool = True) -> str:
    from proxy.config import coerce_domain_list
    from utils.connectivity import run_cfworker_multi_test

    try:
        domains = coerce_domain_list(json.loads(domains_json))
    except (ValueError, TypeError):
        domains = []

    if not domains:
        return json.dumps({"per_domain": {}})

    per_domain = run_cfworker_multi_test(domains, secure=bool(secure))
    return json.dumps({
        "per_domain": {d: {str(k): v for k, v in res.items()}
                       for d, res in per_domain.items()},
    })


# --------------------------------------------------------------------------
# diagnostics
# --------------------------------------------------------------------------

def selftest() -> str:
    """Exercise every platform-specific dependency and report the outcome.

    Returns human-readable detail (not status codes) because the Android UI
    shows this text verbatim, which is the only practical way to diagnose a
    failure on a device that is not attached to a debugger.
    """
    import platform
    import ssl
    import sys

    checks = []

    def check(name: str, fn) -> None:
        try:
            detail = fn()
            checks.append({"name": name, "ok": True, "detail": detail or ""})
        except Exception as exc:
            checks.append({
                "name": name,
                "ok": False,
                "detail": "%s: %s" % (type(exc).__name__, exc),
            })

    check("python", lambda: "%s (%s)" % (sys.version.split()[0], platform.machine()))

    check("ssl", lambda: ssl.OPENSSL_VERSION)

    def _http2() -> str:
        # The 1.11.0 HTTP/2 media path needs httpx and hyper-h2; they are pure
        # Python, but they are the only non-stdlib dependency of the port.
        import h2
        import httpx
        return "httpx %s, h2 %s" % (httpx.__version__, h2.__version__)
    check("http2_stack", _http2)

    def _aes() -> str:
        from proxy import _aes
        return "backend=%s" % _aes.BACKEND_NAME
    check("aes_ctr", _aes)

    def _java_bridge() -> str:
        from java import jclass
        jclass("javax.crypto.Cipher")
        return "javax.crypto reachable"
    check("java_bridge", _java_bridge)

    def _ca() -> str:
        from proxy.utils import _ca_bundle_candidates, create_ssl_context
        create_ssl_context()
        found = [p for p in _ca_bundle_candidates() if p and os.path.isfile(p)]
        return found[0] if found else "platform defaults"
    check("ca_bundle", _ca)

    def _core() -> str:
        from proxy import __version__
        from proxy.config import proxy_config
        return "core %s, port %d" % (__version__, proxy_config.port)
    check("proxy_core", _core)

    def _asyncio() -> str:
        import socket as _socket
        loop = asyncio.new_event_loop()
        try:
            sock = _socket.socket()
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
            sock.close()
        finally:
            loop.close()
        return "local bind ok (port %d)" % port
    check("asyncio_socket", _asyncio)

    def _paths() -> str:
        from utils.app_paths import app_dir
        directory = app_dir()
        probe = directory / ".write_probe"
        probe.write_text("x", encoding="utf-8")
        probe.unlink()
        return str(directory)
    check("app_dir", _paths)

    return json.dumps({"ok": all(c["ok"] for c in checks), "checks": checks})
