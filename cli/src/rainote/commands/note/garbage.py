"""note 回收站命令 —— garbage list / clear / recover。

端点映射（NoteNoteController.java）：
  - list:    GET /system/note/garbageList          (L132)
  - clear:   GET /system/note/clearGarbage/{ids}   (L286, 路径是 /clearGarbage 非 /remove, 破坏性)
  - recover: GET /system/note/recoverNote/{ids}    (L332, @PreAuthorize 缺失 → 匿名风险, 破坏性)

安全警告：``recoverNote`` 端点 ``@PreAuthorize`` 缺失，叠加 SecurityConfig L122 的
``permitAll`` → 匿名用户可执行恢复操作。README 将标注此风险。
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
)


# ---- 核心逻辑函数 ----


def garbage_list(
    client: RainoteClient,
    *,
    pagination: PaginationParams | None = None,
) -> tuple[ApiResponse, int]:
    """GET /system/note/garbageList —— 回收站列表。"""
    params = pagination.to_query() if pagination else {}
    return client.get("/system/note/garbageList", params=params)


def garbage_clear(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/note/clearGarbage/{ids} —— 彻底清除回收站笔记。

    破坏性命令（不可恢复），调用方应先 :func:`confirm_destructive` 预检。
    """
    return client.get(f"/system/note/clearGarbage/{ids}")


def garbage_recover(
    client: RainoteClient,
    ids: str,
) -> tuple[ApiResponse, int]:
    """GET /system/note/recoverNote/{ids} —— 恢复回收站笔记。

    破坏性命令，调用方应先 :func:`confirm_destructive` 预检。

    安全警告：此端点 ``@PreAuthorize`` 缺失，匿名用户可执行（见模块文档）。
    """
    return client.get(f"/system/note/recoverNote/{ids}")


# ---- Typer 命令函数 ----


def list_cmd(
    ctx: typer.Context,
    page: int = typer.Option(1, "--page", help="页码。"),
    size: int = typer.Option(10, "--size", help="每页条数。"),
) -> None:
    """查看回收站列表。"""
    pagination = PaginationParams(page=page, size=size)
    with get_client(ctx) as client:
        api, exit_code = garbage_list(client, pagination=pagination)

    config = ctx.obj
    emit(api, exit_code, config, page=page, size=size)


def clear_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="笔记 ID，多个用逗号分隔。用 all 清空全部。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """彻底清除回收站笔记（不可恢复，破坏性命令强制预检）。"""
    if not confirm_destructive(f"彻底清除回收站笔记 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = garbage_clear(client, ids)

    config = ctx.obj
    emit(api, exit_code, config)


def recover_cmd(
    ctx: typer.Context,
    ids: str = typer.Argument(..., help="笔记 ID，多个用逗号分隔。"),
    yes: bool = typer.Option(False, "--yes", "-y", help="跳过确认提示。"),
) -> None:
    """恢复回收站笔记（破坏性命令强制预检）。

    安全警告：此端点 @PreAuthorize 缺失，存在匿名执行风险。
    """
    if not confirm_destructive(f"恢复回收站笔记 {ids}", yes=yes):
        emit_error("已取消", ExitCode.OK)

    with get_client(ctx) as client:
        api, exit_code = garbage_recover(client, ids)

    config = ctx.obj
    emit(api, exit_code, config)
