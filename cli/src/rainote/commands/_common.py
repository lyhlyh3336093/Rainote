"""命令共享辅助 —— client 创建、输出渲染、破坏性命令预检。

所有命令函数通过本模块获取 :class:`RainoteClient` 与统一渲染/退出逻辑，
确保错误处理与退出码映射一致。
"""

from __future__ import annotations

import sys
from typing import Any

import typer

from ..client.http import RainoteClient
from ..config import Config
from ..errors import ExitCode
from ..models.common import ApiResponse
from ..output.renderer import render


def get_client(ctx: typer.Context) -> RainoteClient:
    """从 Typer 上下文创建 :class:`RainoteClient`。

    :param ctx: Typer 上下文，``ctx.obj`` 为 :class:`Config`
    :return: 配置好的 RainoteClient（调用方负责关闭，建议用 ``with``）
    """
    config: Config = ctx.obj
    return RainoteClient(
        base_url=config.base_url,
        token=config.token,
        verbose=config.verbose,
        allow_anonymous=config.allow_anonymous,
    )


def emit(
    api: ApiResponse,
    exit_code: int,
    config: Config,
    *,
    page: int | None = None,
    size: int | None = None,
    fields: list[str] | None = None,
    json_indent: int | None = None,
) -> None:
    """渲染输出到 stdout 并以指定退出码退出。

    :param api: 归一化响应
    :param exit_code: 语义化退出码
    :param config: CLI 配置（决定 output 格式、no_color 等）
    :raises typer.Exit: 总是以 ``exit_code`` 退出

    非成功响应（exit_code != OK）时，错误信息输出到 stderr，但仍按 --output 渲染
    响应体到 stdout（便于 --json 解析错误详情）。
    """
    output = render(
        api,
        config.output,
        page=page,
        size=size,
        fields=fields,
        no_color=config.no_color,
        json_indent=json_indent,
    )
    if output:
        typer.echo(output)

    if exit_code != ExitCode.OK and api.msg:
        typer.echo(f"错误: {api.msg}", err=True)

    raise typer.Exit(exit_code)


def emit_error(message: str, exit_code: int = ExitCode.SERVER) -> None:
    """输出错误信息到 stderr 并退出（无响应体时使用）。"""
    typer.echo(f"错误: {message}", err=True)
    raise typer.Exit(exit_code)


def confirm_destructive(action: str, *, yes: bool = False) -> bool:
    """破坏性命令预检确认。

    :param action: 操作描述（如 ``"删除笔记 1,2,3"``）
    :param yes: ``--yes`` 跳过确认
    :return: True 表示用户确认执行

    ``yes=True`` 时直接返回 True（跳过交互，适用于脚本）。
    """
    if yes:
        return True
    return typer.confirm(f"即将执行: {action}。确认?", default=False)


def read_payload_file(path: str) -> dict[str, Any]:
    """从 JSON 文件读取请求 payload。

    :param path: JSON 文件路径
    :return: 解析后的 dict
    :raises typer.BadParameter: 文件不存在或 JSON 解析失败
    """
    from pathlib import Path

    p = Path(path)
    if not p.exists():
        raise typer.BadParameter(f"文件不存在: {path}")
    try:
        import json

        return json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, ValueError) as exc:
        raise typer.BadParameter(f"JSON 解析失败: {exc}") from exc
