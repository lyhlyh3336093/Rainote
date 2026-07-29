"""输出渲染层 —— json / csv / table 三种渲染器 + 统一分发入口。

设计要点（R6/R7）：

- ``--json`` 走 :mod:`json_out`，直接 ``json.dumps`` + ``sys.stdout.write``，**不经过 Rich**，
  确保无 ANSI 污染，可安全管道至 ``jq`` 等工具
- ``--csv`` 走 :mod:`csv_writer`，Python 标准库 ``csv`` 模块，嵌套对象扁平化（``a.b.c``），
  绕过 Rich
- ``--table``（默认）走 :mod:`table`，Rich 表格；非 TTY 自动禁色
"""
