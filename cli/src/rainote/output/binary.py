"""二进制流输出 —— Excel 导出等二进制响应写入文件或 stdout。

支持两种输出模式（R9）：
  - ``-o <file>``：写入指定文件
  - ``--stdout``：直接写入 stdout（二进制安全，用 ``sys.stdout.buffer``）
"""

from __future__ import annotations

import sys
from pathlib import Path


def write_binary(
    data: bytes,
    output: Path | None,
    *,
    stdout: bool = False,
) -> None:
    """将二进制数据写入文件或 stdout。

    :param data: 二进制数据
    :param output: 输出文件路径；None 表示不写文件
    :param stdout: 是否写入 stdout
    :raises ValueError: 未指定 ``output`` 且 ``stdout=False`` 时

    - ``stdout=True`` → 用 ``sys.stdout.buffer.write`` 二进制安全写入
    - ``output`` 指定 → 用 ``Path.write_bytes`` 写入（覆盖已存在文件）
    - 二者均未指定 → 抛出 :class:`ValueError`
    """
    if stdout:
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()
        return
    if output is not None:
        output.write_bytes(data)
        return
    raise ValueError("必须指定 -o <file> 或 --stdout 之一")
