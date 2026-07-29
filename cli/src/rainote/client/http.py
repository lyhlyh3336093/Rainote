"""HTTP 客户端封装 —— httpx.Client 同步、重试、鉴权头注入、--verbose 脱敏。

设计要点（KTD2/R5/R8）：

- 后端统一返回 HTTP 200，成败由 ``AjaxResult.code`` 区分 → 重试判断基于业务码/退出码
- 业务码 500（退出码 SERVER=5）与连接错误指数退避重试；4xx / VALIDATION 不重试
- 自动注入 ``Authorization: Bearer <token>`` 与 ``X-Requested-With: rainote-cli/<version>``
- ``/login``、``/captchaImage`` 跳过鉴权头（登录前无 token）
- ``--verbose`` 模式下 Authorization 头值脱敏（保留前 8 字符 + ``***``），打印到 stderr
"""

from __future__ import annotations

import sys
import time
from typing import Any, Callable

import httpx

from .. import __version__
from ..errors import ExitCode, RainoteError
from ..models.common import ApiResponse
from .response import parse_response

# 跳过鉴权头的端点（登录前无 token）
_NO_AUTH_PATHS = frozenset({"/login", "/captchaImage"})

# 默认超时与连接限制（计划 Approach 1）
_DEFAULT_TIMEOUT = httpx.Timeout(connect=5.0, read=30.0, write=5.0, pool=2.0)
_DEFAULT_LIMITS = httpx.Limits(max_connections=10, max_keepalive_connections=5)

# 默认重试参数
_DEFAULT_RETRY_MAX = 3
_DEFAULT_RETRY_DELAY = 0.5  # 指数退避基础延迟（秒）


def mask_authorization(value: str) -> str:
    """脱敏 Authorization 头值（R8）。

    保留前 8 字符 + ``***``；不足 8 字符时返回 ``***``。

    >>> mask_authorization("Bearer eyJhbGciOiJIUzI1")
    'Bearer e***'
    """
    if len(value) < 8:
        return "***"
    return value[:8] + "***"


class RainoteClient:
    """HTTP 客户端封装。

    :param base_url: 后端 API 基址（如 ``http://localhost:8080``）
    :param token: JWT token（None 表示匿名，需配合 ``allow_anonymous=True``）
    :param verbose: 是否输出详细诊断信息（Authorization 头自动脱敏）
    :param retry_max: 最大重试次数（业务码 500 / 连接错误时触发）
    :param retry_delay: 指数退避基础延迟（秒）；测试时可设为 0
    :param allow_anonymous: 是否允许匿名调用（调试用，见 R14 --allow-anonymous）
    :param timeout: httpx 超时配置（默认见 :data:`_DEFAULT_TIMEOUT`）
    :param limits: httpx 连接限制（默认见 :data:`_DEFAULT_LIMITS`）
    :param sleep_func: 可注入的 sleep 函数（测试用）
    """

    def __init__(
        self,
        base_url: str,
        token: str | None = None,
        *,
        verbose: bool = False,
        retry_max: int = _DEFAULT_RETRY_MAX,
        retry_delay: float = _DEFAULT_RETRY_DELAY,
        allow_anonymous: bool = False,
        timeout: httpx.Timeout | None = None,
        limits: httpx.Limits | None = None,
        sleep_func: Callable[[float], None] | None = None,
    ) -> None:
        self._token = token
        self._verbose = verbose
        self._retry_max = retry_max
        self._retry_delay = retry_delay
        self._allow_anonymous = allow_anonymous
        self._sleep = sleep_func or time.sleep

        self._client = httpx.Client(
            base_url=base_url,
            timeout=timeout or _DEFAULT_TIMEOUT,
            limits=limits or _DEFAULT_LIMITS,
        )
        # 注册 request hook：鉴权头注入 + verbose 日志
        self._client.event_hooks["request"].append(self._request_hook)

    # ---- 上下文管理 ----

    def __enter__(self) -> "RainoteClient":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def close(self) -> None:
        """关闭底层 httpx 连接池。"""
        self._client.close()

    # ---- 请求 hook ----

    def _request_hook(self, request: httpx.Request) -> None:
        """请求发送前 hook：注入鉴权头 + verbose 日志（脱敏）。

        - ``/login``、``/captchaImage`` 跳过鉴权头
        - 其余端点注入 ``Authorization: Bearer <token>``（若有 token）
        - 所有请求注入 ``X-Requested-With: rainote-cli/<version>``
        - verbose 模式打印请求信息到 stderr，Authorization 脱敏
        """
        path = request.url.path
        skip_auth = path in _NO_AUTH_PATHS

        if not skip_auth and self._token:
            request.headers["Authorization"] = f"Bearer {self._token}"
        request.headers["X-Requested-With"] = f"rainote-cli/{__version__}"

        if self._verbose:
            self._log_request(request)

    def _log_request(self, request: httpx.Request) -> None:
        """打印请求日志到 stderr，Authorization 头脱敏（R8）。"""
        print(f"-> {request.method} {request.url}", file=sys.stderr)
        for key, value in request.headers.items():
            if key.lower() == "authorization":
                value = mask_authorization(value)
            print(f"   {key}: {value}", file=sys.stderr)

    # ---- 请求方法 ----

    def request(
        self,
        method: str,
        path: str,
        *,
        is_login: bool = False,
        **kwargs: Any,
    ) -> tuple[ApiResponse, int]:
        """发送 HTTP 请求并解析响应，含重试。

        :param method: HTTP 方法（GET/POST/...）
        :param path: 请求路径（相对 base_url）
        :param is_login: 是否为 /login 端点（触发 KTD3 特判）
        :param kwargs: 透传给 ``httpx.Client.request``（json/params/data/headers 等）
        :return: ``(ApiResponse, exit_code)`` 元组

        重试策略：
          - 退出码 SERVER(5)（业务码 500）→ 指数退避重试
          - 连接错误（ConnectError/TimeoutException/NetworkError）→ 指数退避重试
          - 其他退出码（含 VALIDATION、AUTH、FORBIDDEN 等）→ 不重试
          - 重试耗尽后返回最后一次响应 / 抛出 :class:`RainoteError`
        """
        last_exc: Exception | None = None
        last_api: ApiResponse | None = None
        last_exit_code: int = ExitCode.SERVER

        for attempt in range(self._retry_max + 1):
            try:
                resp = self._client.request(method, path, **kwargs)
            except (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError) as exc:
                # 连接错误 → 重试
                last_exc = exc
                if attempt < self._retry_max:
                    self._sleep(self._backoff_delay(attempt))
                    continue
                raise RainoteError(
                    f"网络错误: {exc}", ExitCode.NETWORK
                ) from exc

            api, exit_code = parse_response(resp, is_login=is_login)
            last_api = api
            last_exit_code = exit_code

            # 业务码 500（退出码 SERVER=5）→ 重试
            if exit_code == ExitCode.SERVER and attempt < self._retry_max:
                self._sleep(self._backoff_delay(attempt))
                continue

            return api, exit_code

        # 重试耗尽：返回最后一次结果
        if last_api is not None:
            return last_api, last_exit_code
        # 不应到达：last_exc 必定存在
        raise RainoteError(
            f"网络错误: {last_exc}", ExitCode.NETWORK
        )

    def _backoff_delay(self, attempt: int) -> float:
        """指数退避延迟：retry_delay * (2 ** attempt)。"""
        return self._retry_delay * (2 ** attempt)

    def get(self, path: str, **kwargs: Any) -> tuple[ApiResponse, int]:
        """GET 请求。"""
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs: Any) -> tuple[ApiResponse, int]:
        """POST 请求。"""
        return self.request("POST", path, **kwargs)

    def put(self, path: str, **kwargs: Any) -> tuple[ApiResponse, int]:
        """PUT 请求。"""
        return self.request("PUT", path, **kwargs)

    def delete(self, path: str, **kwargs: Any) -> tuple[ApiResponse, int]:
        """DELETE 请求。"""
        return self.request("DELETE", path, **kwargs)

    # ---- 二进制下载 ----

    def download_binary(
        self,
        method: str,
        path: str,
        **kwargs: Any,
    ) -> tuple[bytes, int]:
        """下载二进制流（如 Excel 导出）。

        与 :meth:`request` 不同，本方法返回原始字节而非解析后的 ApiResponse，
        因为导出接口返回的是二进制文件流而非 JSON。

        :return: ``(content_bytes, exit_code)`` 元组
        """
        last_exc: Exception | None = None
        for attempt in range(self._retry_max + 1):
            try:
                resp = self._client.request(method, path, **kwargs)
            except (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError) as exc:
                last_exc = exc
                if attempt < self._retry_max:
                    self._sleep(self._backoff_delay(attempt))
                    continue
                raise RainoteError(
                    f"网络错误: {exc}", ExitCode.NETWORK
                ) from exc

            # 非 2xx → 错误
            if not (200 <= resp.status_code < 300):
                from ..errors import map_http_status_to_exit_code

                exit_code = map_http_status_to_exit_code(resp.status_code)
                return b"", exit_code

            return resp.content, ExitCode.OK

        raise RainoteError(
            f"网络错误: {last_exc}", ExitCode.NETWORK
        )
