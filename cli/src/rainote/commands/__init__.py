"""命令组层 —— note / dwtable / column / record / block / notelink 子命令。

每个子命令组是一个 Typer sub-app，通过 ``app.add_typer(..., name=...)`` 挂载到
主 app。挂载时必须传 ``name`` 参数，避免 shadow（KTD）。
"""
