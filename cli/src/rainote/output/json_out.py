"""纯 JSON 输出 —— 直接 json.dumps，不经过 Rich。

确保 ``--json`` 输出无 ANSI 污染，可安全管道至 ``jq`` 等工具（R6）。
"""

from __future__ import annotations

import json

from ..models.common import ApiResponse


def render_json(
    api: ApiResponse,
    *,
    indent: int | None = None,
) -> str:
    """渲染纯 JSON 字符串。

    :param api: 归一化后的响应
    :param indent: 缩进空格数（None 为紧凑输出）
    :return: JSON 字符串（不含 ANSI 转义）

    直接输出 ``api.raw``（后端原始响应体），保留 ``rows``/``total``/``data`` 等全部字段。
    中文不转义（``ensure_ascii=False``）。
    """
    return json.dumps(api.raw, ensure_ascii=False, indent=indent)
