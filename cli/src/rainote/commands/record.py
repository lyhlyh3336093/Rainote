"""record 命令组 —— 记录 CRUD / 排序 / 回填 / 导出。

端点映射（NoteRecordController.java）：
  - list:           GET  /system/record/pageList       (分页)
  - list-all:       GET  /system/record/list           (不分页, query: dwtableId/viewId)
  - search-list:    GET  /system/record/searchList     (query: keyword)
  - data-list:      GET  /system/record/dataList
  - get:            GET  /system/record/{id}
  - get-data:       GET  /system/record/data/{id}      (路径 /data/{id} 非 /getData/{id})
  - create:         POST /system/record/add
  - update:         POST /system/record/update         (方法 POST 非 PUT)
  - update-sort:    POST /system/record/updateSort     (@Log 被注释, 审计盲点)
  - delete:         GET  /system/record/remove/{ids}   (GET 写操作, 破坏性)
  - backfill-names: POST /system/record/backfillNames  (管理员端点, 破坏性, 强制 --confirm)
  - export:         POST /system/record/export         (二进制流)

--check-revision（同 note update）：update 前先 GET /{id} 取 revisionId 比对，
不匹配 → 退出码 7。已知后端 revisionId 当前硬编码为 1L 且不递增，此参数暂为
虚假并发保护（待后端修复递增逻辑后生效）。

安全提示：
  - updateSort 端点 ``@Log`` 注解被注释，排序变更不会落入操作审计日志。
  - backfillNames 是管理员高危端点，强制要求 ``--confirm`` 显式确认。
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


def list_records(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
) -> tuple[ApiResponse, int]:
    """GET /system/record/pageList —— 分页查询记录列表。"""
    params = pagination.to_query()
    return client.get("/system/record/pageList", params=params)


def list_all_records(
    client: RainoteClient,
    *,
    dwtable_id: str | None = None,
    view_id: str | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/record/list —— 不分页查询记录列表。

    :param dwtable_id: 多维表 ID 筛选（query: ``dwtableId``）
    :param view_id: 视图 ID 筛选（query: ``viewId``）
    """
    params: dict[str, Any] = {}
    if dwtable_id is not None:
        params["dwtableId"] = dwtable_id
    if view_id is not None:
        params["viewId"] = view_id
    return client.get("/system/record/list", params=params)


def search_list_records(
    client: RainoteClient,
    *,
    keyword: str | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/record/searchList —— 关键词搜索记录。

    :param keyword: 搜索关键词（query: ``keyword``）
    """
    params: dict[str, Any] = {}
    if keyword is not None:
        params["keyword"] = keyword
    return client.get("/system/record/searchList", params=params)


def data_list_records(client: RainoteClient) -> tuple[ApiResponse, int]:
    """GET /system/record/dataList —— 查询记录数据列表。"""
    return client.get("/system/record/dataList")


def get_record(
    client: RainoteClient,
    record_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/record/{id} —— 获取单条记录。

    后端约定：code=200 + 无 data 键表示未找到（AjaxResult data==null 时不 put data 键）。
    """
    return client.get(f"/system/record/{record_id}")


def get_data_record(
    client: RainoteClient,
    record_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/record/data/{id} —— 获取单条记录数据。

    注意：路径是 ``/data/{id}`` 非 ``/getData/{id}``（NoteRecordController 约定）。
    """
    return client.get(f"/system/record/data/{record_id}")


def create_record(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/record/add —— 新建记录。"""
    return client.post("/system/record/add", json=payload)


def update_record(
    client: RainoteClient,
    *,
    record_id: str,
    payload: dict[str, Any],
    check_revision: int | None = None,
) -> tuple[ApiResponse, int]:
    """POST /system/record/update —— 更新记录。

    方法为 POST（非 PUT），路径为 ``/update``（非 ``/user/update``）。

    :param check_revision: 期望的 revisionId；非 None 时先 GET ``/{id}`` 比对，
        不匹配 → 退出码 7 (WARN)，不执行更新。

    已知问题：后端 revisionId 硬编码为 1L 且不递增，--check-revision 当前
    提供虚假并发保护（见模块文档）。
    """
    if check_revision is not None:
        # 先 GET /{id} 获取当前 revisionId
        api, exit_code = client.get(f"/system/record/{record_id}")
        if not api.ok:
            return api, exit_code
        if not api.has_data or not isinstance(api.data, dict):
            return api, ExitCode.NOT_FOUND
        current_revision = api.data.get("revisionId")
        if current_revision != check_revision:
            return (
                ApiResponse(
                    code=601,
                    msg=(
                        f"revision 不匹配：期望 {check_revision}，"
                        f"实际 {current_revision}"
                    ),
                    raw={
                        "code": 601,
                        "msg": "revision 不匹配",
                        "expected": check_revision,
                        "actual": current_revision,
                    },
                ),
                ExitCode.WARN,
            )

    # 确保 payload 含 id（更新需要）
    if "id" not in payload:
        payload = {**payload, "id": record_id}
    return client.post("/system/record/update", json=payload)


def update_sort_record(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/record/updateSort —— 批量更新记录排序。

    审计盲点：后端 ``@Log`` 注解被注释，排序变更不会落入操作审计日志
    （见模块文档安全提示）。
    """
    return client.post("/system/record/updateSort", json=payload)


def delete_record(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/record/remove/{ids} —— 删除记录（支持批量，逗号分隔）。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。
    """
    return client.get(f"/system/record/remove/{ids}")


def backfill_names_record(client: RainoteClient) -> tuple[ApiResponse, int]:
    """POST /system/record/backfillNames —— 管理员端点，回填记录名称字段。

    破坏性命令（批量数据变更），调用方必须先强制 ``--confirm`` 预检
    （见 :func:`backfill_names_cmd`）。
    """
    return client.post("/system/record/backfillNames")


def export_records(
    client: RainoteClient,
    *,
    payload: dict[str, Any] | None = None,
) -> tuple[bytes, int]:
    """POST /system/record/export —— 导出记录为 Excel 二进制流。

    :param payload: 筛选条件 JSON（可为空）
    :return: ``(content_bytes, exit_code)``
    """
    return client.download_binary(
        "POST", "/system/record/export", json=payload or {}
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
) -> None:
    """分页查询记录列表。"""
    is_asc = "desc" if desc else ("asc" if asc else None)
    pagination = PaginationParams(
        page=page, size=size, order_by=order_by, is_asc=is_asc, reasonable=reasonable
    )
    with get_client(ctx) as client:
        api, exit_code = list_records(client, pagination=pagination)

    config = ctx.obj
    emit(api, exit_code, config, page=page, size=size)


def list_all_cmd(
    ctx: typer.Context,
    dwtable_id: Optional[str] = typer.Option(
        None, "--dwtable-id", help="按多维表 ID 筛选（query: dwtableId）。"
    ),
    view_id: Optional[str] = typer.Option(
        None, "--view-id", help="按视图 ID 筛选（query: viewId）。"
    ),
) -> None:
    """查询全部记录（不分页，可按多维表/视图筛选）。"""
    with get_client(ctx) as client:
        api, exit_code = list_all_records(
            client, dwtable_id=dwtable_id, view_id=view_id
        )

    config = ctx.obj
    emit(api, exit_code, config)


def search_list_cmd(
    ctx: typer.Context,
    keyword: Optional[str] = typer.Option(
        None, "--keyword", help="搜索关键词（query: keyword）。"
    ),
) -> None:
    """关键词搜索记录。"""
    with get_client(ctx) as client:
        api, exit_code = search_list_records(client, keyword=keyword)

    config = ctx.obj
    emit(api, exit_code, config)


def data_list_cmd(ctx: typer.Context) -> None:
    """查询记录数据列表。"""
    with get_client(ctx) as client:
        api, exit_code = data_list_records(client)

    config = ctx.obj
    emit(api, exit_code, config)


def get_cmd(
    ctx: typer.Context,
    record_id: str = typer.Argument(..., help="记录 ID。"),
) -> None:
    """获取单条记录详情。"""
    with get_client(ctx) as client:
        api, exit_code = get_record(client, record_id)

    config = ctx.obj
    # code=200 + 无 data 键 = 未找到
    if api.ok and not api.has_data:
        typer.echo(f"未找到记录: {record_id}", err=True)
        raise typer.Exit(ExitCode.OK)
    emit(api, exit_code, config)


def get_data_cmd(
    ctx: typer.Context,
    record_id: str = typer.Argument(..., help="记录 ID。"),
) -> None:
    """获取单条记录数据（路径 /data/{id}）。"""
    with get_client(ctx) as client:
        api, exit_code = get_data_record(client, record_id)

    config = ctx.obj
    # code=200 + 无 data 键 = 未找到
    if api.ok and not api.has_data:
        typer.echo(f"未找到记录数据: {record_id}", err=True)
        raise typer.Exit(ExitCode.OK)
    emit(api, exit_code, config)


def create_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """新建记录（从 JSON 文件读取 payload）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = create_record(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def update_cmd(
    ctx: typer.Context,
    record_id: str = typer.Argument(..., help="记录 ID。"),
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
    check_revision: Optional[int] = typer.Option(
        None,
        "--check-revision",
        help="并发冲突检测：期望的 revisionId（不匹配则退出码 7）。"
        "注意：后端 revisionId 当前硬编码为 1L 且不递增，此参数暂为虚假保护。",
    ),
) -> None:
    """更新记录（从 JSON 文件读取 payload）。

    方法为 POST（非 PUT），路径为 /update（NoteRecordController 约定）。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_record(
            client,
            record_id=record_id,
            payload=payload,
            check_revision=check_revision,
        )

    config = ctx.obj
    emit(api, exit_code, config)


def update_sort_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """批量更新记录排序。

    审计盲点：后端 ``@Log`` 注解被注释，排序变更不会落入操作审计日志。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_sort_record(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def delete_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="记录 ID，多个用逗号分隔（如 1,2,3）。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """删除记录（支持批量，破坏性命令强制预检）。

    后端用 GET /remove/{ids}（非 DELETE），ids 逗号拼接到 URL。
    """
    if not confirm_destructive(f"删除记录 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = delete_record(client, ids)

    config = ctx.obj
    emit(api, exit_code, config)


def backfill_names_cmd(
    ctx: typer.Context,
    confirm: bool = typer.Option(
        False,
        "--confirm",
        help="强制确认标志：管理员高危端点，必须显式传入 --confirm 才会执行。",
    ),
) -> None:
    """回填记录名称字段（管理员高危端点，强制 --confirm）。

    此端点批量变更记录数据，未传 ``--confirm`` 将直接拒绝执行（退出码 6）。
    """
    if not confirm:
        emit_error(
            "backfill-names 是管理员高危端点，必须传 --confirm 显式确认",
            ExitCode.VALIDATION,
        )

    with get_client(ctx) as client:
        api, exit_code = backfill_names_record(client)

    config = ctx.obj
    emit(api, exit_code, config)


def export_cmd(
    ctx: typer.Context,
    output_file: Optional[Path] = typer.Option(
        None, "--output", "-o", help="输出文件路径（如 records.xlsx）。"
    ),
    stdout: bool = typer.Option(False, "--stdout", help="输出到 stdout（二进制安全）。"),
    file: Optional[str] = typer.Option(
        None, "--file", "-f", help="筛选条件 JSON 文件路径。"
    ),
) -> None:
    """导出记录为 Excel（二进制流）。

    必须指定 ``-o <file>`` 或 ``--stdout`` 之一。
    """
    if output_file is None and not stdout:
        emit_error("必须指定 -o <file> 或 --stdout 之一", ExitCode.VALIDATION)

    payload = read_payload_file(file) if file else {}

    with get_client(ctx) as client:
        data, exit_code = export_records(client, payload=payload)

    if exit_code != ExitCode.OK:
        emit_error(f"导出失败（退出码 {exit_code}）", exit_code)

    try:
        write_binary(data, output_file, stdout=stdout)
    except ValueError as exc:
        emit_error(str(exc), ExitCode.VALIDATION)

    if output_file is not None:
        typer.echo(f"已导出: {output_file}（{len(data)} 字节）", err=True)


# ---- Typer sub-app ----

record_app = typer.Typer(
    name="record",
    help="记录 CRUD / 排序 / 回填 / 导出。",
    no_args_is_help=True,
)

record_app.command("list")(list_cmd)
record_app.command("list-all")(list_all_cmd)
record_app.command("search-list")(search_list_cmd)
record_app.command("data-list")(data_list_cmd)
record_app.command("get")(get_cmd)
record_app.command("get-data")(get_data_cmd)
record_app.command("create")(create_cmd)
record_app.command("update")(update_cmd)
record_app.command("update-sort")(update_sort_cmd)
record_app.command("delete")(delete_cmd)
record_app.command("backfill-names")(backfill_names_cmd)
record_app.command("export")(export_cmd)
