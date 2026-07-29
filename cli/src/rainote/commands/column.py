"""column 命令组 —— 列 CRUD / 排序 / 去重 / 导出。

端点映射（NoteColumnController.java）：
  - list:            GET  /system/column/list                              (L48,  分页, query 含 dwtableId)
  - column_list:     GET  /system/column/columnList                        (L61,  不分页, query 含 dwtableId)
  - double_link_list:GET  /system/column/selectNoteDoubleLinkColumnList    (L72,  query 含 dwtableId)
  - get:             GET  /system/column/{id}                              (L97)
  - add:             POST /system/column/add                               (L108, 接收 NoteColumnVo, property 是 JSON object)
  - update:          POST /system/column/update                            (L128, 方法 POST 非 PUT, 路径 /update 非 /edit)
  - update_sort:     POST /system/column/updateSort                        (L140, 代码异味: 接收 NoteRecordVo 而非 NoteColumnVo)
  - remove:          GET  /system/column/remove/{ids}                      (L168, GET 写操作, 破坏性)
  - deduplicate:     GET  /system/column/deduplicate?columnId=             (L179, GET 与项目记忆"必须 POST"冲突, CLI 以代码为准, 破坏性)
  - export:          POST /system/column/export                            (L85,  二进制流, body=筛选条件)

注意若干后端代码事实（CLI 以代码为准，非 REST 惯例，详见 endpoints.py）：
  - column update 路径是 ``/update``（非 /edit），方法 POST（非 PUT）
  - column updateSort 接收 ``NoteRecordVo``（非 NoteColumnVo，疑似 copy-paste bug），CLI 按代码事实调用
  - column deduplicate 是 GET（与项目记忆"必须 POST"冲突，CLI 以代码为准）
  - column remove 用 GET（与 note/dwtable/record/block 一致）
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

import typer

from ..client.http import RainoteClient
from ..client.pagination import PaginationParams
from ..errors import ExitCode
from ..models.common import ApiResponse
from ..output.binary import write_binary
from ._common import (
    confirm_destructive,
    emit,
    emit_error,
    get_client,
    read_payload_file,
)


# ---- 核心逻辑函数（可测试，不依赖 Typer）----


def list_columns(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
    dwtable_id: str | int | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/column/list —— 分页查询列列表。

    :param dwtable_id: 多维表 ID 筛选（query 参数 ``dwtableId``），None 表示不筛选
    """
    filters: dict[str, Any] = {}
    if dwtable_id is not None:
        filters["dwtableId"] = dwtable_id
    params = pagination.to_query(extra=filters)
    return client.get("/system/column/list", params=params)


def column_list(
    client: RainoteClient,
    *,
    dwtable_id: str | int,
) -> tuple[ApiResponse, int]:
    """GET /system/column/columnList?dwtableId= —— 不分页查询列列表。"""
    return client.get(
        "/system/column/columnList", params={"dwtableId": dwtable_id}
    )


def double_link_list(
    client: RainoteClient,
    *,
    dwtable_id: str | int,
) -> tuple[ApiResponse, int]:
    """GET /system/column/selectNoteDoubleLinkColumnList?dwtableId= —— 双链列列表。"""
    return client.get(
        "/system/column/selectNoteDoubleLinkColumnList",
        params={"dwtableId": dwtable_id},
    )


def get_column(
    client: RainoteClient,
    column_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/column/{id} —— 获取单条列。"""
    return client.get(f"/system/column/{column_id}")


def create_column(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/column/add —— 新建列。

    后端接收 ``NoteColumnVo``（非 ``NoteColumn`` 实体），其中 ``property`` 字段是
    ``myHashMap``，CLI 传 JSON object 即可（如 ``{"format": "number"}``）。
    """
    return client.post("/system/column/add", json=payload)


def update_column(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/column/update —— 更新列。

    注意：后端用 POST（非 PUT），路径是 ``/update``（非 ``/edit``），
    与 dwtable/block 的 ``/edit`` 路径不一致（见 endpoints.py）。
    """
    return client.post("/system/column/update", json=payload)


def update_sort_column(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/column/updateSort —— 更新列排序。

    代码异味：后端 ``updateSort`` 端点签名接收 ``NoteRecordVo`` 而非 ``NoteColumnVo``
    （疑似 copy-paste bug，见 NoteColumnController.java:140）。CLI 按代码事实调用，
    payload 字段以 ``NoteRecordVo`` 结构为准。
    """
    return client.post("/system/column/updateSort", json=payload)


def delete_column(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/column/remove/{ids} —— 删除列（支持批量，逗号分隔）。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。
    后端用 GET（非 DELETE），与 note/dwtable/record/block 的 remove 一致。
    """
    return client.get(f"/system/column/remove/{ids}")


def deduplicate_column(
    client: RainoteClient,
    *,
    column_id: str | int,
) -> tuple[ApiResponse, int]:
    """GET /system/column/deduplicate?columnId= —— 列去重。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。

    注意：此端点用 GET（与项目记忆"破坏性操作必须 POST"冲突）。
    CLI 以后端代码事实为准（见 endpoints.py: NoteColumnController.java:179）。
    """
    return client.get(
        "/system/column/deduplicate", params={"columnId": column_id}
    )


def export_columns(
    client: RainoteClient,
    *,
    payload: dict[str, Any] | None = None,
) -> tuple[bytes, int]:
    """POST /system/column/export —— 导出列为 Excel 二进制流。

    :param payload: 筛选条件 JSON（可为空）
    :return: ``(content_bytes, exit_code)``
    """
    return client.download_binary(
        "POST", "/system/column/export", json=payload or {}
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
    dwtable_id: Optional[str] = typer.Option(
        None, "--dwtable-id", help="按多维表 ID 筛选。"
    ),
) -> None:
    """分页查询列列表。"""
    is_asc = "desc" if desc else ("asc" if asc else None)
    pagination = PaginationParams(
        page=page, size=size, order_by=order_by, is_asc=is_asc, reasonable=reasonable
    )
    with get_client(ctx) as client:
        api, exit_code = list_columns(
            client, pagination=pagination, dwtable_id=dwtable_id
        )

    config = ctx.obj
    emit(api, exit_code, config, page=page, size=size)


def column_list_cmd(
    ctx: typer.Context,
    dwtable_id: str = typer.Option(..., "--dwtable-id", help="多维表 ID。"),
) -> None:
    """查询指定多维表的列列表（不分页）。"""
    with get_client(ctx) as client:
        api, exit_code = column_list(client, dwtable_id=dwtable_id)

    config = ctx.obj
    emit(api, exit_code, config)


def double_link_list_cmd(
    ctx: typer.Context,
    dwtable_id: str = typer.Option(..., "--dwtable-id", help="多维表 ID。"),
) -> None:
    """查询指定多维表的双链列列表。"""
    with get_client(ctx) as client:
        api, exit_code = double_link_list(client, dwtable_id=dwtable_id)

    config = ctx.obj
    emit(api, exit_code, config)


def get_cmd(
    ctx: typer.Context,
    column_id: str = typer.Argument(..., help="列 ID。"),
) -> None:
    """获取单条列详情。"""
    with get_client(ctx) as client:
        api, exit_code = get_column(client, column_id)

    config = ctx.obj
    emit(api, exit_code, config)


def create_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """新建列（从 JSON 文件读取 payload）。

    payload 是 ``NoteColumnVo`` 结构，其中 ``property`` 字段为 JSON object
    （如 ``{"format": "number"}``），后端以 ``myHashMap`` 接收。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = create_column(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def update_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """更新列（从 JSON 文件读取 payload）。

    方法为 POST（非 PUT），路径为 ``/update``（非 ``/edit``，NoteColumnController.java:128）。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_column(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def update_sort_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """更新列排序（从 JSON 文件读取 payload）。

    代码异味：后端 ``updateSort`` 接收 ``NoteRecordVo`` 而非 ``NoteColumnVo``
    （疑似 copy-paste bug），CLI 按代码事实调用（NoteColumnController.java:140）。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_sort_column(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def delete_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="列 ID，多个用逗号分隔（如 1,2,3）。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """删除列（支持批量，破坏性命令强制预检）。

    后端用 GET /remove/{ids}（非 DELETE），ids 逗号拼接到 URL。
    """
    if not confirm_destructive(f"删除列 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = delete_column(client, ids)

    config = ctx.obj
    emit(api, exit_code, config)


def deduplicate_cmd(
    ctx: typer.Context,
    column_id: str = typer.Option(..., "--column-id", help="要去重的列 ID。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """列去重（破坏性命令强制预检）。

    注意：后端用 GET（与项目记忆"破坏性操作必须 POST"冲突），CLI 以后端代码事实为准
    （NoteColumnController.java:179）。
    """
    if not confirm_destructive(f"对列 {column_id} 去重", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = deduplicate_column(client, column_id=column_id)

    config = ctx.obj
    emit(api, exit_code, config)


def export_cmd(
    ctx: typer.Context,
    output_file: Optional[Path] = typer.Option(
        None, "--output", "-o", help="输出文件路径（如 columns.xlsx）。"
    ),
    stdout: bool = typer.Option(False, "--stdout", help="输出到 stdout（二进制安全）。"),
    file: Optional[str] = typer.Option(
        None, "--file", "-f", help="筛选条件 JSON 文件路径。"
    ),
) -> None:
    """导出列为 Excel（二进制流）。

    必须指定 ``-o <file>`` 或 ``--stdout`` 之一。
    """
    if output_file is None and not stdout:
        emit_error("必须指定 -o <file> 或 --stdout 之一", ExitCode.VALIDATION)

    payload = read_payload_file(file) if file else {}

    with get_client(ctx) as client:
        data, exit_code = export_columns(client, payload=payload)

    if exit_code != ExitCode.OK:
        emit_error(f"导出失败（退出码 {exit_code}）", exit_code)

    try:
        write_binary(data, output_file, stdout=stdout)
    except ValueError as exc:
        emit_error(str(exc), ExitCode.VALIDATION)

    if output_file is not None:
        typer.echo(f"已导出: {output_file}（{len(data)} 字节）", err=True)


# ---- Typer sub-app ----

column_app = typer.Typer(
    name="column",
    help="列 CRUD / 排序 / 去重 / 导出。",
    no_args_is_help=True,
)

column_app.command("list")(list_cmd)
column_app.command("column-list")(column_list_cmd)
column_app.command("double-link-list")(double_link_list_cmd)
column_app.command("get")(get_cmd)
column_app.command("create")(create_cmd)
column_app.command("update")(update_cmd)
column_app.command("update-sort")(update_sort_cmd)
column_app.command("delete")(delete_cmd)
column_app.command("deduplicate")(deduplicate_cmd)
column_app.command("export")(export_cmd)
