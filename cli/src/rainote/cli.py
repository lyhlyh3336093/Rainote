"""rainote CLI 入口 —— Typer app 与全局选项。

全局选项（R14）：
  --base-url / --output / --json / --csv / --table / --token / --token-file
  / --verbose / --no-color / --allow-anonymous（隐藏调试标志）

``--json``/``--csv``/``--table`` 是 ``--output`` 的快捷别名；同时指定时快捷别名优先。
``--allow-anonymous`` 使用 ``hidden=True``，不在 ``--help`` 默认输出中暴露（R14），
仅在后端 SecurityConfig L122 配置缺陷下作为调试逃生口，后端修复后将移除。

四层配置优先级由 :mod:`rainote.config` 实现，本模块仅负责收集 CLI 层覆盖并交给 Config.load。
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import typer

from . import __version__
from .config import Config, VALID_OUTPUTS

app = typer.Typer(
    name="rainote",
    help="雨滴笔记 CLI —— 命令行访问笔记、多维表、语义关联接口。",
    no_args_is_help=True,
    add_completion=False,
)


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(f"rainote {__version__}")
        raise typer.Exit()


def _resolve_output(
    output: Optional[str],
    json_out: bool,
    csv_out: bool,
    table_out: bool,
) -> Optional[str]:
    """从 --output 与 --json/--csv/--table 快捷别名解析有效输出格式。

    快捷别名优先于 --output；多个快捷别名同时给出时按 json > csv > table 取第一个。
    """
    if json_out:
        return "json"
    if csv_out:
        return "csv"
    if table_out:
        return "table"
    return output


def _read_token_file(token_file: Optional[Path]) -> Optional[str]:
    """从 --token-file 读取 token（去除首尾空白）。文件不存在时返回 None。"""
    if token_file is None:
        return None
    try:
        return token_file.read_text(encoding="utf-8").strip()
    except OSError:
        return None


@app.callback(invoke_without_command=True)
def main(
    ctx: typer.Context,
    base_url: Optional[str] = typer.Option(
        None, "--base-url", help="后端 API 基址（默认 http://localhost:8080）。"
    ),
    output: Optional[str] = typer.Option(
        None,
        "--output",
        help=f"输出格式，可选 {'/'.join(VALID_OUTPUTS)}。",
        metavar="{" + "|".join(VALID_OUTPUTS) + "}",
    ),
    json_out: bool = typer.Option(
        False, "--json", help="输出原始 JSON（--output json 的快捷别名）。",
    ),
    csv_out: bool = typer.Option(
        False, "--csv", help="输出 CSV（--output csv 的快捷别名）。",
    ),
    table_out: bool = typer.Option(
        False, "--table", help="输出表格（--output table 的快捷别名，默认）。",
    ),
    token: Optional[str] = typer.Option(
        None,
        "--token",
        help="JWT token（泄露风险高，推荐 RAINOTE_TOKEN 环境变量 / --token-file / rainote login）。",
    ),
    token_file: Optional[Path] = typer.Option(
        None, "--token-file", help="从文件读取 JWT token。",
    ),
    verbose: bool = typer.Option(
        False, "--verbose", help="输出详细诊断信息（Authorization 头自动脱敏）。",
    ),
    no_color: bool = typer.Option(
        False, "--no-color", help="禁用彩色输出（非 TTY 时自动启用）。",
    ),
    allow_anonymous: bool = typer.Option(
        False,
        "--allow-anonymous",
        hidden=True,
        help="调试标志：允许匿名调用（因后端 SecurityConfig L122 配置缺陷而存在，后端修复后将移除）。",
    ),
    version: bool = typer.Option(
        False,
        "--version",
        callback=_version_callback,
        is_eager=True,
        help="显示版本号并退出。",
    ),
) -> None:
    """雨滴笔记 CLI —— 命令行访问笔记、多维表、语义关联接口。"""
    resolved_output = _resolve_output(output, json_out, csv_out, table_out)
    file_token = _read_token_file(token_file)
    # --token 与 --token-file 同时给出时，--token 优先（显式意图）
    effective_token = token if token is not None else file_token

    # --token 一次性安全警告（R16）
    if token is not None:
        typer.echo(
            "警告: --token 参数会暴露在进程列表、shell 历史与系统日志中。"
            "推荐使用 RAINOTE_TOKEN 环境变量、--token-file 或 rainote login。",
            err=True,
        )

    config = Config.load(
        {
            "base_url": base_url,
            "output": resolved_output,
            "token": effective_token,
            "no_color": True if no_color else None,
            "verbose": True if verbose else None,
            "allow_anonymous": True if allow_anonymous else None,
        }
    )
    ctx.obj = config

    # 无子命令且非 --version/--help 时，Typer 通过 no_args_is_help 处理；
    # invoke_without_command 下显式提示
    if ctx.invoked_subcommand is None:
        # 仅当不是被 --version/--help 触发时
        if not version:
            typer.echo("rainote —— 雨滴笔记 CLI。使用 --help 查看可用命令。", err=True)


# 占位：子命令组在 U2/U4/U5/U6 中注册
# app.add_typer(note_app, name="note")   # U4
# app.add_typer(dwtable_app, name="dwtable")  # U5
# ...


if __name__ == "__main__":
    app()
