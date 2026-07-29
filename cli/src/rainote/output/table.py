"""Rich 表格输出 —— 分页信息 + 表头 + 数据行 / 无数据行。

非 TTY 自动禁色（Rich Console 默认行为，配合 force_terminal=False 确保无 ANSI）。
"""

from __future__ import annotations

import io
from typing import Any

from rich.console import Console
from rich.table import Table
from rich.text import Text

from ..models.common import ApiResponse
from .csv_writer import _extract_rows, flatten_obj


def render_table(
    api: ApiResponse,
    *,
    page: int | None = None,
    size: int | None = None,
    fields: list[str] | None = None,
    no_color: bool = False,
) -> str:
    """渲染 Rich 表格字符串。

    :param api: 归一化后的响应
    :param page: 当前页码（用于分页信息显示）
    :param size: 每页大小（用于计算总页数）
    :param fields: 指定列顺序；None 时从首行推断
    :param no_color: 强制禁色（非 TTY 时自动启用）
    :return: 表格字符串（非 TTY 时无 ANSI 转义）

    - 分页响应顶部显示"共 N 条，第 x/y 页"
    - 空 rows 显示表头 + 灰色"无数据"行
    - 单对象 data 显示为单行表格
    """
    buf = io.StringIO()
    # force_terminal=False 确保非 TTY 时不输出 ANSI 颜色码
    console = Console(file=buf, force_terminal=False, no_color=no_color)

    # ---- 分页信息 ----
    if api.is_page and api.total is not None:
        if page is not None and size is not None and size > 0:
            total_pages = max(1, (api.total + size - 1) // size)
            console.print(f"共 {api.total} 条，第 {page}/{total_pages} 页")
        else:
            console.print(f"共 {api.total} 条")

    # ---- 行数据 ----
    rows = _extract_rows(api)

    # ---- 字段推断 ----
    if fields is None:
        if rows and isinstance(rows[0], dict):
            fields = list(flatten_obj(rows[0]).keys())
        else:
            fields = []

    # ---- 表格构建 ----
    table = Table(show_header=True, header_style="bold" if not no_color else "")
    for f in fields:
        table.add_column(str(f))

    if not rows:
        # 空 rows：显示表头 + 灰色"无数据"行
        if fields:
            cells = [Text("无数据", style="dim")] + [Text("") for _ in fields[1:]]
            table.add_row(*cells)
        else:
            # 无字段时直接打印"无数据"
            console.print(Text("无数据", style="dim"))
            return buf.getvalue()
    else:
        for row in rows:
            if isinstance(row, dict):
                flat = flatten_obj(row)
                cells = [str(flat.get(f, "")) for f in fields]
            else:
                cells = [str(row)] + ["" for _ in fields[1:]]
            table.add_row(*cells)

    console.print(table)
    return buf.getvalue()
