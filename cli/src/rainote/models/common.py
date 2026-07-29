"""后端响应归一化数据模型。

后端有两种响应结构（KTD2）：

1. ``AjaxResult``（单对象/操作结果）::

       {"code": 200, "msg": "操作成功", "data": {...}}   # data == null 时省略 data 键

2. ``TableDataInfo``（分页列表）::

       {"code": 200, "msg": "查询成功", "rows": [...], "total": 42}

本模块定义统一的 :class:`ApiResponse`，由 :mod:`rainote.client.response` 归一化填充。
设计要点：

- ``ok`` 属性：业务码 == 200 表示成功（后端统一返回 HTTP 200，成败由 ``code`` 区分，KTD2）
- ``has_data`` 属性：用 ``'data' in raw`` 检测，而非 ``data is not None``。
  原因：后端 ``AjaxResult`` 在 ``data == null`` 时**不 put data 键**（见
  ``ruoyi-common`` 的 ``AjaxResult`` 实现），因此 ``data`` 字段存在与否才是
  "后端是否返回了 data" 的可靠信号；``data is None`` 无法区分"未返回"与"返回 null"。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


# 业务码常量（后端 ruoyi-common HttpStatus / AjaxResult 约定）
BIZ_OK = 200  # 成功
BIZ_UNAUTHORIZED = 401
BIZ_FORBIDDEN = 403
BIZ_NOT_FOUND = 404
BIZ_BAD_REQUEST = 400
BIZ_SERVER_ERROR = 500
BIZ_WARN = 601  # 自定义警告码（如 revision 不匹配）


@dataclass
class TablePage:
    """分页数据切片（TableDataInfo 归一化后提取）。

    :ivar rows: 当页记录列表
    :ivar total: 符合查询条件的总记录数（跨所有页）
    """

    rows: list[Any]
    total: int


@dataclass
class ErrorPayload:
    """错误负载 —— 业务码映射到退出码后的结构化错误信息。

    供输出层渲染错误详情，并携带退出码供 CLI 退出。

    :ivar code: 后端业务码（如 401/500）
    :ivar msg: 后端返回的错误消息
    :ivar exit_code: CLI 语义化退出码（见 :class:`rainote.errors.ExitCode`）
    :ivar raw: 原始响应体（用于 --json 输出原始错误）
    """

    code: int
    msg: str
    exit_code: int
    raw: dict[str, Any] | None = None


@dataclass
class ApiResponse:
    """后端响应归一化结果。

    统一表示 ``AjaxResult`` 与 ``TableDataInfo`` 两种结构。
    由 :func:`rainote.client.response.parse_response` 填充。

    :ivar code: 业务码（200 成功）
    :ivar msg: 消息文本
    :ivar data: AjaxResult.data 字段值（可能为 None；是否存在看 :attr:`has_data`）
    :ivar raw: 原始响应体（用于 --json 输出、has_data 检测）
    :ivar is_page: 是否为 TableDataInfo 分页响应
    :ivar rows: 分页记录列表（is_page=True 时有效）
    :ivar total: 总记录数（is_page=True 时有效）
    """

    code: int
    msg: str
    data: Any | None = None
    raw: dict[str, Any] = field(default_factory=dict)
    is_page: bool = False
    rows: list[Any] | None = None
    total: int | None = None

    @property
    def ok(self) -> bool:
        """业务码 200 表示成功。

        后端统一返回 HTTP 200，成败由 ``AjaxResult.code`` 区分（KTD2），
        因此不能用 HTTP 状态码判断成功。
        """
        return self.code == BIZ_OK

    @property
    def has_data(self) -> bool:
        """后端是否返回了 data 字段。

        用 ``'data' in raw`` 而非 ``self.data is not None``：
        后端 ``AjaxResult`` 在 ``data == null`` 时**不 put data 键**，
        故 ``data`` 键存在与否才是"后端是否返回 data"的可靠信号。
        """
        return "data" in self.raw

    def as_error(self, exit_code: int) -> ErrorPayload:
        """将非成功响应转为 :class:`ErrorPayload`（供错误渲染）。"""
        return ErrorPayload(
            code=self.code, msg=self.msg, exit_code=exit_code, raw=self.raw
        )
