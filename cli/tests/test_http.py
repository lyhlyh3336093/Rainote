"""client/http.py HTTP 客户端测试。

覆盖：鉴权头注入/跳过、X-Requested-With、重试逻辑、--verbose 脱敏（R8）、
连接错误处理、业务码→退出码集成。
"""

from __future__ import annotations

import httpx
import pytest
import respx

from rainote import __version__
from rainote.client.http import RainoteClient, mask_authorization
from rainote.errors import ExitCode, RainoteError


BASE_URL = "http://localhost:8080"


# ---- 鉴权头注入与跳过 ----


class TestAuthHeader:
    @respx.mock
    def test_authorization_injected(self) -> None:
        """GET 业务接口时注入 Authorization: Bearer <token>。"""
        route = respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        client = RainoteClient(base_url=BASE_URL, token="my-jwt-token")
        try:
            client.get("/system/note/list")
        finally:
            client.close()
        assert route.called
        sent_auth = route.calls.last.request.headers.get("Authorization")
        assert sent_auth == "Bearer my-jwt-token"

    @respx.mock
    def test_login_skips_auth_header(self) -> None:
        """POST /login 跳过鉴权头（登录前无 token）。"""
        route = respx.post(f"{BASE_URL}/login").respond(
            200, json={"code": 200, "msg": "ok", "token": "abc"}
        )
        client = RainoteClient(base_url=BASE_URL, token="should-not-be-used")
        try:
            client.post("/login", is_login=True, json={"username": "u", "password": "p"})
        finally:
            client.close()
        assert route.called
        sent_auth = route.calls.last.request.headers.get("Authorization")
        assert sent_auth is None

    @respx.mock
    def test_captcha_image_skips_auth_header(self) -> None:
        """GET /captchaImage 跳过鉴权头（登录前获取验证码）。"""
        route = respx.get(f"{BASE_URL}/captchaImage").respond(
            200, json={"code": 200, "msg": "ok", "img": "base64..."}
        )
        client = RainoteClient(base_url=BASE_URL, token="should-not-be-used")
        try:
            client.get("/captchaImage")
        finally:
            client.close()
        assert route.called
        sent_auth = route.calls.last.request.headers.get("Authorization")
        assert sent_auth is None

    @respx.mock
    def test_x_requested_with_header(self) -> None:
        """所有请求注入 X-Requested-With: rainote-cli/<version>。"""
        route = respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        client = RainoteClient(base_url=BASE_URL, token="t")
        try:
            client.get("/system/note/list")
        finally:
            client.close()
        sent = route.calls.last.request.headers.get("X-Requested-With")
        assert sent == f"rainote-cli/{__version__}"

    @respx.mock
    def test_no_token_no_auth_header(self) -> None:
        """无 token 时不注入 Authorization（allow_anonymous 场景）。"""
        route = respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        client = RainoteClient(base_url=BASE_URL, token=None, allow_anonymous=True)
        try:
            client.get("/system/note/list")
        finally:
            client.close()
        assert route.called
        sent_auth = route.calls.last.request.headers.get("Authorization")
        assert sent_auth is None


# ---- 重试逻辑 ----


class TestRetry:
    @respx.mock
    def test_biz_500_retries_then_succeeds(self) -> None:
        """业务码 500 触发重试，最终成功。"""
        route = respx.get(f"{BASE_URL}/system/note/list").mock(
            side_effect=[
                httpx.Response(200, json={"code": 500, "msg": "服务异常"}),
                httpx.Response(200, json={"code": 500, "msg": "服务异常"}),
                httpx.Response(
                    200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
                ),
            ]
        )
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=3, retry_delay=0
        )
        try:
            api, exit_code = client.get("/system/note/list")
        finally:
            client.close()
        assert route.call_count == 3
        assert api.ok is True
        assert exit_code == ExitCode.OK

    @respx.mock
    def test_biz_500_retries_exhausted(self) -> None:
        """业务码 500 重试耗尽 → 返回最后一次（退出码 5）。"""
        route = respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 500, "msg": "服务异常"}
        )
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=2, retry_delay=0
        )
        try:
            api, exit_code = client.get("/system/note/list")
        finally:
            client.close()
        # retry_max=2 → 初次 + 2 次重试 = 3 次调用
        assert route.call_count == 3
        assert exit_code == ExitCode.SERVER

    @respx.mock
    def test_4xx_no_retry(self) -> None:
        """4xx（如 401）不重试，立即返回。"""
        route = respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 401, "msg": "未授权"}
        )
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=3, retry_delay=0
        )
        try:
            api, exit_code = client.get("/system/note/list")
        finally:
            client.close()
        assert route.call_count == 1
        assert exit_code == ExitCode.AUTH

    @respx.mock
    def test_connect_error_retries_then_raises(self) -> None:
        """连接错误重试耗尽 → 抛出 RainoteError(NETWORK)。"""
        route = respx.get(f"{BASE_URL}/system/note/list").mock(
            side_effect=httpx.ConnectError("Connection refused")
        )
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=2, retry_delay=0
        )
        try:
            with pytest.raises(RainoteError) as exc_info:
                client.get("/system/note/list")
            assert exc_info.value.exit_code == ExitCode.NETWORK
        finally:
            client.close()
        assert route.call_count == 3

    @respx.mock
    def test_connect_error_then_succeeds(self) -> None:
        """连接错误后重试成功。"""
        route = respx.get(f"{BASE_URL}/system/note/list").mock(
            side_effect=[
                httpx.ConnectError("Connection refused"),
                httpx.Response(
                    200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
                ),
            ]
        )
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=3, retry_delay=0
        )
        try:
            api, exit_code = client.get("/system/note/list")
        finally:
            client.close()
        assert route.call_count == 2
        assert api.ok is True

    @respx.mock
    def test_login_500_no_retry_for_validation(self) -> None:
        """/login 特判：code 500 + 验证码关键词 → 退出码 6，且不重试（非服务错误）。

        特判为 VALIDATION 后属于 4xx 类，不应重试。
        """
        route = respx.post(f"{BASE_URL}/login").respond(
            200, json={"code": 500, "msg": "验证码错误"}
        )
        client = RainoteClient(
            base_url=BASE_URL, token=None, retry_max=3, retry_delay=0
        )
        try:
            api, exit_code = client.post("/login", is_login=True, json={})
        finally:
            client.close()
        assert route.call_count == 1
        assert exit_code == ExitCode.VALIDATION


# ---- --verbose 脱敏 ----


class TestVerboseMask:
    def test_mask_authorization_long(self) -> None:
        """脱敏：保留前 8 字符 + ***。"""
        assert mask_authorization("Bearer eyJhbGciOiJIUzI1") == "Bearer e***"

    def test_mask_authorization_short(self) -> None:
        """短于 8 字符 → 全 ***。"""
        assert mask_authorization("short") == "***"

    def test_mask_authorization_empty(self) -> None:
        assert mask_authorization("") == "***"

    def test_mask_authorization_exact_8(self) -> None:
        """恰好 8 字符 → 前 8 + ***。"""
        assert mask_authorization("Bearer08") == "Bearer08***"

    @respx.mock
    def test_verbose_logs_masked_auth(self, capsys: pytest.CaptureFixture[str]) -> None:
        """--verbose 时日志中 Authorization 显示脱敏值（R8）。"""
        respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        client = RainoteClient(
            base_url=BASE_URL, token="eyJhbGciOiJIUzI1NiJ9.payload.sig", verbose=True
        )
        try:
            client.get("/system/note/list")
        finally:
            client.close()
        captured = capsys.readouterr()
        # 日志输出到 stderr
        assert "Bearer e***" in captured.err
        # 原始 token 不应出现在日志中
        assert "eyJhbGciOiJIUzI1NiJ9.payload.sig" not in captured.err

    @respx.mock
    def test_non_verbose_no_auth_log(self, capsys: pytest.CaptureFixture[str]) -> None:
        """非 verbose 模式不输出请求日志。"""
        respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        client = RainoteClient(base_url=BASE_URL, token="secret-token", verbose=False)
        try:
            client.get("/system/note/list")
        finally:
            client.close()
        captured = capsys.readouterr()
        assert "Authorization" not in captured.err


# ---- 响应解析集成 ----


class TestRequestIntegration:
    @respx.mock
    def test_get_returns_api_response_and_exit_code(self) -> None:
        respx.get(f"{BASE_URL}/system/note/list").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "title": "笔记1"}],
                "total": 1,
            },
        )
        client = RainoteClient(base_url=BASE_URL, token="t", retry_delay=0)
        try:
            api, exit_code = client.get("/system/note/list")
        finally:
            client.close()
        assert api.is_page is True
        assert api.rows == [{"id": 1, "title": "笔记1"}]
        assert api.total == 1
        assert exit_code == ExitCode.OK

    @respx.mock
    def test_post_login_returns_token(self) -> None:
        respx.post(f"{BASE_URL}/login").respond(
            200, json={"code": 200, "msg": "ok", "token": "jwt-token-xyz"}
        )
        client = RainoteClient(base_url=BASE_URL, token=None, retry_delay=0)
        try:
            api, exit_code = client.post(
                "/login", is_login=True, json={"username": "u", "password": "p"}
            )
        finally:
            client.close()
        assert api.ok is True
        assert api.has_data is False  # token 在顶层，非 data 键
        assert api.raw.get("token") == "jwt-token-xyz"
        assert exit_code == ExitCode.OK

    @respx.mock
    def test_non_json_fallback(self) -> None:
        """HTTP 502 非 JSON → 退出码 5。"""
        respx.get(f"{BASE_URL}/system/note/list").respond(
            502, content=b"<html>Bad Gateway</html>", headers={"content-type": "text/html"}
        )
        client = RainoteClient(base_url=BASE_URL, token="t", retry_delay=0)
        try:
            api, exit_code = client.get("/system/note/list")
        finally:
            client.close()
        assert exit_code == ExitCode.SERVER

    @respx.mock
    def test_timeout_raises_network_error(self) -> None:
        """请求超时 → RainoteError(NETWORK)。"""
        respx.get(f"{BASE_URL}/system/note/list").mock(
            side_effect=httpx.TimeoutException("timed out")
        )
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=1, retry_delay=0
        )
        try:
            with pytest.raises(RainoteError) as exc_info:
                client.get("/system/note/list")
            assert exc_info.value.exit_code == ExitCode.NETWORK
        finally:
            client.close()


# ---- 上下文管理 ----


class TestContextManager:
    @respx.mock
    def test_context_manager(self) -> None:
        respx.get(f"{BASE_URL}/system/note/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with RainoteClient(base_url=BASE_URL, token="t", retry_delay=0) as client:
            api, _ = client.get("/system/note/list")
        assert api.ok is True
