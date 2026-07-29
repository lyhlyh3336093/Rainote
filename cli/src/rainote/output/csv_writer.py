"""CSV 流式输出 —— 标准库 csv 模块，嵌套对象扁平化。

绕过 Rich，确保无 ANSI 污染（R7）。嵌套对象扁平化为 ``a.b.c`` 列名。
"""

from __future__ import annotations

import csv
import io
from typing import Any

from ..models.common import ApiResponse


def flatten_obj(obj: dict[str, Any], prefix: str = "") -> dict[str, Any]:
    """扁平化嵌套 dict。

    :param obj: 待扁平化的字典
    :param prefix: 递归用的键前缀
    :return: 扁平化后的字典，键为点分隔路径（如 ``a.b.c``）

    示例::

        >>> flatten_obj({"a": {"b": 1}})
        {'a.b': 1}
        >>> flatten_obj({"a": {"b": {"c": 2}}})
        {'a.b.c': 2}

    非 dict 值（含 list）原样保留，不展开索引。
    """
    out: dict[str, Any] = {}
    for key, value in obj.items():
        full_key = f"{prefix}.{key}" if prefix else key
        if isinstance(value, dict):
            out.update(flatten_obj(value, full_key))
        else:
            out[full_key] = value
    return out


def _extract_rows(api: ApiResponse) -> list[Any]:
    """从 ApiResponse 提取行数据列表。

    - 分页响应 → ``api.rows``
    - 单对象 data（dict）→ ``[data]``
    - 单对象 data（list）→ ``data``
    - 其他 → 空列表
    """
    if api.is_page:
        return list(api.rows or [])
    if api.has_data and isinstance(api.data, dict):
        return [api.data]
    if api.has_data and isinstance(api.data, list):
        return list(api.data)
    return []


def render_csv(
    api: ApiResponse,
    *,
    fields: list[str] | None = None,
) -> str:
    """渲染 CSV 字符串。

    :param api: 归一化后的响应
    :param fields: 指定列顺序；None 时从首行推断；空结果 + 无 fields → 输出空
    :return: CSV 字符串（不含 ANSI 转义）

    - 嵌套对象扁平化（``a.b`` 列名）
    - ``fields`` 指定时，即使无数据也输出表头行
    - 无 ``fields`` 且无数据 → 输出空字符串
    - 换行符统一为 ``\\n``（避免 ``\\r\\n`` 跨平台问题）
    """
    rows = _extract_rows(api)
    flat_rows: list[dict[str, Any]] = []
    for row in rows:
        if isinstance(row, dict):
            flat_rows.append(flatten_obj(row))
        else:
            flat_rows.append({})

    # 确定字段
    if fields is None:
        if not flat_rows:
            return ""
        # 从首行推断字段（保持插入顺序）
        fields = list(flat_rows[0].keys())

    output = io.StringIO()
    writer = csv.DictWriter(
        output,
        fieldnames=fields,
        extrasaction="ignore",
        lineterminator="\n",
    )
    writer.writeheader()
    for i, row in enumerate(flat_rows):
        if row:
            # dict 行：直接写
            writer.writerow(row)
        elif fields:
            # 非 dict 行（如纯字符串）：用首列承载原始值
            writer.writerow({fields[0]: rows[i]})
        else:
            writer.writerow({})

    return output.getvalue()
