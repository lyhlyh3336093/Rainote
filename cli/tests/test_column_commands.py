"""column 命令核心逻辑测试。

测试核心函数（不依赖 Typer/CliRunner），用 respx mock HTTP。
覆盖端点路径、分页参数、dwtableId 筛选、property JSON object 传递、
update 路径 /update（非 /edit）、updateSort 路径、delete GET、deduplicate GET（非 POST）、
export 二进制流。
"""

from __future__ import annotations

import json
from pathlib import Path

import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.column import (
    column_list,
    create_column,
    deduplicate_column,
    delete_column,
    double_link_list,
    export_columns,
    get_column,
    list_columns,
    update_column,
    update_sort_column,
)
from rainote.errors import ExitCode
from rainote.output.binary import write_binary


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


# ---- list ----


class TestListColumns:
    @respx.mock
    def test_list_success(self) -> None:
        """mock /list 返回 {code:200, rows:[...], total:5}。"""
        respx.get(f"{BASE_URL}/system/column/list").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "name": "列1"}],
                "total": 5,
            },
        )
        with _client() as c:
            api, exit_code = list_columns(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 5
        assert api.rows == [{"id": 1, "name": "列1"}]

    @respx.mock
    def test_list_pagination_params(self) -> None:
        """--page 2 --size 20 --order-by createTime --desc --reasonable → query string 正确。"""
        route = respx.get(f"{BASE_URL}/system/column/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_columns(
                c,
                pagination=PaginationParams(
                    page=2, size=20, order_by="createTime",
                    is_asc="desc", reasonable=True,
                ),
            )
        req = route.calls.last.request
        params = dict(req.url.params)
        assert params["pageNum"] == "2"
        assert params["pageSize"] == "20"
        assert params["orderByColumn"] == "createTime"
        assert params["isAsc"] == "desc"
        assert params["reasonable"] == "true"

    @respx.mock
    def test_list_with_dwtable_id_filter(self) -> None:
        """--dwtable-id 1 → query 含 dwtableId=1。"""
        route = respx.get(f"{BASE_URL}/system/column/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_columns(
                c,
                pagination=PaginationParams(page=1, size=10),
                dwtable_id="1",
            )
        params = dict(route.calls.last.request.url.params)
        assert params["dwtableId"] == "1"
        assert params["pageNum"] == "1"

    @respx.mock
    def test_list_without_dwtable_id(self) -> None:
        """未传 dwtable_id 时 query 不含 dwtableId。"""
        route = respx.get(f"{BASE_URL}/system/column/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_columns(c, pagination=PaginationParams(page=1, size=10))
        params = dict(route.calls.last.request.url.params)
        assert "dwtableId" not in params


# ---- column_list (不分页) ----


class TestColumnList:
    @respx.mock
    def test_column_list_path(self) -> None:
        """路径是 /columnList?dwtableId=1。"""
        route = respx.get(f"{BASE_URL}/system/column/columnList").respond(
            200, json={"code": 200, "msg": "ok", "data": [{"id": 1, "name": "列1"}]}
        )
        with _client() as c:
            api, exit_code = column_list(c, dwtable_id="1")
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/column/columnList"
        params = dict(route.calls.last.request.url.params)
        assert params["dwtableId"] == "1"

    @respx.mock
    def test_column_list_no_pagination(self) -> None:
        """columnList 不分页 → query 不含 pageNum/pageSize。"""
        route = respx.get(f"{BASE_URL}/system/column/columnList").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            column_list(c, dwtable_id="42")
        params = dict(route.calls.last.request.url.params)
        assert "pageNum" not in params
        assert "pageSize" not in params
        assert params["dwtableId"] == "42"


# ---- double_link_list ----


class TestDoubleLinkList:
    @respx.mock
    def test_double_link_list_path(self) -> None:
        """路径是 /selectNoteDoubleLinkColumnList?dwtableId=1。"""
        route = respx.get(
            f"{BASE_URL}/system/column/selectNoteDoubleLinkColumnList"
        ).respond(
            200, json={"code": 200, "msg": "ok", "data": [{"id": 1, "name": "双链列"}]}
        )
        with _client() as c:
            api, exit_code = double_link_list(c, dwtable_id="1")
        assert exit_code == ExitCode.OK
        assert route.called
        assert (
            route.calls.last.request.url.path
            == "/system/column/selectNoteDoubleLinkColumnList"
        )
        params = dict(route.calls.last.request.url.params)
        assert params["dwtableId"] == "1"


# ---- get ----


class TestGetColumn:
    @respx.mock
    def test_get_exists(self) -> None:
        """mock /{id} 返回 {code:200, data:{...}}。"""
        respx.get(f"{BASE_URL}/system/column/42").respond(
            200,
            json={"code": 200, "msg": "操作成功", "data": {"id": 42, "name": "列"}},
        )
        with _client() as c:
            api, exit_code = get_column(c, "42")
        assert exit_code == ExitCode.OK
        assert api.has_data is True
        assert api.data == {"id": 42, "name": "列"}

    @respx.mock
    def test_get_error_401(self) -> None:
        respx.get(f"{BASE_URL}/system/column/1").respond(
            200, json={"code": 401, "msg": "未授权"}
        )
        with _client() as c:
            api, exit_code = get_column(c, "1")
        assert exit_code == ExitCode.AUTH


# ---- create ----


class TestCreateColumn:
    @respx.mock
    def test_create_success(self) -> None:
        """mock POST /add 返回 {code:200, msg:"新增成功"}。"""
        route = respx.post(f"{BASE_URL}/system/column/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        with _client() as c:
            api, exit_code = create_column(
                c,
                {
                    "dwtableId": 1,
                    "name": "新列",
                    "property": {"format": "number"},
                },
            )
        assert exit_code == ExitCode.OK
        assert route.called
        body = json.loads(route.calls.last.request.content)
        assert body["name"] == "新列"
        # 验证 property 是 JSON object 传递
        assert body["property"] == {"format": "number"}

    @respx.mock
    def test_create_path_is_add(self) -> None:
        """验证路径是 /add 非 /。"""
        route = respx.post(f"{BASE_URL}/system/column/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_column(c, {"name": "x"})
        assert route.called
        assert route.calls.last.request.url.path == "/system/column/add"

    @respx.mock
    def test_create_property_json_object_passes_through(self) -> None:
        """property 是 JSON object（含嵌套结构），原样传递给后端。"""
        route = respx.post(f"{BASE_URL}/system/column/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        property_obj = {
            "format": "select",
            "options": [{"label": "A", "value": "a"}, {"label": "B", "value": "b"}],
            "defaults": None,
        }
        with _client() as c:
            create_column(
                c,
                {"dwtableId": 1, "name": "选择列", "property": property_obj},
            )
        body = json.loads(route.calls.last.request.content)
        assert body["property"] == property_obj
        # 嵌套结构未被字符串化
        assert isinstance(body["property"], dict)
        assert isinstance(body["property"]["options"], list)


# ---- update ----


class TestUpdateColumn:
    @respx.mock
    def test_update_success(self) -> None:
        """mock POST /update 返回成功（验证方法为 POST，路径 /update 非 /edit）。"""
        route = respx.post(f"{BASE_URL}/system/column/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_column(
                c, {"id": 1, "name": "更新名称"}
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "POST"
        assert route.calls.last.request.url.path == "/system/column/update"

    @respx.mock
    def test_update_path_is_not_edit(self) -> None:
        """验证 update 路径是 /update，不是 /edit（与 dwtable/block 区分）。"""
        edit_route = respx.post(f"{BASE_URL}/system/column/edit").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        update_route = respx.post(f"{BASE_URL}/system/column/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_column(c, {"id": 1, "name": "x"})
        assert update_route.called
        assert not edit_route.called

    @respx.mock
    def test_update_uses_post_not_put(self) -> None:
        """update 用 POST 非 PUT。"""
        put_route = respx.put(f"{BASE_URL}/system/column/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        post_route = respx.post(f"{BASE_URL}/system/column/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_column(c, {"id": 1, "name": "x"})
        assert post_route.called
        assert not put_route.called


# ---- update_sort ----


class TestUpdateSortColumn:
    @respx.mock
    def test_update_sort_success(self) -> None:
        """mock POST /updateSort 返回成功（验证路径 /updateSort）。"""
        route = respx.post(f"{BASE_URL}/system/column/updateSort").respond(
            200, json={"code": 200, "msg": "排序更新成功"}
        )
        with _client() as c:
            api, exit_code = update_sort_column(
                c, {"dwtableId": 1, "sorts": [{"id": 1, "sort": 0}]}
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "POST"
        assert route.calls.last.request.url.path == "/system/column/updateSort"

    @respx.mock
    def test_update_sort_payload_passes_through(self) -> None:
        """payload 透传（NoteRecordVo 结构，代码异味由注释标注，CLI 不做转换）。"""
        route = respx.post(f"{BASE_URL}/system/column/updateSort").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        payload = {"dwtableId": 7, "records": [{"id": 1, "sort": 1}]}
        with _client() as c:
            update_sort_column(c, payload)
        body = json.loads(route.calls.last.request.content)
        assert body == payload


# ---- delete ----


class TestDeleteColumn:
    @respx.mock
    def test_delete_single(self) -> None:
        """delete 1 → /remove/1。"""
        route = respx.get(f"{BASE_URL}/system/column/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            api, exit_code = delete_column(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_batch(self) -> None:
        """delete 1,2,3 拼接为 /remove/1,2,3。"""
        route = respx.get(f"{BASE_URL}/system/column/remove/1,2,3").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            delete_column(c, "1,2,3")
        assert route.called
        assert route.calls.last.request.url.path == "/system/column/remove/1,2,3"

    @respx.mock
    def test_delete_uses_get(self) -> None:
        """验证 delete 用 GET（非 DELETE）。"""
        route = respx.get(f"{BASE_URL}/system/column/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            delete_column(c, "1")
        assert route.calls.last.request.method == "GET"


# ---- deduplicate ----


class TestDeduplicateColumn:
    @respx.mock
    def test_deduplicate_success(self) -> None:
        """mock GET /deduplicate?columnId=123 返回成功。"""
        route = respx.get(f"{BASE_URL}/system/column/deduplicate").respond(
            200, json={"code": 200, "msg": "去重成功"}
        )
        with _client() as c:
            api, exit_code = deduplicate_column(c, column_id="123")
        assert exit_code == ExitCode.OK
        assert route.called
        params = dict(route.calls.last.request.url.params)
        assert params["columnId"] == "123"

    @respx.mock
    def test_deduplicate_uses_get_not_post(self) -> None:
        """验证 deduplicate 用 GET（非 POST）—— 与项目记忆"必须 POST"冲突，CLI 以代码为准。"""
        post_route = respx.post(f"{BASE_URL}/system/column/deduplicate").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        get_route = respx.get(f"{BASE_URL}/system/column/deduplicate").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            deduplicate_column(c, column_id="123")
        assert get_route.called
        assert not post_route.called

    @respx.mock
    def test_deduplicate_path(self) -> None:
        """验证路径是 /system/column/deduplicate。"""
        route = respx.get(f"{BASE_URL}/system/column/deduplicate").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            deduplicate_column(c, column_id="42")
        assert route.calls.last.request.url.path == "/system/column/deduplicate"


# ---- export ----


class TestExportColumns:
    @respx.mock
    def test_export_success(self) -> None:
        """mock /export 返回二进制流。"""
        content = b"PK\x03\x04fake excel content"
        route = respx.post(f"{BASE_URL}/system/column/export").respond(
            200, content=content,
            headers={"content-type": "application/vnd.ms-excel"},
        )
        with _client() as c:
            data, exit_code = export_columns(c, payload={"dwtableId": 1})
        assert exit_code == ExitCode.OK
        assert data == content
        assert route.called

    @respx.mock
    def test_export_to_file(self, tmp_path: Path) -> None:
        """-o columns.xlsx 写入文件，验证文件大小匹配。"""
        content = b"fake excel content for file"
        respx.post(f"{BASE_URL}/system/column/export").respond(
            200, content=content
        )
        out_file = tmp_path / "columns.xlsx"
        with _client() as c:
            data, exit_code = export_columns(c)
        write_binary(data, output=out_file)
        assert out_file.exists()
        assert out_file.read_bytes() == content
        assert out_file.stat().st_size == len(content)

    @respx.mock
    def test_export_with_filter_payload(self) -> None:
        """--file 指定筛选 JSON，验证请求体传递。"""
        content = b"filtered export"
        route = respx.post(f"{BASE_URL}/system/column/export").respond(
            200, content=content
        )
        with _client() as c:
            export_columns(
                c, payload={"dwtableId": 1, "name": "筛选名称"}
            )
        body = json.loads(route.calls.last.request.content)
        assert body["dwtableId"] == 1
        assert body["name"] == "筛选名称"

    @respx.mock
    def test_export_empty_payload(self) -> None:
        """无筛选条件 → 空 payload。"""
        content = b"all export"
        route = respx.post(f"{BASE_URL}/system/column/export").respond(
            200, content=content
        )
        with _client() as c:
            export_columns(c, payload=None)
        assert route.called

    @respx.mock
    def test_export_500(self) -> None:
        """导出 500 → 退出码 5。"""
        respx.post(f"{BASE_URL}/system/column/export").respond(
            500, content=b"error"
        )
        with _client() as c:
            data, exit_code = export_columns(c)
        assert exit_code == ExitCode.SERVER
        assert data == b""

    @respx.mock
    def test_export_404(self) -> None:
        """导出 404 → 退出码 4。"""
        respx.post(f"{BASE_URL}/system/column/export").respond(
            404, content=b"not found"
        )
        with _client() as c:
            data, exit_code = export_columns(c)
        assert exit_code == ExitCode.NOT_FOUND
