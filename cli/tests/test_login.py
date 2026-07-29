"""U2 登录流程测试 —— 三种验证码模式、验证码重试、/login 特判。"""

from __future__ import annotations

import httpx
import pytest

from rainote.auth.login import login, LoginResult
from rainote.errors import ExitCode


def _client(routes: dict, base_url="http://localhost:8080"):
    """构造 MockTransport client，routes: {path_method: handler_or_body}。"""

    def handler(request: httpx.Request) -> httpx.Response:
        key = f"{request.method} {request.url.path}"
        route = routes.get(key)
        if route is None:
            return httpx.Response(404, json={"code": 404, "msg": "not found"})
        if callable(route):
            return route(request)
        return httpx.Response(200, json=route)

    return httpx.Client(base_url=base_url, transport=httpx.MockTransport(handler))


CAPTCHA_ON = {"captchaEnabled": True, "uuid": "uuid-1", "img": "data:image/png;base64,xxx"}
CAPTCHA_OFF = {"captchaEnabled": False, "uuid": "", "img": ""}
LOGIN_OK = {"code": 200, "msg": "操作成功", "token": "jwt-token-xxx"}


# ---------- image 模式 ----------

def test_image_mode_captcha_disabled_success():
    """image 模式 + 后端 captchaEnabled=false：直接登录成功。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_OFF,
        "POST /login": LOGIN_OK,
    })
    result = login(client, "admin", "pwd", captcha_mode="image")
    assert result.success is True
    assert result.token == "jwt-token-xxx"


def test_image_mode_captcha_enabled_success():
    """image 模式 + captchaEnabled=true：prompt 输入 code 后登录成功。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_ON,
        "POST /login": LOGIN_OK,
    })
    result = login(
        client, "admin", "pwd", captcha_mode="image",
        prompt_func=lambda: "1234",
        open_image_func=lambda p: None,
    )
    assert result.success is True
    assert result.token == "jwt-token-xxx"


# ---------- skip 模式 ----------

def test_skip_mode_captcha_disabled_success():
    """skip 模式 + captchaEnabled=false：成功。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_OFF,
        "POST /login": LOGIN_OK,
    })
    result = login(client, "admin", "pwd", captcha_mode="skip")
    assert result.success is True
    assert result.token == "jwt-token-xxx"


def test_skip_mode_captcha_enabled_fails_exit6():
    """skip 模式 + captchaEnabled=true：报错退出码 6，不静默降级。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_ON,
        "POST /login": LOGIN_OK,
    })
    result = login(client, "admin", "pwd", captcha_mode="skip")
    assert result.success is False
    assert result.exit_code == ExitCode.VALIDATION


# ---------- code 模式 ----------

def test_code_mode_missing_uuid_fails():
    """code 模式缺 --captcha-uuid：报错退出。"""
    client = _client({"POST /login": LOGIN_OK})
    result = login(
        client, "admin", "pwd", captcha_mode="code",
        captcha_code="1234",  # 缺 uuid
    )
    assert result.success is False
    assert result.exit_code == ExitCode.VALIDATION


def test_code_mode_success():
    """code 模式 + --captcha-code + --captcha-uuid：成功。"""
    client = _client({"POST /login": LOGIN_OK})
    result = login(
        client, "admin", "pwd", captcha_mode="code",
        captcha_code="1234", captcha_uuid="abc",
    )
    assert result.success is True
    assert result.token == "jwt-token-xxx"


def test_code_mode_passes_code_and_uuid_to_login():
    """code 模式将 code+uuid 传入 /login body。"""
    captured = {}

    def login_handler(request):
        import json
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json=LOGIN_OK)

    client = _client({"POST /login": login_handler})
    login(client, "admin", "pwd", captcha_mode="code",
          captcha_code="9999", captcha_uuid="my-uuid")
    assert captured["body"]["code"] == "9999"
    assert captured["body"]["uuid"] == "my-uuid"
    assert captured["body"]["username"] == "admin"
    assert captured["body"]["password"] == "pwd"


# ---------- 验证码重试 ----------

def test_captcha_retry_success_on_second_attempt():
    """验证码重试：第一次 code 500 '验证码错误'，第二次 code 200 → 成功（重取 uuid）。"""
    calls = {"n": 0, "captcha_fetches": 0}

    def captcha_handler(request):
        calls["captcha_fetches"] += 1
        return httpx.Response(200, json={**CAPTCHA_ON, "uuid": f"uuid-{calls['captcha_fetches']}"})

    def login_handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(200, json={"code": 500, "msg": "验证码错误"})
        return httpx.Response(200, json=LOGIN_OK)

    client = _client({
        "GET /captchaImage": captcha_handler,
        "POST /login": login_handler,
    })
    prompts = iter(["wrong", "correct"])
    result = login(
        client, "admin", "pwd", captcha_mode="image",
        prompt_func=lambda: next(prompts),
        open_image_func=lambda p: None,
    )
    assert result.success is True
    assert calls["captcha_fetches"] == 2  # 重试时重取了新 uuid


def test_captcha_retry_limit_exit6():
    """验证码重试上限：3 次都 code 500 → 退出码 6。"""
    def login_handler(request):
        return httpx.Response(200, json={"code": 500, "msg": "验证码错误"})

    client = _client({
        "GET /captchaImage": CAPTCHA_ON,
        "POST /login": login_handler,
    })
    result = login(
        client, "admin", "pwd", captcha_mode="image",
        prompt_func=lambda: "1234",
        open_image_func=lambda p: None,
        max_retries=3,
    )
    assert result.success is False
    assert result.exit_code == ExitCode.VALIDATION


# ---------- /login 特判（KTD3） ----------

def test_login_password_error_exit6_not_5():
    """/login 特判：code 500 '用户名或密码错误' → 退出码 6（非 5）。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_OFF,
        "POST /login": {"code": 500, "msg": "用户名或密码错误"},
    })
    result = login(client, "admin", "wrongpwd", captcha_mode="skip", max_retries=1)
    assert result.success is False
    assert result.exit_code == ExitCode.VALIDATION


def test_login_server_error_exit5():
    """/login 特判：code 500 '服务异常'（不匹配关键词）→ 退出码 5。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_OFF,
        "POST /login": {"code": 500, "msg": "服务异常"},
    })
    result = login(client, "admin", "pwd", captcha_mode="skip", max_retries=1)
    assert result.success is False
    assert result.exit_code == ExitCode.SERVER


def test_login_captcha_keyword_jcaptcha_exit6():
    """/login 特判：msg 含 'jcaptcha' → 退出码 6。"""
    client = _client({
        "GET /captchaImage": CAPTCHA_OFF,
        "POST /login": {"code": 500, "msg": "jcaptcha error"},
    })
    result = login(client, "admin", "pwd", captcha_mode="skip", max_retries=1)
    assert result.exit_code == ExitCode.VALIDATION
