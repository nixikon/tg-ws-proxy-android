"""
AES-CTR shim.

Backends are tried in order and the first one that reproduces a known-answer
test wins:

  1. JVM ``javax.crypto.Cipher`` through Chaquopy — used on Android. Native
     speed with zero pip dependencies, and its CTR counter arithmetic matches
     OpenSSL (verified against NIST SP 800-38A F.5.1, including the 1-byte
     incremental ``update`` calls the relay feeds it).
  2. ``cryptography`` — desktop / Docker.
  3. ctypes over the system OpenSSL ``libcrypto`` — routers and other embedded
     boxes without a Rust toolchain.

The public surface mimics the small subset of
``cryptography.hazmat.primitives.ciphers`` that this project actually uses::

    Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor().update(data)
"""
from __future__ import annotations

import logging

log = logging.getLogger('tg-mtproto-proxy')


# NIST SP 800-38A, F.5.1 (AES-128-CTR). Every byte the proxy relays depends on
# this exact counter arithmetic, so each backend is validated at import time
# instead of being trusted.
_KAT_KEY = bytes.fromhex('2b7e151628aed2a6abf7158809cf4f3c')
_KAT_IV = bytes.fromhex('f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff')
_KAT_PT = bytes.fromhex(
    '6bc1bee22e409f96e93d7e117393172a'
    'ae2d8a571e03ac9c9eb76fac45af8e51')
_KAT_CT = bytes.fromhex(
    '874d6191b620e3261bef6864990db6ce'
    '9806f66b7970fdff8617187bb9fffdff')


def _kat_ok(cipher_cls, algorithms, modes) -> bool:
    """Run the known-answer test, including chunked updates."""
    try:
        stream = cipher_cls(
            algorithms.AES(_KAT_KEY), modes.CTR(_KAT_IV)).encryptor()
        if stream.update(_KAT_PT) != _KAT_CT:
            return False
        # A freshly initialised stream must behave identically when fed in small
        # pieces — that is the path the relay actually uses.
        stream = cipher_cls(
            algorithms.AES(_KAT_KEY), modes.CTR(_KAT_IV)).encryptor()
        out = bytearray()
        for i in range(0, len(_KAT_PT), 3):
            out += stream.update(_KAT_PT[i:i + 3])
        return bytes(out) == _KAT_CT
    except Exception:
        return False


def _jvm_backend():
    """Chaquopy -> ``javax.crypto`` (Android)."""
    cipher_cls = key_spec_cls = iv_spec_cls = None
    try:
        from java.javax.crypto import Cipher as cipher_cls
        from java.javax.crypto.spec import IvParameterSpec as iv_spec_cls
        from java.javax.crypto.spec import SecretKeySpec as key_spec_cls
    except Exception:
        # `java.jclass` is the documented way to resolve a class dynamically.
        # (Class.forName would return a java.lang.Class *object*, which cannot
        # be called as a constructor, so it is not usable here.)
        try:
            from java import jclass
            cipher_cls = jclass("javax.crypto.Cipher")
            key_spec_cls = jclass("javax.crypto.spec.SecretKeySpec")
            iv_spec_cls = jclass("javax.crypto.spec.IvParameterSpec")
        except Exception:
            return None

    if cipher_cls is None or key_spec_cls is None or iv_spec_cls is None:
        return None

    _JCipher = cipher_cls
    SecretKeySpec = key_spec_cls
    IvParameterSpec = iv_spec_cls

    class algorithms:
        class AES:
            __slots__ = ("key",)

            def __init__(self, key: bytes):
                if len(key) not in (16, 24, 32):
                    raise ValueError("AES key must be 16/24/32 bytes")
                self.key = bytes(key)

    class modes:
        class CTR:
            __slots__ = ("iv",)

            def __init__(self, iv: bytes):
                if len(iv) != 16:
                    raise ValueError("CTR IV must be 16 bytes")
                self.iv = bytes(iv)

    class _CtrStream:
        __slots__ = ("_cipher",)

        def __init__(self, key: bytes, iv: bytes):
            cipher = _JCipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                _JCipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )
            self._cipher = cipher

        def update(self, data: bytes) -> bytes:
            if not data:
                # Java's Cipher.update returns null for an empty input.
                return b""
            out = self._cipher.update(bytes(data))
            return b"" if out is None else bytes(out)

    class Cipher:
        __slots__ = ("_key", "_iv")

        def __init__(self, algorithm, mode):
            if not isinstance(algorithm, algorithms.AES):
                raise TypeError("only AES is supported")
            if not isinstance(mode, modes.CTR):
                raise TypeError("only CTR mode is supported")
            self._key = algorithm.key
            self._iv = mode.iv

        def encryptor(self) -> _CtrStream:
            return _CtrStream(self._key, self._iv)

        # CTR is symmetric — decryption is encryption with the same keystream.
        decryptor = encryptor

    if not _kat_ok(Cipher, algorithms, modes):
        return None
    return Cipher, algorithms, modes


def _cryptography_backend():
    try:
        from cryptography.hazmat.primitives.ciphers import (
            Cipher, algorithms, modes,
        )
    except ImportError:
        return None
    if not _kat_ok(Cipher, algorithms, modes):
        return None
    return Cipher, algorithms, modes


def _ctypes_backend():
    try:
        import ctypes
        import ctypes.util
    except ImportError:
        return None

    def _load_libcrypto():
        name = ctypes.util.find_library("crypto")
        candidates = []
        if name:
            candidates.append(name)
        candidates += [
            "libcrypto.so.3", "libcrypto.so.1.1", "libcrypto.so.1.0.0",
            "libcrypto.so", "/opt/lib/libcrypto.so",
            "/opt/lib/libcrypto.so.1.1", "/opt/lib/libcrypto.so.3",
        ]
        last_err = None
        for c in candidates:
            try:
                return ctypes.CDLL(c)
            except OSError as e:
                last_err = e
        raise RuntimeError(
            "libcrypto not found; install openssl-util or "
            "`opkg install libopenssl`. Last error: %r" % last_err
        )

    try:
        _libcrypto = _load_libcrypto()

        _libcrypto.EVP_CIPHER_CTX_new.restype = ctypes.c_void_p
        _libcrypto.EVP_CIPHER_CTX_free.argtypes = [ctypes.c_void_p]
        _libcrypto.EVP_aes_128_ctr.restype = ctypes.c_void_p
        _libcrypto.EVP_aes_192_ctr.restype = ctypes.c_void_p
        _libcrypto.EVP_aes_256_ctr.restype = ctypes.c_void_p
        _libcrypto.EVP_EncryptInit_ex.argtypes = [
            ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
            ctypes.c_char_p, ctypes.c_char_p,
        ]
        _libcrypto.EVP_EncryptInit_ex.restype = ctypes.c_int
        _libcrypto.EVP_EncryptUpdate.argtypes = [
            ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int),
            ctypes.c_char_p, ctypes.c_int,
        ]
        _libcrypto.EVP_EncryptUpdate.restype = ctypes.c_int
    except Exception:
        return None

    _EVP_BY_KEY = {
        16: _libcrypto.EVP_aes_128_ctr,
        24: _libcrypto.EVP_aes_192_ctr,
        32: _libcrypto.EVP_aes_256_ctr,
    }

    class algorithms:
        class AES:
            __slots__ = ("key",)

            def __init__(self, key: bytes):
                if len(key) not in _EVP_BY_KEY:
                    raise ValueError("AES key must be 16/24/32 bytes")
                self.key = bytes(key)

    class modes:
        class CTR:
            __slots__ = ("iv",)

            def __init__(self, iv: bytes):
                if len(iv) != 16:
                    raise ValueError("CTR IV must be 16 bytes")
                self.iv = bytes(iv)

    class _CtrStream:
        __slots__ = ("_ctx",)

        def __init__(self, key: bytes, iv: bytes):
            ctx = _libcrypto.EVP_CIPHER_CTX_new()
            if not ctx:
                raise RuntimeError("EVP_CIPHER_CTX_new failed")
            self._ctx = ctx
            evp = _EVP_BY_KEY[len(key)]()
            if _libcrypto.EVP_EncryptInit_ex(ctx, evp, None, key, iv) != 1:
                _libcrypto.EVP_CIPHER_CTX_free(ctx)
                self._ctx = None
                raise RuntimeError("EVP_EncryptInit_ex failed")

        def update(self, data: bytes) -> bytes:
            if not data:
                return b""
            outlen = ctypes.c_int(0)
            buf = ctypes.create_string_buffer(len(data) + 16)
            if _libcrypto.EVP_EncryptUpdate(
                self._ctx, buf, ctypes.byref(outlen), bytes(data), len(data)
            ) != 1:
                raise RuntimeError("EVP_EncryptUpdate failed")
            return buf.raw[:outlen.value]

        def __del__(self):
            ctx = getattr(self, "_ctx", None)
            if ctx:
                _libcrypto.EVP_CIPHER_CTX_free(ctx)
                self._ctx = None

    class Cipher:
        __slots__ = ("_key", "_iv")

        def __init__(self, algorithm, mode):
            if not isinstance(algorithm, algorithms.AES):
                raise TypeError("only AES is supported")
            if not isinstance(mode, modes.CTR):
                raise TypeError("only CTR mode is supported")
            self._key = algorithm.key
            self._iv = mode.iv

        def encryptor(self) -> _CtrStream:
            return _CtrStream(self._key, self._iv)

        decryptor = encryptor

    if not _kat_ok(Cipher, algorithms, modes):
        return None
    return Cipher, algorithms, modes


BACKEND_NAME = ""
BACKEND_FAILURES = []


def _select_backend():
    global BACKEND_NAME, BACKEND_FAILURES
    failures = []
    for name, factory in (
        ("jvm", _jvm_backend),
        ("cryptography", _cryptography_backend),
        ("ctypes-libcrypto", _ctypes_backend),
    ):
        try:
            backend = factory()
        except Exception as exc:  # pragma: no cover - defensive
            failures.append(f"{name}: {exc!r}")
            continue
        if backend is not None:
            BACKEND_NAME = name
            log.debug("AES-CTR backend: %s", name)
            return backend
        failures.append(f"{name}: unavailable or failed known-answer test")

    BACKEND_FAILURES = failures
    raise RuntimeError(
        "No usable AES-CTR backend found (" + "; ".join(failures) + ")"
    )


Cipher, algorithms, modes = _select_backend()
