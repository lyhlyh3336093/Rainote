"""U2 token 活性探测测试（test-first，Execution note）。

mock /getInfo 返回 200/401/网络错误，验证分流逻辑（KTD2）。
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import httpx
import pytest

from rainote.auth.token import (
    Credentials,
    CredentialsCorruptError,
    PROBE_INTERVAL,
    load_credentials,
    save_credentials,
    probe_token,
    mark_credentials_invalid,
    get_effective_token,
)
from rainote.errors import ExitCode


# ---------- 辅助：构造带 MockTransport 的 client ----------

def _make_client(handler, base_url="http://localhost:8080"):
    transport = httpx.MockTransport(handler)
    return httpx.Client(base_url=base_url, transport=transport)


def _getinfo_handler(code: int, msg: str = "操作成功", extra: dict | None = None):
    """返回指定 code 的 /getInfo handler。"""
    body = {"code": code, "msg": msg}
    if extra:
        body.update(extra)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/getInfo"
        return httpx.Response(200, json=body)

    return handler


# ---------- 活性探测 ----------

def test_probe_active_returns_true(monkeypatch, tmp_path):
    """mock /getInfo 返回 {code:200} → active=True, exit_code=None。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="t", username="admin"))
    client = _make_client(_getinfo_handler(200, extra={"user": {"userName": "admin"}}))
    result = probe_token(client, force=True)
    assert result.active is True
    assert result.exit_code is None


def test_probe_401_returns_auth_exit(monkeypatch, tmp_path):
    """mock /getInfo 返回 {code:401} → active=False, exit_code=AUTH(2)。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="t", username="admin"))
    client = _make_client(_getinfo_handler(401, msg="认证失败"))
    result = probe_token(client, force=True)
    assert result.active is False
    assert result.exit_code == ExitCode.AUTH


def test_probe_401_marks_credentials_invalid(monkeypatch, tmp_path):
    """code 401 标记 token 失效：credentials 被移除或失效。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="t", username="admin"))
    client = _make_client(_getinfo_handler(401))
    probe_token(client, force=True)
    # 失效后 credentials 应被标记无效（load 返回 None 或 token 清空）
    creds = load_credentials()
    assert creds is None or creds.token != "t"


def test_probe_network_error_returns_network_exit(monkeypatch, tmp_path):
    """mock /getInfo 抛 ConnectError → exit_code=NETWORK(10)，不标记 token 失效。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="t", username="admin"))

    def handler(request):
        raise httpx.ConnectError("connection refused")

    client = _make_client(handler)
    result = probe_token(client, force=True)
    assert result.active is False
    assert result.exit_code == ExitCode.NETWORK


def test_probe_network_error_does_not_invalidate_token(monkeypatch, tmp_path):
    """网络错误不标记 token 失效：credentials 仍含原 token。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="keep-me", username="admin"))

    def handler(request):
        raise httpx.ConnectError("timeout")

    client = _make_client(handler)
    probe_token(client, force=True)
    creds = load_credentials()
    assert creds is not None
    assert creds.token == "keep-me"


# ---------- 探测频次 ----------

def test_probe_skipped_when_recent_non_destructive(monkeypatch, tmp_path):
    """非破坏性命令：距上次探测 < 5 分钟时跳过 /getInfo 调用。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    # last_probe_at 设为当前时间
    save_credentials(Credentials(token="t", username="admin", last_probe_at=time.time()))

    called = {"count": 0}

    def handler(request):
        called["count"] += 1
        return httpx.Response(200, json={"code": 200})

    client = _make_client(handler)
    result = probe_token(client, force=False)  # 非破坏性
    assert called["count"] == 0  # 未调用 /getInfo
    assert result.active is True
    assert result.exit_code is None


def test_probe_forced_when_destructive_even_if_recent(monkeypatch, tmp_path):
    """破坏性命令：即使距上次探测 < 5 分钟也强制调 /getInfo。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="t", username="admin", last_probe_at=time.time()))

    called = {"count": 0}

    def handler(request):
        called["count"] += 1
        return httpx.Response(200, json={"code": 200, "user": {}})

    client = _make_client(handler)
    result = probe_token(client, force=True)  # 破坏性
    assert called["count"] == 1  # 强制调用
    assert result.active is True


def test_probe_called_when_stale_non_destructive(monkeypatch, tmp_path):
    """非破坏性命令：距上次探测 > 5 分钟时仍调用 /getInfo。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    # last_probe_at 设为 6 分钟前
    save_credentials(
        Credentials(token="t", username="admin", last_probe_at=time.time() - PROBE_INTERVAL - 60))

    called = {"count": 0}

    def handler(request):
        called["count"] += 1
        return httpx.Response(200, json={"code": 200, "user": {}})

    client = _make_client(handler)
    probe_token(client, force=False)
    assert called["count"] == 1


# ---------- credentials 读写 ----------

def test_save_load_roundtrip(monkeypatch, tmp_path):
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    creds = Credentials(token="abc", username="admin", login_at=1000.0)
    save_credentials(creds)
    loaded = load_credentials()
    assert loaded is not None
    assert loaded.token == "abc"
    assert loaded.username == "admin"
    assert loaded.login_at == 1000.0


def test_load_returns_none_when_missing(monkeypatch, tmp_path):
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    assert load_credentials() is None


def test_load_corrupt_raises_credentials_corrupt_error(monkeypatch, tmp_path):
    """credentials 损坏（JSON 解析失败）时抛 CredentialsCorruptError。"""
    from rainote.paths import credentials_path

    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    credentials_path().write_text("{not valid json", encoding="utf-8")
    with pytest.raises(CredentialsCorruptError):
        load_credentials()


def test_mark_credentials_invalid_removes_token(monkeypatch, tmp_path):
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    save_credentials(Credentials(token="t", username="admin"))
    mark_credentials_invalid()
    creds = load_credentials()
    assert creds is None or creds.token != "t"


# ---------- 有效 token 解析 ----------

def test_get_effective_token_prefers_env(monkeypatch, tmp_path):
    """RAINOTE_TOKEN 环境变量优先于 credentials 文件。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    monkeypatch.setenv("RAINOTE_TOKEN", "env-token")
    save_credentials(Credentials(token="file-token", username="admin"))
    assert get_effective_token() == "env-token"


def test_get_effective_token_falls_back_to_credentials(monkeypatch, tmp_path):
    """无 RAINOTE_TOKEN 时回退到 credentials 文件。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    monkeypatch.delenv("RAINOTE_TOKEN", raising=False)
    save_credentials(Credentials(token="file-token", username="admin"))
    assert get_effective_token() == "file-token"


def test_get_effective_token_returns_none_when_nothing(monkeypatch, tmp_path):
    """无环境变量且无 credentials 时返回 None。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    monkeypatch.delenv("RAINOTE_TOKEN", raising=False)
    assert get_effective_token() is None
