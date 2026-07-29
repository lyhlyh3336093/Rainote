"""notelink 命令组 —— 语义关联 CRUD / 导出 / 单元格查询 / 按笔记查询。

端点映射（NoteNotelinkController.java）：
  - list:     GET    /system/notelink/list                          (L41, 分页)
  - get:      GET    /system/notelink/{id}                          (L66)
  - cell:     GET    /system/notelink/cell/{linkColumnId}/{linkItemId}  (L77, 按单元格)
  - by-note:  GET    /system/notelink/byNote/{noteId}               (L89, 按笔记)
  - create:   POST   /system/notelink                               (L101, 根路径无 /add)
  - update:   PUT    /system/notelink                               (L117, PUT 根路径)
  - delete:   DELETE /system/notelink/{ids}                         (L128, DELETE 非 GET)
  - export:   POST   /system/notelink/export                        (L54, 二进制流)

代码事实（CLI 以代码为准）：
  - create 用 POST 根路径（无 /add），update 用 PUT 根路径（非 POST /update）
  - delete 用 DELETE 方法（其他 Controller 都是 GET /remove/{ids}）
  - @PathVariable Long[] ids：Spring 自动按逗号转 Long 数组

CONCEPTS.md 词汇：NoteNotelink 的 ``linkNoteId`` 可能 null（历史数据），
后端 byNote 查询用 COALESCE 兜底。CLI 输出时需注意此字段可能缺失。
"""

from __future__ import annotations

from typing import Any, Optional

import typer

from ._common import (
    confirm_destructive,
    emit,
    emit_error,
    get_client,
    read_payload_file,
)
from ..client.http import RainoteClient
from ..client.pagination import PaginationParams
from ..errors import ExitCode
from ..models.common import ApiResponse
from ..output.binary import write_binary
from pathlib import Path


# ---- 核心逻辑函数 ----


def list_notelinks(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
    filters: dict[str, Any] | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/notelink/list —— 分页查询语义关联。"""
    params = pagination.to_query(extra=filters)
    return client.get("/system/notelink/list", params=params)


def get_notelink(
    client: RainoteClient,
    notelink_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/notelink/{id} —— 获取单条语义关联。"""
    return client.get(f"/system/notelink/{notelink_id}")


def cell_notelinks(
    client: RainoteClient,
    *,
    link_column_id: str,
    link_item_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/notelink/cell/{linkColumnId}/{linkItemId} —— 按单元格查询。

    返回指定关联列 + 指定记录的所有语义关联。
    """
    return client.get(
        f"/system/notelink/cell/{link_column_id}/{link_item_id}"
    )


def by_note_notelinks(
    client: RainoteClient,
    *,
    note_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/notelink/byNote/{noteId} —— 按笔记查询。

    返回指定笔记的所有语义关联。注意 ``linkNoteId`` 可能 null（历史数据）。
    """
    return client.get(f"/system/notelink/byNote/{note_id}")


def create_notelink(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/notelink —— 新建语义关联（根路径，无 /add）。"""
    return client.post("/system/notelink", json=payload)


def update_notelink(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """PUT /system/notelink —— 更新语义关联（PUT 根路径，非 POST /update）。"""
    return client.put("/system/notelink", json=payload)


def delete_notelinks(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """DELETE /system/notelink/{ids} —— 删除语义关联（DELETE 方法，非 GET）。

    @PathVariable Long[] ids：Spring 自动按逗号转 Long 数组。
    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。
    """
    return client.delete(f"/system/notelink/{ids}")


def export_notelinks(
    client: RainoteClient,
    *,
    payload: dict[str, Any] | None = None,
) -> tuple[bytes, int]:
    """POST /system/notelink/export —— 导出语义关联为 Excel 二进制流。"""
    return client.download_binary(
        "POST", "/system/notelink/export", json=payload or {}
    )


# ---- Typer 命令函数 ----


def list_cmd(
    ctx: typer.Context,
    page: int = typer.Option(1, "--page", help="页码。"),
    size: int = typer.Option(10, "--size", help="每页条数。"),
) -> None:
    """分页查询语义关联列表。"""
    pagination = PaginationParams(page=page, size=size)
    with get_client(ctx) as client:
        api, exit_code = list_notelinks(client, pagination=pagination)
    emit(api, exit_code, ctx.obj, page=page, size=size)


def get_cmd(
    ctx: typer.Context,
    notelink_id: str = typer.Argument(..., help="语义关联 ID。"),
) -> None:
    """获取单条语义关联详情。"""
    with get_client(ctx) as client:
        api, exit_code = get_notelink(client, notelink_id)
    if api.ok and not api.has_data:
        typer.echo(f"未找到语义关联: {notelink_id}", err=True)
        raise typer.Exit(ExitCode.OK)
    emit(api, exit_code, ctx.obj)


def cell_cmd(
    ctx: typer.Context,
    link_column_id: str = typer.Argument(..., help="关联列 ID（linkColumnId）。"),
    link_item_id: str = typer.Argument(..., help="记录 ID（linkItemId）。"),
) -> None:
    """按单元格查询语义关联（指定关联列 + 记录）。"""
    with get_client(ctx) as client:
        api, exit_code = cell_notelinks(
            client, link_column_id=link_column_id, link_item_id=link_item_id
        )
    emit(api, exit_code, ctx.obj)


def by_note_cmd(
    ctx: typer.Context,
    note_id: str = typer.Argument(..., help="笔记 ID。"),
) -> None:
    """按笔记查询语义关联。

    注意：linkNoteId 可能 null（历史数据），输出时注意此字段可能缺失。
    """
    with get_client(ctx) as client:
        api, exit_code = by_note_notelinks(client, note_id=note_id)
    emit(api, exit_code, ctx.obj)


def create_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """新建语义关联（POST 根路径，无 /add）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = create_notelink(client, payload)
    emit(api, exit_code, ctx.obj)


def update_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """更新语义关联（PUT 根路径，非 POST /update）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_notelink(client, payload)
    emit(api, exit_code, ctx.obj)


def delete_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="语义关联 ID，多个用逗号分隔。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """删除语义关联（DELETE 方法，破坏性命令强制预检）。

    注意：与其他命令组不同，notelink delete 用 DELETE 方法（非 GET /remove）。
    @PathVariable Long[] ids，Spring 自动按逗号转 Long 数组。
    """
    if not confirm_destructive(f"删除语义关联 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = delete_notelinks(client, ids)
    emit(api, exit_code, ctx.obj)


def export_cmd(
    ctx: typer.Context,
    output_file: Optional[Path] = typer.Option(
        None, "--output", "-o", help="输出文件路径。"
    ),
    stdout: bool = typer.Option(False, "--stdout", help="输出到 stdout。"),
    file: Optional[str] = typer.Option(
        None, "--file", "-f", help="筛选条件 JSON 文件路径。"
    ),
) -> None:
    """导出语义关联为 Excel（二进制流）。"""
    if output_file is None and not stdout:
        emit_error("必须指定 -o <file> 或 --stdout 之一", ExitCode.VALIDATION)

    payload = read_payload_file(file) if file else {}

    with get_client(ctx) as client:
        data, exit_code = export_notelinks(client, payload=payload)

    if exit_code != ExitCode.OK:
        emit_error(f"导出失败（退出码 {exit_code}）", exit_code)

    try:
        write_binary(data, output_file, stdout=stdout)
    except ValueError as exc:
        emit_error(str(exc), ExitCode.VALIDATION)

    if output_file is not None:
        typer.echo(f"已导出: {output_file}（{len(data)} 字节）", err=True)


# ---- Typer sub-app ----

notelink_app = typer.Typer(
    name="notelink",
    help="语义关联 CRUD / 导出 / 单元格查询 / 按笔记查询。",
    no_args_is_help=True,
)

notelink_app.command("list")(list_cmd)
notelink_app.command("get")(get_cmd)
notelink_app.command("cell")(cell_cmd)
notelink_app.command("by-note")(by_note_cmd)
notelink_app.command("create")(create_cmd)
notelink_app.command("update")(update_cmd)
notelink_app.command("delete")(delete_cmd)
notelink_app.command("export")(export_cmd)
