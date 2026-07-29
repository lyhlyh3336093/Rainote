"""统一渲染入口 —— 按 --output 分发到 table/csv/json。

非 TTY 自动禁色（由各渲染器内部处理）。
"""

from __future__ import annotations

from ..models.common import ApiResponse
from .csv_writer import render_csv
from .json_out import render_json
from .table import render_table


def render(
    api: ApiResponse,
    output: str | None,
    *,
    page: int | None = None,
    size: int | None = None,
    fields: list[str] | None = None,
    no_color: bool = False,
    json_indent: int | None = None,
) -> str:
    """按 ``--output`` 分发到对应渲染器。

    :param api: 归一化后的响应
    :param output: 输出格式（``"json"`` / ``"csv"`` / ``"table"``）；None 默认 ``"table"``
    :param page: 当前页码（table 模式分页信息）
    :param size: 每页大小（table 模式分页信息）
    :param fields: 指定列顺序（csv/table 模式）
    :param no_color: 强制禁色（table 模式）
    :param json_indent: JSON 缩进空格数（json 模式）
    :return: 渲染后的字符串

    分发规则：
      - ``"json"`` → :func:`render_json`（纯 JSON，无 ANSI）
      - ``"csv"`` → :func:`render_csv`（CSV，无 ANSI）
      - ``"table"`` / None → :func:`render_table`（Rich 表格，非 TTY 自动禁色）
    """
    fmt = output or "table"

    if fmt == "json":
        return render_json(api, indent=json_indent)
    if fmt == "csv":
        return render_csv(api, fields=fields)
    if fmt == "table":
        return render_table(
            api, page=page, size=size, fields=fields, no_color=no_color
        )
    # 不应到达（Config.__post_init__ 已校验 output 值）
    raise ValueError(f"无效的 output 值: {fmt!r}")
