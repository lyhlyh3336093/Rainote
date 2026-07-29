"""block 命令组 —— 块 CRUD / 批量更新 / 关联 / 导出。

端点映射（NoteBlockController.java）：
  - list:            GET  /system/block/list             (分页)
  - get:             GET  /system/block/{id}             (单条详情)
  - create:          POST /system/block/add              (新建)
  - update:          POST /system/block/update           (方法 POST 非 PUT)
  - update-batch:    POST /system/block/updateBatch      (body 是 List<NoteBlock> 数组非对象)
  - delete:          GET  /system/block/remove/{ids}     (GET 写操作, 破坏性)
  - link-to-dwtable: POST /system/block/linkToDwtable    (接收 NoteBlockVo)
  - remove-link:     POST /system/block/removeLink
  - export:          POST /system/block/export           (二进制流)

注意：update-batch 端点请求 body 是 ``List<NoteBlock>`` 数组（非对象），
传递时直接用 ``json=[...]``（列表），不是 ``json={...}``（字典）。
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


def list_blocks(
    client: RainoteClient,
    *,
    pagination: PaginationParams,
) -> tuple[ApiResponse, int]:
    """GET /system/block/list —— 分页查询块列表。"""
    params = pagination.to_query()
    return client.get("/system/block/list", params=params)


def get_block(
    client: RainoteClient,
    block_id: str,
) -> tuple[ApiResponse, int]:
    """GET /system/block/{id} —— 获取单条块详情。"""
    return client.get(f"/system/block/{block_id}")


def create_block(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/block/add —— 新建块。"""
    return client.post("/system/block/add", json=payload)


def update_block(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/block/update —— 更新块。

    方法为 POST（非 PUT），路径为 /update。
    """
    return client.post("/system/block/update", json=payload)


def update_batch_blocks(
    client: RainoteClient,
    blocks_list: list[dict[str, Any]],
) -> tuple[ApiResponse, int]:
    """POST /system/block/updateBatch —— 批量更新块。

    注意：请求 body 是 ``List<NoteBlock>`` 数组（非对象），
    传递时直接用 ``json=[...]``（列表），不是 ``json={...}``（字典）。
    """
    return client.post("/system/block/updateBatch", json=blocks_list)


def delete_block(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/block/remove/{ids} —— 删除块（支持批量，逗号分隔）。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。
    """
    return client.get(f"/system/block/remove/{ids}")


def link_to_dwtable(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/block/linkToDwtable —— 关联块到多维表（接收 NoteBlockVo）。"""
    return client.post("/system/block/linkToDwtable", json=payload)


def remove_link(
    client: RainoteClient,
    payload: dict[str, Any],
) -> tuple[ApiResponse, int]:
    """POST /system/block/removeLink —— 移除块关联。"""
    return client.post("/system/block/removeLink", json=payload)


def export_blocks(
    client: RainoteClient,
    *,
    payload: dict[str, Any] | None = None,
) -> tuple[bytes, int]:
    """POST /system/block/export —— 导出块为 Excel 二进制流。

    :param payload: 筛选条件 JSON（可为空）
    :return: ``(content_bytes, exit_code)``
    """
    return client.download_binary("POST", "/system/block/export", json=payload or {})


# ---- 辅助：读取 JSON 数组 payload ----


def _read_list_payload_file(path: str) -> list[dict[str, Any]]:
    """从 JSON 文件读取请求 payload（数组形式）。

    用于 update-batch 等需要 JSON 数组（非对象）的端点。
    与 :func:`read_payload_file` 区别：本函数返回 list 并校验顶层为数组。

    :param path: JSON 文件路径
    :return: 解析后的 list[dict]
    :raises typer.BadParameter: 文件不存在、JSON 解析失败或顶层非数组
    """
    p = Path(path)
    if not p.exists():
        raise typer.BadParameter(f"文件不存在: {path}")
    try:
        import json

        data = json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, ValueError) as exc:
        raise typer.BadParameter(f"JSON 解析失败: {exc}") from exc
    if not isinstance(data, list):
        raise typer.BadParameter(f"期望 JSON 数组，得到 {type(data).__name__}")
    return data


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
    """分页查询块列表。"""
    is_asc = "desc" if desc else ("asc" if asc else None)
    pagination = PaginationParams(
        page=page, size=size, order_by=order_by, is_asc=is_asc, reasonable=reasonable
    )
    with get_client(ctx) as client:
        api, exit_code = list_blocks(client, pagination=pagination)

    config = ctx.obj
    emit(api, exit_code, config, page=page, size=size)


def get_cmd(
    ctx: typer.Context,
    block_id: str = typer.Argument(..., help="块 ID。"),
) -> None:
    """获取单条块详情。"""
    with get_client(ctx) as client:
        api, exit_code = get_block(client, block_id)

    config = ctx.obj
    emit(api, exit_code, config)


def create_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """新建块（从 JSON 文件读取 payload）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = create_block(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def update_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """更新块（从 JSON 文件读取 payload）。

    方法为 POST（非 PUT），路径为 /update。
    """
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_block(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def update_batch_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON 数组 payload 文件路径。"),
) -> None:
    """批量更新块（从 JSON 文件读取数组 payload）。

    注意：请求 body 是 ``List<NoteBlock>`` 数组（非对象），
    ``--file`` 指向的 JSON 文件顶层必须是数组（如 ``[{...}, {...}]``）。
    """
    blocks_list = _read_list_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = update_batch_blocks(client, blocks_list)

    config = ctx.obj
    emit(api, exit_code, config)


def delete_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="块 ID，多个用逗号分隔（如 1,2,3）。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """删除块（支持批量，破坏性命令强制预检）。

    后端用 GET /remove/{ids}（非 DELETE），ids 逗号拼接到 URL。
    """
    if not confirm_destructive(f"删除块 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = delete_block(client, ids)

    config = ctx.obj
    emit(api, exit_code, config)


def link_to_dwtable_cmd(
    ctx: typer.Context,
    file: str = typer.Option(
        ..., "--file", "-f", help="JSON payload 文件路径（NoteBlockVo）。"
    ),
) -> None:
    """关联块到多维表（接收 NoteBlockVo）。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = link_to_dwtable(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def remove_link_cmd(
    ctx: typer.Context,
    file: str = typer.Option(..., "--file", "-f", help="JSON payload 文件路径。"),
) -> None:
    """移除块关联。"""
    payload = read_payload_file(file)
    with get_client(ctx) as client:
        api, exit_code = remove_link(client, payload)

    config = ctx.obj
    emit(api, exit_code, config)


def export_cmd(
    ctx: typer.Context,
    output_file: Optional[Path] = typer.Option(
        None, "--output", "-o", help="输出文件路径（如 blocks.xlsx）。"
    ),
    stdout: bool = typer.Option(False, "--stdout", help="输出到 stdout（二进制安全）。"),
    file: Optional[str] = typer.Option(
        None, "--file", "-f", help="筛选条件 JSON 文件路径。"
    ),
) -> None:
    """导出块为 Excel（二进制流）。

    必须指定 ``-o <file>`` 或 ``--stdout`` 之一。
    """
    if output_file is None and not stdout:
        emit_error("必须指定 -o <file> 或 --stdout 之一", ExitCode.VALIDATION)

    payload = read_payload_file(file) if file else {}

    with get_client(ctx) as client:
        data, exit_code = export_blocks(client, payload=payload)

    if exit_code != ExitCode.OK:
        emit_error(f"导出失败（退出码 {exit_code}）", exit_code)

    try:
        write_binary(data, output_file, stdout=stdout)
    except ValueError as exc:
        emit_error(str(exc), ExitCode.VALIDATION)

    if output_file is not None:
        typer.echo(f"已导出: {output_file}（{len(data)} 字节）", err=True)


# ---- Typer sub-app ----


block_app = typer.Typer(
    name="block",
    help="块 CRUD / 批量更新 / 关联 / 导出。",
    no_args_is_help=True,
)

block_app.command("list")(list_cmd)
block_app.command("get")(get_cmd)
block_app.command("create")(create_cmd)
block_app.command("update")(update_cmd)
block_app.command("update-batch")(update_batch_cmd)
block_app.command("delete")(delete_cmd)
block_app.command("link-to-dwtable")(link_to_dwtable_cmd)
block_app.command("remove-link")(remove_link_cmd)
block_app.command("export")(export_cmd)
