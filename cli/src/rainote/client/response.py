"""后端响应归一化 —— AjaxResult / TableDataInfo → ApiResponse。

后端两种响应结构（KTD2）：

1. ``AjaxResult``（单对象/操作结果）::

       {"code": 200, "msg": "操作成功", "data": {...}}   # data==null 时省略 data 键

2. ``TableDataInfo``（分页列表）::

       {"code": 200, "msg": "查询成功", "rows": [...], "total": 42}

二者均通过 HTTP 200 返回，成败由 ``code`` 区分。本模块将其归一化为
:class:`rainote.models.common.ApiResponse`，并计算语义化退出码。

区分依据：``rows`` + ``total`` 同时存在 → TableDataInfo（分页）。
"""

from __future__ import annotations

from typing import Any

import httpx

from ..errors import ExitCode, map_http_status_to_exit_code, map_response_to_exit_code
from ..models.common import ApiResponse


def parse_body(
    body: dict[str, Any] | None,
    *,
    is_login: bool = False,
) -> tuple[ApiResponse, int]:
    """解析已 JSON 反序列化的响应体，归一化为 :class:`ApiResponse`。

    :param body: 已解析的响应体 dict（None 视为无法解析）
    :param is_login: 是否为 /login 端点（触发 KTD3 特判）
    :return: ``(api_response, exit_code)`` 元组

    归一化规则：
      - ``rows`` + ``total`` 同时存在 → TableDataInfo（分页），``code`` 缺省 200
      - 否则 → AjaxResult，``data`` 通过 :attr:`ApiResponse.has_data` 检测存在性
      - 退出码由 :func:`map_response_to_exit_code` 计算
    """
    if not isinstance(body, dict):
        # 非 dict body（如纯数组）→ 视为无法解析业务码
        return ApiResponse(code=200, msg="", raw={}), ExitCode.SERVER

    is_page = "rows" in body and "total" in body

    if is_page:
        api = ApiResponse(
            code=body.get("code", 200),
            msg=body.get("msg", ""),
            raw=body,
            is_page=True,
            rows=body.get("rows", []),
            total=body.get("total", 0),
        )
    else:
        api = ApiResponse(
            code=body.get("code", 200),
            msg=body.get("msg", ""),
            data=body.get("data"),
            raw=body,
        )

    exit_code = map_response_to_exit_code(api.code, api.msg, is_login=is_login)
    return api, exit_code


def parse_response(
    resp: httpx.Response,
    *,
    is_login: bool = False,
) -> tuple[ApiResponse, int]:
    """解析 :class:`httpx.Response`，归一化为 :class:`ApiResponse`。

    :param resp: httpx 响应对象
    :param is_login: 是否为 /login 端点（触发 KTD3 特判）
    :return: ``(api_response, exit_code)`` 元组

    解析顺序：
      1. 尝试 ``resp.json()`` 解析 JSON body
      2. JSON 解析成功 → 调用 :func:`parse_body`
      3. JSON 解析失败（body 非 JSON）→ 回退 HTTP 状态码（:func:`map_http_status_to_exit_code`）
      4. 空 body → 回退 HTTP 状态码
    """
    # 空 body → 回退 HTTP 状态码
    if not resp.content:
        return (
            ApiResponse(
                code=resp.status_code,
                msg="",
                raw={},
            ),
            map_http_status_to_exit_code(resp.status_code),
        )

    # 尝试解析 JSON
    try:
        body = resp.json()
    except (ValueError, TypeError):
        # body 非 JSON → 回退 HTTP 状态码
        return (
            ApiResponse(
                code=resp.status_code,
                msg=_safe_text(resp),
                raw={},
            ),
            map_http_status_to_exit_code(resp.status_code),
        )

    return parse_body(body, is_login=is_login)


def _safe_text(resp: httpx.Response) -> str:
    """安全提取响应文本（截断至 200 字符，避免超长错误页污染输出）。"""
    try:
        text = resp.text
    except (ValueError, UnicodeDecodeError):
        return ""
    return text[:200] if text else ""
