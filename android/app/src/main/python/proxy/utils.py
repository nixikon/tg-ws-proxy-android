import os
import socket as _socket
import urllib.request
import http.client
import ssl
import logging
import re

from typing import Optional, Dict, List
from urllib.request import Request

log = logging.getLogger('tg-mtproto-proxy')


ZERO_64 = b'\x00' * 64
HANDSHAKE_LEN = 64
SKIP_LEN = 8
PREKEY_LEN = 32
KEY_LEN = 32
IV_LEN = 16
PROTO_TAG_POS = 56
DC_IDX_POS = 60

PROTO_TAG_ABRIDGED = b'\xef\xef\xef\xef'
PROTO_TAG_INTERMEDIATE = b'\xee\xee\xee\xee'
PROTO_TAG_SECURE = b'\xdd\xdd\xdd\xdd'

PROTO_ABRIDGED_INT = 0xEFEFEFEF
PROTO_INTERMEDIATE_INT = 0xEEEEEEEE
PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD

RESERVED_FIRST_BYTES = {0xEF}
RESERVED_STARTS = {b'\x48\x45\x41\x44', b'\x50\x4F\x53\x54',
                    b'\x47\x45\x54\x20', b'\xee\xee\xee\xee',
                    b'\xdd\xdd\xdd\xdd', b'\x16\x03\x01\x02'}
RESERVED_CONTINUE = b'\x00\x00\x00\x00'

_GITHUB_IPS: Dict[str, str] = {
    "release-assets.githubusercontent.com": "185.199.109.133",
    "raw.githubusercontent.com": "185.199.109.133",
}

DC_DEFAULT_IPS: Dict[int, str] = {
    1: '149.154.175.50',
    2: '149.154.167.51',
    3: '149.154.175.100',
    4: '149.154.167.91',
    5: '149.154.171.5',
    203: '91.105.192.100'
}

DC_TEST_IPS: Dict[int, str] = {
    1: '149.154.175.10',
    2: '149.154.167.40',
    3: '149.154.175.117',
}

WS_PATH = '/apiws'
WS_PATH_TEST = WS_PATH + '_test'


def ws_domains(dc: int, is_media) -> List[str]:
    if dc == 203:
        dc = 2
    if not is_media:
        return [f'kws{dc}.web.telegram.org']
    return [f'kws{dc}-1.web.telegram.org', f'kws{dc}.web.telegram.org']


def human_bytes(n: int) -> str:
    for unit in ('B', 'KB', 'MB', 'GB'):
        if abs(n) < 1024:
            return f"{n:.1f}{unit}"
        n /= 1024  # type: ignore
    return f"{n:.1f}TB"


def get_link_host(host: str) -> Optional[str]:
    if host == '0.0.0.0':
        try:
            with _socket.socket(_socket.AF_INET, _socket.SOCK_DGRAM) as _s:
                _s.connect(('8.8.8.8', 80))
                link_host = _s.getsockname()[0]
        except OSError:
            link_host = '127.0.0.1'
        return link_host
    else:
        return host


class DomainCensorFilter(logging.Filter):
    domain_pattern = re.compile(
        r'(?<![\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+'
        r'[a-zA-Z]{2,}(?![\w-])'
    )

    def _censor_match(self, match):
        domain = match.group()
        normalized = domain.casefold().rstrip('.')
        if normalized == 'telegram.org' or normalized.endswith('.telegram.org') or normalized.endswith('.log'):
            return domain
        parts = domain.split('.')
        if len(parts) < 2:
            return domain
        return '.'.join(
            part if i == len(parts) - 1 else
            part[:len(part) // 2] + '*' * (len(part) - len(part) // 2)
            for i, part in enumerate(parts)
        )

    def filter(self, record):
        record.msg = self.domain_pattern.sub(self._censor_match, record.getMessage())
        record.args = ()
        return True


class _PinnedHTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, req: Request):
        host = req.host.split(":")[0]
        ip = _GITHUB_IPS.get(host)
        if not ip:
            return super().https_open(req)
        pinned = ip

        class _Conn(http.client.HTTPSConnection):
            def connect(self):
                self.sock = _socket.create_connection(
                    (pinned, self.port or 443),
                    self.timeout,
                    self.source_address,
                )
                if self._tunnel_host:
                    self._tunnel()
                self.sock = self._context.wrap_socket(
                    self.sock, server_hostname=self._tunnel_host or self.host
                )

        try:
            return self.do_open(_Conn, req, context=self._context)
        except Exception:
            return super().https_open(req)


_ca_bundle_override: Optional[str] = None


def set_ca_bundle(path: str) -> None:
    """Force a specific CA bundle file (the Android UI extracts it from assets).

    Must be called before any SSL context is built — ``proxy.raw_websocket``
    creates its contexts at import time.
    """
    global _ca_bundle_override
    _ca_bundle_override = path or None


def _ca_bundle_candidates() -> List[str]:
    """Files that may hold the CA bundle, most specific first."""
    candidates: List[str] = []
    if _ca_bundle_override:
        candidates.append(_ca_bundle_override)
    env_path = os.environ.get("TG_WS_PROXY_CA_BUNDLE")
    if env_path:
        candidates.append(env_path)
    # <app>/certs/cacert.pem — shipped inside the APK for Android, where
    # neither certifi nor a system OpenSSL trust store is guaranteed.
    here = os.path.dirname(os.path.abspath(__file__))
    parent = os.path.dirname(here)
    candidates.append(os.path.join(parent, "certs", "cacert.pem"))
    candidates.append(os.path.join(parent, "cacert.pem"))
    candidates.append(os.path.join(here, "certs", "cacert.pem"))
    candidates.append(os.path.join(here, "cacert.pem"))
    try:
        import certifi
        candidates.append(certifi.where())
    except Exception:
        pass
    return candidates


# Directories holding hashed CA certificates. Android stores its system trust
# anchors this way, which is the layout OpenSSL expects for ``capath``.
_CA_DIRECTORIES = (
    "/system/etc/security/cacerts",
    "/apex/com.android.conscrypt/cacerts",
    "/etc/ssl/certs",
    "/etc/pki/tls/certs",
)


def create_ssl_context(*, check_hostname: bool = True) -> ssl.SSLContext:
    """TLS client context that verifies against a real CA bundle.

    Android has no OpenSSL trust store that ``set_default_verify_paths`` can
    find, so a bundled bundle (or the system ``cacerts`` directory) is loaded
    explicitly; only when neither is available do we fall back to the
    platform defaults.
    """
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.verify_mode = ssl.CERT_REQUIRED
    context.check_hostname = check_hostname

    loaded = False
    for path in _ca_bundle_candidates():
        if path and os.path.isfile(path):
            try:
                context.load_verify_locations(cafile=path)
                loaded = True
                log.debug("CA bundle loaded from %s", path)
                break
            except (OSError, ssl.SSLError):
                continue

    if not loaded:
        for capath in _CA_DIRECTORIES:
            if os.path.isdir(capath):
                try:
                    context.load_verify_locations(capath=capath)
                    loaded = True
                    log.debug("CA bundle loaded from directory %s", capath)
                    break
                except (OSError, ssl.SSLError):
                    continue

    if not loaded:
        # Worth surfacing: TLS verification would then depend entirely on
        # whatever OpenSSL can find by default.
        log.info("No CA bundle found; falling back to platform defaults")
        try:
            context.load_default_certs()
        except Exception:
            pass

    return context


def build_github_opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(
        _PinnedHTTPSHandler(context=create_ssl_context()))
