"""note 命令组 —— 笔记 CRUD / 回收站 / 导出。

Typer sub-app，通过 ``app.add_typer(note_app, name="note")`` 挂载到主 app。
挂载时传 ``name="note"`` 避免 shadow（KTD）。

命令结构::

    rainote note list          # 分页查询
    rainote note get <id>      # 获取详情
    rainote note create --file # 新建
    rainote note update <id> --file [--check-revision N]
    rainote note delete <ids>  # 删除（破坏性）
    rainote note export -o f.xlsx
    rainote note garbage list
    rainote note garbage clear <ids>    # 彻底清除（破坏性）
    rainote note garbage recover <ids>  # 恢复（破坏性，@PreAuthorize 缺失）
"""

from __future__ import annotations

import typer

from .crud import (
    create_cmd,
    delete_cmd,
    get_cmd,
    list_cmd as list_notes_cmd,
    update_cmd,
)
from .export import export_cmd
from .garbage import (
    clear_cmd as garbage_clear_cmd,
    list_cmd as garbage_list_cmd,
    recover_cmd as garbage_recover_cmd,
)

note_app = typer.Typer(
    name="note",
    help="笔记 CRUD / 回收站 / 导出。",
    no_args_is_help=True,
)

# CRUD 命令
note_app.command("list")(list_notes_cmd)
note_app.command("get")(get_cmd)
note_app.command("create")(create_cmd)
note_app.command("update")(update_cmd)
note_app.command("delete")(delete_cmd)

# 导出命令
note_app.command("export")(export_cmd)

# 回收站子命令组
garbage_app = typer.Typer(
    name="garbage",
    help="回收站管理。",
    no_args_is_help=True,
)
garbage_app.command("list")(garbage_list_cmd)
garbage_app.command("clear")(garbage_clear_cmd)
garbage_app.command("recover")(garbage_recover_cmd)
note_app.add_typer(garbage_app, name="garbage")
