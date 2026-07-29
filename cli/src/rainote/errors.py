"""错误处理 —— 退出码常量与异常。

U2 阶段先建立退出码常量与 /login 端点特判（KTD3），U3 阶段补全完整的
业务码 → 退出码映射（map_response_to_exit_code）。

语义化退出码（R5）：
  0  OK           成功
  2  AUTH         认证失败（401）
  3  FORBIDDEN    禁止访问（403）
  4  NOT_FOUND    未找到（404）
  5  SERVER       服务错误（500）
  6  VALIDATION   校验错误（400 / login 验证码密码错误）
  7  WARN         警告（601 / revision 不匹配）
  10 NETWORK      网络错误
"""

from __future__ import annotations

import re

import typer
# typer 0.27 vendored click 为 ``typer._click``（pip 不再单独安装 click）。
# 锁定 typer>=0.27,<0.28，故 ``typer._click.ClickException`` 可用。
from typer._click import ClickException


class ExitCode:
    """语义化退出码常量。"""

    OK = 0
    AUTH = 2
    FORBIDDEN = 3
    NOT_FOUND = 4
    SERVER = 5
    VALIDATION = 6
    WARN = 7
    NETWORK = 10


class RainoteError(ClickException):
    """CLI 业务异常，携带退出码与消息。

    继承 :class:`typer._click.ClickException` 使 Typer/Click 在命令执行时自动捕获并按
    ``exit_code`` 退出，无需在每个命令函数中 try-except。CliRunner 也会正确读取
    ``exit_code``（``standalone_mode=False`` 下 ClickException 由 CliRunner 处理）。

    :ivar message: 错误消息
    :ivar exit_code: 语义化退出码（见 :class:`ExitCode`）
    """

    # 类默认退出码，实例 __init__ 可覆盖
    exit_code: int = ExitCode.SERVER

    def __init__(self, message: str, exit_code: int = ExitCode.SERVER) -> None:
        super().__init__(message)
        self.message = message
        self.exit_code = exit_code

    def show(self) -> None:  # type: ignore[override]
        """输出错误信息到 stderr（重写 ClickException.show）。

        Click/CliRunner 在捕获 ClickException 时调用 ``show()``，本方法将错误消息
        输出到 stderr，与 ``_common.emit_error`` 的格式保持一致（``"错误: ..."``）。
        """
        typer.echo(f"错误: {self.message}", err=True)


# /login 端点特判关键词（KTD3）
# 后端 CaptchaException/CaptchaExpireException/UserPasswordNotMatchException 落入
# GlobalExceptionHandler.handleRuntimeException 返回 code 500，需特判为 ValidationError(6)
_LOGIN_VALIDATION_KEYWORDS = re.compile(
    r"jcaptcha|password|captcha|验证码|密码", re.IGNORECASE
)


def classify_login_error(code: int, msg: str) -> int:
    """/login 端点特判：code 500 + msg 含验证码/密码关键词 → 退出码 6，否则 5。

    后端登录失败（验证码错误、密码错误）落入 RuntimeException handler 返回 code 500，
    若按标准映射会误报为服务错误(5)。特判为校验错误(6)。
    """
    if code == 500 and msg and _LOGIN_VALIDATION_KEYWORDS.search(msg):
        return ExitCode.VALIDATION
    return ExitCode.SERVER


# 业务码 → 退出码标准映射（R5）
# 后端 ruoyi-common HttpStatus 约定：200/401/403/404/400/500；601 为自定义警告码
_BIZ_TO_EXIT: dict[int, int] = {
    200: ExitCode.OK,
    401: ExitCode.AUTH,
    403: ExitCode.FORBIDDEN,
    404: ExitCode.NOT_FOUND,
    400: ExitCode.VALIDATION,
    500: ExitCode.SERVER,
    601: ExitCode.WARN,
}


def map_response_to_exit_code(code: int, msg: str, *, is_login: bool = False) -> int:
    """业务码 → 退出码映射（R5），含 /login 端点特判（KTD3）。

    后端统一返回 HTTP 200，成败由 ``AjaxResult.code`` 区分（KTD2），因此优先解析
    业务码而非 HTTP 状态码。

    :param code: 后端 ``AjaxResult.code`` 业务码
    :param msg: 后端 ``AjaxResult.msg`` 消息（用于 /login 特判关键词匹配）
    :param is_login: 是否为 /login 端点响应（触发 KTD3 特判）
    :return: :class:`ExitCode` 常量

    映射规则：
      - 200 → OK(0)
      - 401 → AUTH(2)、403 → FORBIDDEN(3)、404 → NOT_FOUND(4)
      - 400 → VALIDATION(6)、601 → WARN(7)
      - 500 → SERVER(5)，**但** /login 端点 + msg 含验证码/密码关键词 → VALIDATION(6)
      - 未知码 → SERVER(5)
    """
    # /login 特判：code 500 + 验证码/密码关键词 → VALIDATION(6)
    if is_login and code == 500 and msg and _LOGIN_VALIDATION_KEYWORDS.search(msg):
        return ExitCode.VALIDATION
    return _BIZ_TO_EXIT.get(code, ExitCode.SERVER)


# HTTP 状态码 → 退出码回退映射
# 仅当 body 非 JSON 或 HTTP 非 200 时使用（无法解析业务码）
_HTTP_TO_EXIT: dict[int, int] = {
    200: ExitCode.OK,
    401: ExitCode.AUTH,
    403: ExitCode.FORBIDDEN,
    404: ExitCode.NOT_FOUND,
    400: ExitCode.VALIDATION,
}


def map_http_status_to_exit_code(status_code: int) -> int:
    """HTTP 状态码 → 退出码回退映射。

    仅当响应体非 JSON（无法解析 ``AjaxResult.code``）或 HTTP 非 200 时使用。
    正常路径应优先使用 :func:`map_response_to_exit_code` 解析业务码。

    :param status_code: HTTP 状态码
    :return: :class:`ExitCode` 常量

    映射规则：
      - 200 → OK(0)、401 → AUTH(2)、403 → FORBIDDEN(3)、404 → NOT_FOUND(4)
      - 400 → VALIDATION(6)
      - 5xx → SERVER(5)
      - 其他未知码 → SERVER(5)
    """
    if status_code in _HTTP_TO_EXIT:
        return _HTTP_TO_EXIT[status_code]
    if 500 <= status_code < 600:
        return ExitCode.SERVER
    return ExitCode.SERVER
