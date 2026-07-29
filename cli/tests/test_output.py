"""output 渲染层测试 —— json_out / csv_writer / table / renderer。

覆盖：
- 空结果渲染（table/csv/json）
- CSV 嵌套对象扁平化
- 非 TTY 自动禁色
- --json 绕过 Rich（无 ANSI）
- renderer 按 --output 分发
"""

from __future__ import annotations

import json

from rainote.models.common import ApiResponse
from rainote.output.csv_writer import flatten_obj, render_csv
from rainote.output.json_out import render_json
from rainote.output.renderer import render
from rainote.output.table import render_table


def _page_api(rows: list, total: int) -> ApiResponse:
    return ApiResponse(
        code=200,
        msg="查询成功",
        is_page=True,
        rows=rows,
        total=total,
        raw={"code": 200, "msg": "查询成功", "rows": rows, "total": total},
    )


def _single_api(data: dict | None, has_data: bool = True) -> ApiResponse:
    raw = {"code": 200, "msg": "操作成功"}
    if has_data:
        raw["data"] = data
    return ApiResponse(code=200, msg="操作成功", data=data, raw=raw)


# ---- json_out ----


class TestRenderJson:
    def test_page_empty(self) -> None:
        """空结果 json：分页接口输出 {"rows":[],"total":0}。"""
        api = _page_api([], 0)
        out = render_json(api)
        assert json.loads(out) == {"code": 200, "msg": "查询成功", "rows": [], "total": 0}

    def test_page_with_rows(self) -> None:
        api = _page_api([{"id": 1}], 1)
        out = render_json(api)
        body = json.loads(out)
        assert body["rows"] == [{"id": 1}]
        assert body["total"] == 1

    def test_no_ansi(self) -> None:
        """--json 绕过 Rich，输出纯 JSON 无 ANSI 污染。"""
        api = _page_api([{"id": 1}], 1)
        out = render_json(api)
        assert "\x1b[" not in out  # 无 ANSI 转义序列

    def test_single_result(self) -> None:
        api = _single_api({"id": 1, "title": "笔记"})
        out = render_json(api)
        body = json.loads(out)
        assert body["data"] == {"id": 1, "title": "笔记"}

    def test_indent(self) -> None:
        api = _single_api({"id": 1})
        out = render_json(api, indent=2)
        assert "\n" in out  # 缩进输出含换行

    def test_chinese_not_escaped(self) -> None:
        """中文不应被转义为 \\uXXXX。"""
        api = _single_api({"title": "笔记"})
        out = render_json(api)
        assert "笔记" in out
        assert "\\u" not in out


# ---- csv_writer ----


class TestFlattenObj:
    def test_flat(self) -> None:
        assert flatten_obj({"a": 1, "b": 2}) == {"a": 1, "b": 2}

    def test_nested(self) -> None:
        """嵌套对象 {a:{b:1}} 扁平化为 a.b。"""
        assert flatten_obj({"a": {"b": 1}}) == {"a.b": 1}

    def test_deep_nested(self) -> None:
        assert flatten_obj({"a": {"b": {"c": 2}}}) == {"a.b.c": 2}

    def test_list_preserved(self) -> None:
        """列表保留为原值（不展开索引）。"""
        assert flatten_obj({"a": [1, 2, 3]}) == {"a": [1, 2, 3]}

    def test_mixed(self) -> None:
        flat = flatten_obj({"id": 1, "meta": {"name": "x", "tags": ["a", "b"]}})
        assert flat == {"id": 1, "meta.name": "x", "meta.tags": ["a", "b"]}

    def test_none_value(self) -> None:
        assert flatten_obj({"a": None}) == {"a": None}


class TestRenderCsv:
    def test_page_with_rows(self) -> None:
        api = _page_api([{"id": 1, "title": "笔记1"}, {"id": 2, "title": "笔记2"}], 2)
        out = render_csv(api)
        lines = out.strip().split("\n")
        assert lines[0] == "id,title"
        assert lines[1] == "1,笔记1"
        assert lines[2] == "2,笔记2"

    def test_empty_with_fields(self) -> None:
        """空结果 csv（指定 fields）：仅输出表头行。"""
        api = _page_api([], 0)
        out = render_csv(api, fields=["id", "title"])
        lines = out.strip().split("\n")
        assert lines[0] == "id,title"
        assert len(lines) == 1  # 仅表头

    def test_empty_without_fields(self) -> None:
        """空结果 csv（未指定 fields）：输出空。"""
        api = _page_api([], 0)
        out = render_csv(api)
        assert out.strip() == ""

    def test_nested_flatten(self) -> None:
        """嵌套对象扁平化为 a.b 列。"""
        api = _page_api([{"id": 1, "author": {"name": "张三"}}], 1)
        out = render_csv(api)
        lines = out.strip().split("\n")
        assert "author.name" in lines[0]
        assert "张三" in lines[1]

    def test_fields_specify_order(self) -> None:
        """--fields 指定列顺序。"""
        api = _page_api([{"id": 1, "title": "x", "status": "active"}], 1)
        out = render_csv(api, fields=["title", "id"])
        lines = out.strip().split("\n")
        assert lines[0] == "title,id"
        assert lines[1] == "x,1"

    def test_no_ansi(self) -> None:
        api = _page_api([{"id": 1}], 1)
        out = render_csv(api)
        assert "\x1b[" not in out

    def test_single_result(self) -> None:
        """非分页单对象也能 csv 输出。"""
        api = _single_api({"id": 1, "title": "笔记"})
        out = render_csv(api)
        lines = out.strip().split("\n")
        assert lines[0] == "id,title"
        assert lines[1] == "1,笔记"


# ---- table ----


class TestRenderTable:
    def test_page_with_rows(self) -> None:
        api = _page_api([{"id": 1, "title": "笔记1"}], 1)
        out = render_table(api)
        assert "笔记1" in out
        assert "共 1 条" in out

    def test_empty_with_fields(self) -> None:
        """空 rows + fields：显示表头 + 灰色无数据行。"""
        api = _page_api([], 0)
        out = render_table(api, fields=["id", "title"])
        assert "无数据" in out
        assert "id" in out

    def test_empty_without_fields(self) -> None:
        """空 rows 无 fields：显示无数据提示。"""
        api = _page_api([], 0)
        out = render_table(api)
        assert "无数据" in out

    def test_no_ansi_in_non_tty(self) -> None:
        """非 TTY（StringIO 捕获）时 Rich 表格不含 ANSI 颜色码。"""
        api = _page_api([{"id": 1}], 1)
        out = render_table(api)
        assert "\x1b[" not in out

    def test_pagination_info(self) -> None:
        """分页数据顶部显示"共 N 条，第 x/y 页"。"""
        api = _page_api([{"id": 1}], 100)
        out = render_table(api, page=2, size=10)
        assert "共 100 条" in out
        assert "第 2" in out

    def test_single_result(self) -> None:
        api = _single_api({"id": 1, "title": "笔记"})
        out = render_table(api)
        assert "笔记" in out


# ---- renderer 统一入口 ----


class TestRenderer:
    def test_dispatch_json(self) -> None:
        api = _page_api([{"id": 1}], 1)
        out = render(api, output="json")
        body = json.loads(out)
        assert body["rows"] == [{"id": 1}]

    def test_dispatch_csv(self) -> None:
        api = _page_api([{"id": 1, "title": "x"}], 1)
        out = render(api, output="csv")
        assert "id,title" in out

    def test_dispatch_table(self) -> None:
        api = _page_api([{"id": 1, "title": "x"}], 1)
        out = render(api, output="table")
        assert "x" in out

    def test_table_default(self) -> None:
        """output=None 默认 table。"""
        api = _page_api([{"id": 1, "title": "x"}], 1)
        out = render(api, output=None)
        assert "x" in out

    def test_json_no_ansi(self) -> None:
        """--json 输出纯 JSON 无 ANSI。"""
        api = _page_api([{"id": 1}], 1)
        out = render(api, output="json")
        assert "\x1b[" not in out
