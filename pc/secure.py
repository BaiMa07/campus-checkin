# -*- coding: utf-8 -*-
"""
用 Windows DPAPI 加密保存密码。

DPAPI 把密钥绑定在当前 Windows 用户身上：同一台机器同一个账号能解开，
文件被拷到别的电脑或别的用户下就解不开，所以不用自己管密钥。
"""

import base64
import ctypes
import ctypes.wintypes as wintypes


class _Blob(ctypes.Structure):
    _fields_ = [
        ("cbData", wintypes.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_char)),
    ]


_crypt32 = ctypes.windll.crypt32
_kernel32 = ctypes.windll.kernel32

CRYPTPROTECT_UI_FORBIDDEN = 0x01


def _to_blob(data):
    buf = ctypes.create_string_buffer(data, len(data))
    blob = _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    return blob, buf


def _from_blob(blob):
    return ctypes.string_at(blob.pbData, blob.cbData)


def encrypt(text):
    """明文 -> base64 字符串；失败返回空串"""
    if not text:
        return ""
    blob_in, keep = _to_blob(text.encode("utf-8"))
    blob_out = _Blob()
    ok = _crypt32.CryptProtectData(
        ctypes.byref(blob_in), None, None, None, None,
        CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(blob_out))
    if not ok:
        return ""
    try:
        return base64.b64encode(_from_blob(blob_out)).decode("ascii")
    finally:
        _kernel32.LocalFree(blob_out.pbData)


def decrypt(token):
    """base64 字符串 -> 明文；失败返回空串"""
    if not token:
        return ""
    try:
        raw = base64.b64decode(token)
    except Exception:
        return ""
    blob_in, keep = _to_blob(raw)
    blob_out = _Blob()
    ok = _crypt32.CryptUnprotectData(
        ctypes.byref(blob_in), None, None, None, None,
        CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(blob_out))
    if not ok:
        return ""
    try:
        return _from_blob(blob_out).decode("utf-8", "replace")
    finally:
        _kernel32.LocalFree(blob_out.pbData)
