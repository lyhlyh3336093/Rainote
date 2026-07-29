"""note 导出命令 —— export。

端点映射（NoteNoteController.java）：
  - export: POST /system/note/export (L161, 二进制流, body=筛选条件)

输出模式（R9）：
  - ``-o <file>``：写入文件
  - ``--stdout``：写入 stdout（二进制安全）
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

import typer

from ...client.http import RainoteClient
from ...errors import ExitCode
from .._common import emit_error, get_client, read_payload_file
from ...output.binary import write_binary


# ---- 核心逻辑函数 ----


def export_notes(
    client: RainoteClient,
    *,
    payload: dict[str, Any] | None = None,
) -> tuple[bytes, int]:
    """POST /system/note/export —— 导出笔记为 Excel 二进制流。

    :param payload: 筛选条件 JSON（可为空）
    :return: ``(content_bytes, exit_code)``
    """
    return client.download_binary(
        "POST", "/system/note/export", json=payload or {}
    )


# ---- Typer 命令函数 ----


def export_cmd(
    ctx: typer.Context,
    output_file: Optional[Path] = typer.Option(
        None, "--output", "-o", help="输出文件路径（如 notes.xlsx）。"
    ),
    stdout: bool = typer.Option(False, "--stdout", help="输出到 stdout（二进制安全）。"),
    file: Optional[str] = typer.Option(
        None, "--file", "-f", help="筛选条件 JSON 文件路径。"
    ),
) -> None:
    """导出笔记为 Excel（二进制流）。

    必须指定 ``-o <file>`` 或 ``--stdout`` 之一。
    """
    if output_file is None and not stdout:
        emit_error("必须指定 -o <file> 或 --stdout 之一", ExitCode.VALIDATION)

    payload = read_payload_file(file) if file else {}

    with get_client(ctx) as client:
        data, exit_code = export_notes(client, payload=payload)

    if exit_code != ExitCode.OK:
        emit_error(f"导出失败（退出码 {exit_code}）", exit_code)

    try:
        write_binary(data, output_file, stdout=stdout)
    except ValueError as exc:
        emit_error(str(exc), ExitCode.VALIDATION)

    if output_file is not None:
        typer.echo(f"已导出: {output_file}（{len(data)} 字节）", err=True)
