"""dwtable 命令组 —— 多维表 CRUD / 导出 / 数据查询。

端点映射（DwTableController.java）：
  - list:      GET   /system/dwtable/list          (分页, query: noteId/name/url)
  - page-list: GET   /system/dwtable/pageList      (分页)
  - get:       GET   /system/dwtable/{id}          (获取元数据)
  - get-data:  GET   /system/dwtable/data/{id}     (注意：路径是 /data/{id} 非 /getData/{id})
  - create:    POST  /system/dwtable/add           (新增)
  - edit:      POST  /system/dwtable/edit          (方法 POST 非 PUT)
  - delete:    GET   /system/dwtable/remove/{ids}  (GET 写操作, 破坏性)
  - export:    POST  /system/dwtable/export        (二进制流)

代码事实（CLI 以代码为准）：
  - get-data 路径为 ``/data/{id}``（非 ``/getData/{id}``）
  - edit 方法为 POST（非 PUT），路径为 ``/edit``
  - delete 用 GET ``/remove/{ids}``（非 DELETE），ids 逗号拼接到 URL
"""

from __future__ import annotations

from pathlib import Path
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


# ---- 核心逻辑函数（可测试，不依赖 Typer）----


def list_dwt(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
    filters: dict[str, Any] | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/dwtable/list —— 分页查询多维表列表。

    :param filters: 筛选条件，支持 ``noteId`` / ``name`` / ``url``
    """
    params = pagination.to_query(extra=filters)
    return client.get("/system/dwtable/list", params=params)


def page_list_dwt(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
) -> tuple[ApiResponse, int]:
    """GET /system/dwtable/pageList —— 分页查询多维表（另一分页端点）。"""
    params = pagination.to_query()
    return client.get("/system/dwtable/pageList", params=params)


def get_dwt(
    client: RainoteClient,
    dwt_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/dwtable/{id} —— 获取多维表元数据。"""
    return client.get(f"/system/dwtable/{dwt_id}")


def get_data_dwt(
    client: RainoteClient,
    dwt_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/dwtable/data/{id} —— 获取多维表数据。

    注意：路径是 ``/data/{id}``（非 ``/getData/{id}``）。
    """
    return client.get(f"/system/dwtable/data/{dwt_id}")


def create_dwt(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/dwtable/add —— 新建多维表。"""
    return client.post("/system/dwtable/add", json=payload)


def edit_dwt(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/dwtable/edit —— 编辑多维表。

    方法为 POST（非 PUT），路径为 ``/edit``。
    """
    return client.post("/system/dwtable/edit", json=payload)


def delete_dwt(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/dwtable/remove/{ids} —— 删除多维表（支持批量，逗号分隔）。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。
    """
    return client.get(f"/system/dwtable/remove/{ids}")


def export_dwt(
    client: RainoteClient,
    *,
    payload: dict[str, Any] | None = None,
) -> tuple[bytes, int]:
    """POST /system/dwtable/export —— 导出多维表为 Excel 二进制流。

    :param payload: 筛选条件 JSON（可为空）
    :return: ``(content_bytes, exit_code)``
    """
    return client.download_binary(
        "POST", "/system/dwtable/export", json=payload or {}
    )


# ---- Typer 命令函数 ----


def list_cmd(
    ctx: typer.Context,
    page: int = typer.Option(1, "--page", help="页码（从 1 起）。"),
    size: int = typer.Option(10, "--size", help="每页条数。"),
    order_by: Optional[str] = typer.Option(None, "--order-by", help="排序字段。"),
    desc: bool = typer.Option(False, "--desc", help="降序排序。"),
    asc: bool = typer.Option(False, "--asc", help="升序排序。"),
    reasonable: bool = typer.Option(False, "--reasonable", help="分页合理化。"),
    note_id: Optional[str] = typer.Option(None, "--note-id", help="按笔记 ID 筛选。"),
    name: Optional[str] = typer.Option(None, "--name", help="按名称筛选。"),
    url: Optional[str] = typer.Option(None, "--url", help="按 URL 筛选。"),
) -> None:
    """分页查询多维表列表。"""
    is_asc = "desc" if desc else ("asc" if asc else None)
    pagination = PaginationParams(
        page=page, size=size, order_by=order_by, is_asc=is_asc, reasonable=reasonable
    )
    filters: dict[str, Any] = {}
    if note_id is not None:
        filters["noteId"] = note_id
    if name is not None:
        filters["name"] = name
    if url is not None:
        filters["url"] = url

    with get_client(ctx) as client:
        api, exit_code = list_dwt(client, pagination=pagination, filters=filters)

    emit(api, exit_code, ctx.obj, page=page, size=size)


def page_list_cmd(
    ctx: typer.Context,
    page: int = typer.Option(1, "--page", help="页码（从 1 起）。"),
    size: int = typer.Option(10, "--size", help="每页条数。"),
    order_by: Optional[str] = typer.Option(None, "--order-by", help="排序字段。"),
    desc: bool = typer.Option(False, "--desc", help="降序排序。"),
    asc: bool = typer.Option(False, "--asc", help="升序排序。"),
    reasonable: bool = typer.Option(False, "--reasonable", help="分页合理化。"),
) -> None:
    """分页查询多维表（pageList 端点）。"""
    is_asc = "desc" if desc else ("asc" if asc else None)
    pagination = PaginationParams(
        page=page, size=size, order_by=order_by, is_asc=is_asc, reasonable=reasonable
    )
    with get_client(ctx) as client:
        api, exit_code = page_list_dwt(client, pagination=pagination)

    emit(api, exit_code, ctx.obj, page=page, size=size)


def get_cmd(
    ctx: typer.Context,
    dwt_id: str = typer.Argument(..., help="多维表 ID。"),
) -> None:
    """获取多维表元数据。"""
    with get_client(ctx) as client:
        api, exit_code = get_dwt(client, dwt_id)

    # code=200 + 无 data 键 = 未找到
    if api.ok and not api.has_data:
        typer.echo(f"未找到多维表: {dwt_id}", err=True)
        raise typer.Exit(ExitCode.OK)
    emit(api, exit_code, ctx.obj)


def get_data_cmd(
    ctx: typer.Context,
    dwt_id: str = typer.Argument(..., help="多维表 ID。"),
) -> None:
    """获取多维表数据（路径 /data/{id}）。"""
    with get_client(ctx) as client:
        api, exit_code = get_data_dwt(client, dwt_id)

    if api.ok and not api.has_data:
        typer.echo(f"未找到多维表数据: {dwt_id}", err=True)
        raise typer.Exit(ExitCode.OK)
    emit(api, exit_code, ctx.obj)


def create_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """新建多维表（从 JSON 文件读取 payload）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = create_dwt(client, payload)

    emit(api, exit_code, ctx.obj)


def edit_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """编辑多维表（从 JSON 文件读取 payload）。

    方法为 POST（非 PUT），路径为 ``/edit``。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = edit_dwt(client, payload)

    emit(api, exit_code, ctx.obj)


def delete_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="多维表 ID，多个用逗号分隔（如 1,2,3）。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """删除多维表（支持批量，破坏性命令强制预检）。

    后端用 GET ``/remove/{ids}``（非 DELETE），ids 逗号拼接到 URL。
    """
    if not confirm_destructive(f"删除多维表 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = delete_dwt(client, ids)

    emit(api, exit_code, ctx.obj)


def export_cmd(
    ctx: typer.Context,
    output_file: Optional[Path] = typer.Option(
        None, "--output", "-o", help="输出文件路径（如 dwtable.xlsx）。"
    ),
    stdout: bool = typer.Option(False, "--stdout", help="输出到 stdout（二进制安全）。"),
    file: Optional[str] = typer.Option(
        None, "--file", "-f", help="筛选条件 JSON 文件路径。"
    ),
) -> None:
    """导出多维表为 Excel（二进制流）。

    必须指定 ``-o <file>`` 或 ``--stdout`` 之一。
    """
    if output_file is None and not stdout:
        emit_error("必须指定 -o <file> 或 --stdout 之一", ExitCode.VALIDATION)

    payload = read_payload_file(file) if file else {}

    with get_client(ctx) as client:
        data, exit_code = export_dwt(client, payload=payload)

    if exit_code != ExitCode.OK:
        emit_error(f"导出失败（退出码 {exit_code}）", exit_code)

    try:
        write_binary(data, output_file, stdout=stdout)
    except ValueError as exc:
        emit_error(str(exc), ExitCode.VALIDATION)

    if output_file is not None:
        typer.echo(f"已导出: {output_file}（{len(data)} 字节）", err=True)


# ---- Typer sub-app ----

dwtable_app = typer.Typer(
    name="dwtable",
    help="多维表 CRUD / 导出。",
    no_args_is_help=True,
)

dwtable_app.command("list")(list_cmd)
dwtable_app.command("page-list")(page_list_cmd)
dwtable_app.command("get")(get_cmd)
dwtable_app.command("get-data")(get_data_cmd)
dwtable_app.command("create")(create_cmd)
dwtable_app.command("edit")(edit_cmd)
dwtable_app.command("delete")(delete_cmd)
dwtable_app.command("export")(export_cmd)
