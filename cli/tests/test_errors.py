"""errors.py 错误码映射与 /login 特判测试。

覆盖 R5（语义化退出码）与 KTD3（/login 端点特判）。
"""

from __future__ import annotations

from rainote.errors import (
    ExitCode,
    RainoteError,
    classify_login_error,
    map_http_status_to_exit_code,
    map_response_to_exit_code,
)


class TestMapResponseToExitCode:
    """业务码 → 退出码映射（R5）。"""

    def test_code_200_ok(self) -> None:
        assert map_response_to_exit_code(code=200, msg="ok") == ExitCode.OK

    def test_code_401_auth(self) -> None:
        assert map_response_to_exit_code(code=401, msg="未授权") == ExitCode.AUTH

    def test_code_403_forbidden(self) -> None:
        assert map_response_to_exit_code(code=403, msg="禁止访问") == ExitCode.FORBIDDEN

    def test_code_404_not_found(self) -> None:
        assert map_response_to_exit_code(code=404, msg="未找到") == ExitCode.NOT_FOUND

    def test_code_400_validation(self) -> None:
        assert (
            map_response_to_exit_code(code=400, msg="参数错误") == ExitCode.VALIDATION
        )

    def test_code_500_server(self) -> None:
        assert map_response_to_exit_code(code=500, msg="服务异常") == ExitCode.SERVER

    def test_code_601_warn(self) -> None:
        assert map_response_to_exit_code(code=601, msg="revision 不匹配") == ExitCode.WARN

    def test_unknown_code_defaults_to_server(self) -> None:
        """未知业务码默认映射为 SERVER(5)。"""
        assert map_response_to_exit_code(code=999, msg="未知") == ExitCode.SERVER


class TestLoginSpecialCase:
    """/login 端点特判（KTD3）。

    后端 CaptchaException/CaptchaExpireException/UserPasswordNotMatchException 落入
    GlobalExceptionHandler.handleRuntimeException 返回 code 500，需特判为 VALIDATION(6)。
    """

    def test_login_captcha_chinese(self) -> None:
        """code 500 + msg 含'验证码' → 退出码 6（非 5）。"""
        assert (
            map_response_to_exit_code(code=500, msg="验证码错误", is_login=True)
            == ExitCode.VALIDATION
        )

    def test_login_captcha_english(self) -> None:
        """code 500 + msg 含'captcha' → 退出码 6。"""
        assert (
            map_response_to_exit_code(code=500, msg="Invalid captcha", is_login=True)
            == ExitCode.VALIDATION
        )

    def test_login_password(self) -> None:
        """code 500 + msg 含'password' → 退出码 6。"""
        assert (
            map_response_to_exit_code(code=500, msg="UserPasswordNotMatchException", is_login=True)
            == ExitCode.VALIDATION
        )

    def test_login_jcaptcha(self) -> None:
        """code 500 + msg 含'jcaptcha' → 退出码 6。"""
        assert (
            map_response_to_exit_code(code=500, msg="jcaptcha error", is_login=True)
            == ExitCode.VALIDATION
        )

    def test_login_non_validation_500(self) -> None:
        """code 500 + msg 不匹配关键词 → 退出码 5（保持 SERVER）。"""
        assert (
            map_response_to_exit_code(code=500, msg="服务异常", is_login=True)
            == ExitCode.SERVER
        )

    def test_login_400_still_validation(self) -> None:
        """code 400（非 500）→ 退出码 6（标准映射，无需特判）。"""
        assert (
            map_response_to_exit_code(code=400, msg="参数错误", is_login=True)
            == ExitCode.VALIDATION
        )

    def test_login_special_case_not_triggered_off_login(self) -> None:
        """非 /login 端点：code 500 + msg 含'验证码' → 退出码 5（不特判）。"""
        assert (
            map_response_to_exit_code(code=500, msg="验证码错误", is_login=False)
            == ExitCode.SERVER
        )

    def test_classify_login_error_directly(self) -> None:
        """直接测试 classify_login_error 函数。"""
        assert classify_login_error(500, "验证码错误") == ExitCode.VALIDATION
        assert classify_login_error(500, "服务异常") == ExitCode.SERVER
        assert classify_login_error(500, "") == ExitCode.SERVER
        assert classify_login_error(401, "验证码错误") == ExitCode.SERVER
        assert classify_login_error(500, "密码错误") == ExitCode.VALIDATION


class TestMapHttpStatusToExitCode:
    """HTTP 状态码回退（body 非 JSON 或 HTTP 非 200 时）。"""

    def test_http_200_ok(self) -> None:
        assert map_http_status_to_exit_code(200) == ExitCode.OK

    def test_http_401_auth(self) -> None:
        assert map_http_status_to_exit_code(401) == ExitCode.AUTH

    def test_http_403_forbidden(self) -> None:
        assert map_http_status_to_exit_code(403) == ExitCode.FORBIDDEN

    def test_http_404_not_found(self) -> None:
        assert map_http_status_to_exit_code(404) == ExitCode.NOT_FOUND

    def test_http_400_validation(self) -> None:
        assert map_http_status_to_exit_code(400) == ExitCode.VALIDATION

    def test_http_500_server(self) -> None:
        assert map_http_status_to_exit_code(500) == ExitCode.SERVER

    def test_http_502_server(self) -> None:
        """mock 后端返回 HTTP 502（非 JSON）→ 退出码 5。"""
        assert map_http_status_to_exit_code(502) == ExitCode.SERVER

    def test_http_503_server(self) -> None:
        assert map_http_status_to_exit_code(503) == ExitCode.SERVER

    def test_http_unknown_defaults_to_server(self) -> None:
        assert map_http_status_to_exit_code(999) == ExitCode.SERVER


class TestRainoteError:
    def test_default_exit_code(self) -> None:
        e = RainoteError("something wrong")
        assert e.exit_code == ExitCode.SERVER
        assert e.message == "something wrong"

    def test_custom_exit_code(self) -> None:
        e = RainoteError("未授权", exit_code=ExitCode.AUTH)
        assert e.exit_code == ExitCode.AUTH
        assert str(e) == "未授权"
