"""note CRUD 命令 —— list / get / create / update / delete。

端点映射（NoteNoteController.java）：
  - list:   GET  /system/note/pageList      (L118, 分页)
  - get:    GET  /system/note/{id}          (L192, code=200+无data键=未找到)
  - create: POST /system/note/add           (L225, 后端强制覆盖 auth)
  - update: POST /system/note/user/update   (L237, 方法 POST 非 PUT)
  - delete: GET  /system/note/remove/{ids}  (L273, GET 写操作, 破坏性)

--check-revision（KTD7）：update 前先 GET /{id} 取 revisionId 比对，不匹配 → 退出码 7。

已知问题（P1）：后端 NoteNote.revisionId 硬编码为 1L 且不递增（NoteNoteServiceImpl
未实现递增逻辑），因此 --check-revision 提供的是**虚假并发保护** —— 实际 revisionId
恒为 1。CLI 按计划实现该参数，待后端修复递增逻辑后即可生效。README 将标注此限制。
"""

from __future__ import annotations

from typing import Any, Optional

import typer

from ...client.http import RainoteClient
from ...client.pagination import PaginationParams
from ...errors import ExitCode
from ...models.common import ApiResponse
from .._common import (
    confirm_destructive,
    emit,
    emit_error,
    get_client,
    read_payload_file,
)


# ---- 核心逻辑函数（可测试，不依赖 Typer）----


def list_notes(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
    filters: dict[str, Any] | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/note/pageList —— 分页查询笔记列表。"""
    params = pagination.to_query(extra=filters)
    return client.get("/system/note/pageList", params=params)


def get_note(
    client: RainoteClient,
    note_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/note/{id} —— 获取单条笔记。

    后端约定：code=200 + 无 data 键表示未找到（AjaxResult data==null 时不 put data 键）。
    """
    return client.get(f"/system/note/{note_id}")


def create_note(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/note/add —— 新建笔记。

    注意：后端强制覆盖 ``auth=getUserId()``，CLI 传 auth 字段会被忽略。
    """
    return client.post("/system/note/add", json=payload)


def update_note(
    client: RainoteClient,
    *,
    note_id: str,
    payload: dict[str, Any],
    check_revision: int | None = None,
) -> tuple[ApiResponse, int]:
    """POST /system/note/user/update —— 更新笔记。

    :param check_revision: 期望的 revisionId；非 None 时先 GET /{id} 比对，
        不匹配 → 退出码 7 (WARN)，不执行更新。

    已知问题：后端 revisionId 硬编码为 1L 且不递增，--check-revision 当前
    提供虚假并发保护（见模块文档）。
    """
    if check_revision is not None:
        # 先 GET /{id} 获取当前 revisionId
        api, exit_code = client.get(f"/system/note/{note_id}")
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
        payload = {**payload, "id": note_id}
    return client.post("/system/note/user/update", json=payload)


def delete_notes(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/note/remove/{ids} —— 删除笔记（支持批量，逗号分隔）。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。
    """
    return client.get(f"/system/note/remove/{ids}")


# ---- Typer 命令函数 ----


def list_cmd(
    ctx: typer.Context,
    page: int = typer.Option(1, "--page", help="页码（从 1 起）。"),
    size: int = typer.Option(10, "--size", help="每页条数。"),
    order_by: Optional[str] = typer.Option(None, "--order-by", help="排序字段。"),
    desc: bool = typer.Option(False, "--desc", help="降序排序。"),
    asc: bool = typer.Option(False, "--asc", help="升序排序。"),
    reasonable: bool = typer.Option(False, "--reasonable", help="分页合理化。"),
    title: Optional[str] = typer.Option(None, "--title", help="按标题筛选。"),
) -> None:
    """分页查询笔记列表。"""
    is_asc = "desc" if desc else ("asc" if asc else None)
    pagination = PaginationParams(
        page=page, size=size, order_by=order_by, is_asc=is_asc, reasonable=reasonable
    )
    filters: dict[str, Any] = {}
    if title is not None:
        filters["title"] = title

    with get_client(ctx) as client:
        api, exit_code = list_notes(client, pagination=pagination, filters=filters)

    config = ctx.obj
    emit(api, exit_code, config, page=page, size=size)


def get_cmd(
    ctx: typer.Context,
    note_id: str = typer.Argument(..., help="笔记 ID。"),
) -> None:
    """获取单条笔记详情。"""
    with get_client(ctx) as client:
        api, exit_code = get_note(client, note_id)

    config = ctx.obj
    # code=200 + 无 data 键 = 未找到
    if api.ok and not api.has_data:
        typer.echo(f"未找到笔记: {note_id}", err=True)
        raise typer.Exit(ExitCode.OK)
    emit(api, exit_code, config)


def create_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """新建笔记（从 JSON 文件读取 payload）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = create_note(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def update_cmd(
    ctx: typer.Context,
    note_id: str = typer.Argument(..., help="笔记 ID。"),
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
    check_revision: Optional[int] = typer.Option(
        None,
        "--check-revision",
        help="并发冲突检测：期望的 revisionId（不匹配则退出码 7）。"
        "注意：后端 revisionId 当前硬编码为 1L 且不递增，此参数暂为虚假保护。",
    ),
) -> None:
    """更新笔记（从 JSON 文件读取 payload）。

    方法为 POST（非 PUT），路径为 /user/update（NoteNoteController.java:237）。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_note(
            client,
            note_id=note_id,
            payload=payload,
            check_revision=check_revision,
        )

    config = ctx.obj
    emit(api, exit_code, config)


def delete_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="笔记 ID，多个用逗号分隔（如 1,2,3）。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """删除笔记（支持批量，破坏性命令强制预检）。

    后端用 GET /remove/{ids}（非 DELETE），ids 逗号拼接到 URL。
    """
    if not confirm_destructive(f"删除笔记 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = delete_notes(client, ids)

    config = ctx.obj
    emit(api, exit_code, config)
