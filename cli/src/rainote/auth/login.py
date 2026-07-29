"""登录流程 —— 三种验证码模式 + 验证码重试 + /login 特判（KTD3）。

三种验证码模式（R1）：
- image（默认）：GET /captchaImage，若 captchaEnabled=true 则保存图片、打开、prompt 输入 code；
  captchaEnabled=false 则直接登录。验证码错误时重试（重取 uuid+img，最多 max_retries 次）。
- skip：GET /captchaImage，若 captchaEnabled=true 立即报错退出码 6（不静默降级）；
  false 则直接登录。
- code：用户自备 code+uuid（CLI 不绕过后端验证码机制），必须配合 --captcha-uuid。

验证码重试（P1.3）：后端 validateCaptcha 在校验前先 deleteObject(verifyKey) → 验证码单次消费。
每次重试重新调 /captchaImage 获取新 uuid+img，保留 username/password 不重输入。

/login 特判（KTD3）：code 500 + msg 含 jcaptcha|password|captcha|验证码|密码 → 退出码 6（非 5）。
"""

from __future__ import annotations

import base64
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from typing import Callable, Optional

import httpx

from ..errors import ExitCode, classify_login_error


@dataclass
class LoginResult:
    """登录结果。"""

    success: bool
    token: Optional[str] = None
    exit_code: Optional[int] = None
    message: str = ""


def _post_login(
    client: httpx.Client,
    username: str,
    password: str,
    code: Optional[str] = None,
    uuid: Optional[str] = None,
) -> dict:
    """POST /login，返回响应 body。"""
    payload: dict = {"username": username, "password": password}
    if code is not None:
        payload["code"] = code
    if uuid is not None:
        payload["uuid"] = uuid
    resp = client.post("/login", json=payload)
    return resp.json()


def _interpret(body: dict) -> tuple[bool, Optional[str], Optional[int], bool]:
    """解析 /login 响应。

    Returns:
        (success, token, exit_code, retryable)
        retryable=True 表示验证码类错误，image 模式可重试。
    """
    code = body.get("code")
    msg = body.get("msg", "") or ""
    if code == 200:
        return True, body.get("token"), None, False
    if code == 401:
        return False, None, ExitCode.AUTH, False
    if code == 500:
        ec = classify_login_error(code, msg)
        # 仅验证码类错误（exit 6）可重试；密码错误虽映射 6 但重试无意义，
        # 但 image 模式重试会重新取验证码，仍交给上层循环按 max_retries 控制
        return False, None, ec, ec == ExitCode.VALIDATION
    return False, None, ExitCode.SERVER, False


def _save_captcha_image(img_b64: str, uuid: str) -> Optional[str]:
    """将 base64 验证码图片保存到临时文件，返回路径。失败返回 None。"""
    try:
        # 去除可能的 data:image/...;base64, 前缀
        if "," in img_b64:
            img_b64 = img_b64.split(",", 1)[1]
        data = base64.b64decode(img_b64)
        suffix = ".jpg"
        fd, path = tempfile.mkstemp(prefix=f"rainote-captcha-{uuid}-", suffix=suffix)
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        return path
    except (ValueError, OSError):
        return None


def _open_image(path: str) -> None:
    """用系统默认程序打开图片。"""
    try:
        if sys.platform == "darwin":
            os.system(f'open "{path}"')
        elif os.name == "nt":
            os.startfile(path)  # type: ignore[attr-defined]
        else:
            os.system(f'xdg-open "{path}"')
    except OSError:
        pass


def _prompt_captcha(
    captcha: dict,
    prompt_func: Optional[Callable[[], str]],
    open_image_func: Optional[Callable[[str], None]],
) -> Optional[str]:
    """保存并打开验证码图片，prompt 输入 code。"""
    img = captcha.get("img", "") or ""
    uuid = captcha.get("uuid", "") or ""
    if img:
        tmp = _save_captcha_image(img, uuid)
        if tmp:
            if open_image_func:
                try:
                    open_image_func(tmp)
                except OSError:
                    pass
            else:
                _open_image(tmp)
    if prompt_func is not None:
        try:
            return prompt_func()
        except (EOFError, StopIteration):
            return None
    try:
        return input("请输入验证码: ")
    except EOFError:
        return None


# ---------- 三种模式 ----------

def _login_skip(client: httpx.Client, username: str, password: str) -> LoginResult:
    captcha = client.get("/captchaImage").json()
    if captcha.get("captchaEnabled"):
        return LoginResult(
            False, None, ExitCode.VALIDATION,
            "后端启用验证码，skip 模式不可用。请使用 --captcha-mode code --captcha-code <code> "
            "--captcha-uuid <uuid>，或使用 image 模式，或在后端禁用验证码。",
        )
    body = _post_login(client, username, password)
    success, token, ec, _ = _interpret(body)
    if success:
        return LoginResult(True, token)
    return LoginResult(False, None, ec, body.get("msg", ""))


def _login_code(
    client: httpx.Client,
    username: str,
    password: str,
    code: Optional[str],
    uuid: Optional[str],
) -> LoginResult:
    if uuid is None:
        return LoginResult(
            False, None, ExitCode.VALIDATION,
            "code 模式必须配合 --captcha-uuid 参数（uuid 仍需从 /captchaImage 获取，CLI 不绕过验证码机制）。",
        )
    body = _post_login(client, username, password, code=code, uuid=uuid)
    success, token, ec, _ = _interpret(body)
    if success:
        return LoginResult(True, token)
    return LoginResult(False, None, ec, body.get("msg", ""))


def _login_image(
    client: httpx.Client,
    username: str,
    password: str,
    max_retries: int,
    prompt_func: Optional[Callable[[], str]],
    open_image_func: Optional[Callable[[str], None]],
) -> LoginResult:
    captcha = client.get("/captchaImage").json()
    if not captcha.get("captchaEnabled"):
        # 无需验证码，直接登录
        body = _post_login(client, username, password)
        success, token, ec, _ = _interpret(body)
        if success:
            return LoginResult(True, token)
        return LoginResult(False, None, ec, body.get("msg", ""))

    # captchaEnabled=true：验证码重试循环
    last_exit = ExitCode.VALIDATION
    last_msg = ""
    for attempt in range(max_retries):
        # 首次用已取的 captcha；重试时重新获取新 uuid+img（旧 uuid 已被后端消费）
        if attempt > 0:
            captcha = client.get("/captchaImage").json()
        code = _prompt_captcha(captcha, prompt_func, open_image_func)
        if not code:
            return LoginResult(False, None, ExitCode.VALIDATION, "未输入验证码")
        body = _post_login(
            client, username, password, code=code, uuid=captcha.get("uuid")
        )
        success, token, ec, retryable = _interpret(body)
        if success:
            return LoginResult(True, token)
        last_exit = ec
        last_msg = body.get("msg", "")
        if not retryable:
            break  # 非验证码错误（如服务异常）不重试
    return LoginResult(
        False, None, last_exit,
        last_msg or f"验证码重试 {max_retries} 次仍失败，可尝试 --captcha-mode skip（需后端 captchaEnabled=false）",
    )


def login(
    client: httpx.Client,
    username: str,
    password: str,
    *,
    captcha_mode: str = "image",
    captcha_code: Optional[str] = None,
    captcha_uuid: Optional[str] = None,
    max_retries: int = 3,
    prompt_func: Optional[Callable[[], str]] = None,
    open_image_func: Optional[Callable[[str], None]] = None,
) -> LoginResult:
    """执行登录流程。

    Args:
        client: httpx.Client（/login 与 /captchaImage 跳过鉴权头）。
        username/password: 凭据。
        captcha_mode: image / skip / code。
        captcha_code: code 模式的验证码（用户自备）。
        captcha_uuid: code 模式的 uuid（必填）。
        max_retries: image 模式验证码重试上限。
        prompt_func: image 模式输入验证码的可注入函数（测试用）。
        open_image_func: image 模式打开图片的可注入函数（测试用）。
    """
    if captcha_mode == "skip":
        return _login_skip(client, username, password)
    if captcha_mode == "code":
        return _login_code(client, username, password, captcha_code, captcha_uuid)
    return _login_image(
        client, username, password, max_retries, prompt_func, open_image_func
    )
