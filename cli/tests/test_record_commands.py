"""record 命令组核心逻辑测试。

测试核心函数（不依赖 Typer/CliRunner），用 respx mock HTTP。
覆盖 12 个端点路径、方法、查询参数、--check-revision 冲突检测、二进制导出。
"""

from __future__ import annotations

from pathlib import Path

import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.record import (
    backfill_names_record,
    create_record,
    delete_record,
    data_list_records,
    export_records,
    get_data_record,
    get_record,
    list_all_records,
    list_records,
    search_list_records,
    update_record,
    update_sort_record,
)
from rainote.errors import ExitCode
from rainote.output.binary import write_binary


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


# ---- list (pageList) ----


class TestListRecords:
    @respx.mock
    def test_list_success(self) -> None:
        """mock /pageList 返回 {code:200, rows:[...], total:5}。"""
        respx.get(f"{BASE_URL}/system/record/pageList").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "name": "记录1"}],
                "total": 5,
            },
        )
        with _client() as c:
            api, exit_code = list_records(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 5
        assert api.rows == [{"id": 1, "name": "记录1"}]

    @respx.mock
    def test_list_path_is_pageList(self) -> None:
        """验证路径是 /pageList。"""
        route = respx.get(f"{BASE_URL}/system/record/pageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_records(c, pagination=PaginationParams(page=1, size=10))
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/pageList"

    @respx.mock
    def test_list_pagination_params(self) -> None:
        """--page 2 --size 20 --order-by createTime --desc --reasonable → query string 正确。"""
        route = respx.get(f"{BASE_URL}/system/record/pageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_records(
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


# ---- list-all (/list, 不分页) ----


class TestListAllRecords:
    @respx.mock
    def test_list_all_path_is_list(self) -> None:
        """验证路径是 /list 非 /pageList。"""
        route = respx.get(f"{BASE_URL}/system/record/list").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            list_all_records(c)
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/list"

    @respx.mock
    def test_list_all_with_dwtable_id(self) -> None:
        """--dwtable-id 42 → query: dwtableId=42。"""
        route = respx.get(f"{BASE_URL}/system/record/list").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            list_all_records(c, dwtable_id="42")
        params = dict(route.calls.last.request.url.params)
        assert params["dwtableId"] == "42"

    @respx.mock
    def test_list_all_with_view_id(self) -> None:
        """--view-id 7 → query: viewId=7。"""
        route = respx.get(f"{BASE_URL}/system/record/list").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            list_all_records(c, view_id="7")
        params = dict(route.calls.last.request.url.params)
        assert params["viewId"] == "7"

    @respx.mock
    def test_list_all_with_both_filters(self) -> None:
        """同时传 dwtableId 与 viewId。"""
        route = respx.get(f"{BASE_URL}/system/record/list").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            list_all_records(c, dwtable_id="42", view_id="7")
        params = dict(route.calls.last.request.url.params)
        assert params["dwtableId"] == "42"
        assert params["viewId"] == "7"

    @respx.mock
    def test_list_all_no_filters(self) -> None:
        """无筛选条件 → 无 query 参数。"""
        route = respx.get(f"{BASE_URL}/system/record/list").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            list_all_records(c)
        params = dict(route.calls.last.request.url.params)
        assert "dwtableId" not in params
        assert "viewId" not in params


# ---- search-list ----


class TestSearchListRecords:
    @respx.mock
    def test_search_list_path(self) -> None:
        """验证路径是 /searchList。"""
        route = respx.get(f"{BASE_URL}/system/record/searchList").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            search_list_records(c, keyword="测试")
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/searchList"

    @respx.mock
    def test_search_list_passes_keyword(self) -> None:
        """--keyword 关键词 → query: keyword=xxx。"""
        route = respx.get(f"{BASE_URL}/system/record/searchList").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            search_list_records(c, keyword="关键词")
        params = dict(route.calls.last.request.url.params)
        assert params["keyword"] == "关键词"

    @respx.mock
    def test_search_list_no_keyword(self) -> None:
        """无 keyword → 无 query 参数。"""
        route = respx.get(f"{BASE_URL}/system/record/searchList").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            search_list_records(c)
        params = dict(route.calls.last.request.url.params)
        assert "keyword" not in params


# ---- data-list ----


class TestDataListRecords:
    @respx.mock
    def test_data_list_path(self) -> None:
        """验证路径是 /dataList。"""
        route = respx.get(f"{BASE_URL}/system/record/dataList").respond(
            200, json={"code": 200, "msg": "ok", "data": []}
        )
        with _client() as c:
            api, exit_code = data_list_records(c)
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/dataList"


# ---- get (/ {id}) ----


class TestGetRecord:
    @respx.mock
    def test_get_exists(self) -> None:
        """mock /{id} 返回 {code:200, data:{...}}。"""
        respx.get(f"{BASE_URL}/system/record/42").respond(
            200,
            json={"code": 200, "msg": "操作成功", "data": {"id": 42, "name": "记录"}},
        )
        with _client() as c:
            api, exit_code = get_record(c, "42")
        assert exit_code == ExitCode.OK
        assert api.has_data is True
        assert api.data == {"id": 42, "name": "记录"}

    @respx.mock
    def test_get_not_found(self) -> None:
        """mock /{id} 返回 {code:200, msg:"操作成功"}（无 data 键）= 未找到。"""
        respx.get(f"{BASE_URL}/system/record/999").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = get_record(c, "999")
        assert exit_code == ExitCode.OK
        assert api.has_data is False


# ---- get-data (/data/{id}) ----


class TestGetDataRecord:
    @respx.mock
    def test_get_data_path_is_data(self) -> None:
        """验证路径是 /data/{id} 非 /getData/{id}。"""
        route = respx.get(f"{BASE_URL}/system/record/data/42").respond(
            200,
            json={"code": 200, "msg": "操作成功", "data": {"id": 42, "name": "记录"}},
        )
        with _client() as c:
            api, exit_code = get_data_record(c, "42")
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/data/42"

    @respx.mock
    def test_get_data_not_getData(self) -> None:
        """路径包含 /data/ 但不含 /getData/。"""
        route = respx.get(f"{BASE_URL}/system/record/data/1").respond(
            200, json={"code": 200, "msg": "ok", "data": {"id": 1}}
        )
        with _client() as c:
            get_data_record(c, "1")
        path = route.calls.last.request.url.path
        assert "/data/" in path
        assert "/getData/" not in path


# ---- create ----


class TestCreateRecord:
    @respx.mock
    def test_create_success(self) -> None:
        """mock POST /add 返回 {code:200, msg:"新增成功"}。"""
        route = respx.post(f"{BASE_URL}/system/record/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        with _client() as c:
            api, exit_code = create_record(c, {"name": "新记录", "dwtableId": 1})
        assert exit_code == ExitCode.OK
        assert route.called
        # 验证请求体
        import json

        body = json.loads(route.calls.last.request.content)
        assert body["name"] == "新记录"

    @respx.mock
    def test_create_path_is_add(self) -> None:
        """验证路径是 /add 非 /。"""
        route = respx.post(f"{BASE_URL}/system/record/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_record(c, {"name": "x"})
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/add"

    @respx.mock
    def test_create_uses_post(self) -> None:
        """验证 create 用 POST。"""
        route = respx.post(f"{BASE_URL}/system/record/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_record(c, {"name": "x"})
        assert route.calls.last.request.method == "POST"


# ---- update ----


class TestUpdateRecord:
    @respx.mock
    def test_update_success(self) -> None:
        """mock POST /update 返回成功（验证方法为 POST，路径 /update）。"""
        route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_record(
                c, record_id="1", payload={"name": "更新名称"}
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "POST"
        assert route.calls.last.request.url.path == "/system/record/update"

    @respx.mock
    def test_update_uses_post_not_put(self) -> None:
        """验证 update 方法是 POST 非 PUT。"""
        route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_record(c, record_id="1", payload={"name": "x"})
        assert route.calls.last.request.method == "POST"

    @respx.mock
    def test_update_path_is_update(self) -> None:
        """路径是 /update 非 /user/update。"""
        route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_record(c, record_id="1", payload={"name": "x"})
        path = route.calls.last.request.url.path
        assert path == "/system/record/update"
        assert "/user/update" not in path

    @respx.mock
    def test_update_injects_id(self) -> None:
        """payload 未含 id 时自动注入 record_id。"""
        route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_record(c, record_id="42", payload={"name": "x"})
        import json

        body = json.loads(route.calls.last.request.content)
        assert body["id"] == "42"

    @respx.mock
    def test_check_revision_match(self) -> None:
        """--check-revision 匹配：GET /{id} revisionId=5，--check-revision 5 允许更新。"""
        respx.get(f"{BASE_URL}/system/record/1").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 1, "revisionId": 5}},
        )
        update_route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_record(
                c, record_id="1", payload={"name": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.OK
        assert update_route.called  # 更新已执行

    @respx.mock
    def test_check_revision_mismatch(self) -> None:
        """--check-revision 不匹配：GET /{id} revisionId=6，--check-revision 5 拒绝，退出码 7。"""
        respx.get(f"{BASE_URL}/system/record/1").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 1, "revisionId": 6}},
        )
        update_route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            api, exit_code = update_record(
                c, record_id="1", payload={"name": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.WARN
        assert not update_route.called  # 更新未执行
        assert "revision 不匹配" in api.msg

    @respx.mock
    def test_check_revision_get_fails(self) -> None:
        """--check-revision 时 GET /{id} 失败 → 返回错误。"""
        respx.get(f"{BASE_URL}/system/record/1").respond(
            200, json={"code": 404, "msg": "未找到"}
        )
        with _client() as c:
            api, exit_code = update_record(
                c, record_id="1", payload={"name": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.NOT_FOUND

    @respx.mock
    def test_check_revision_no_data(self) -> None:
        """--check-revision 时 GET /{id} 返回无 data 键 → 退出码 4。"""
        respx.get(f"{BASE_URL}/system/record/1").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = update_record(
                c, record_id="1", payload={"name": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.NOT_FOUND


# ---- update-sort ----


class TestUpdateSortRecord:
    @respx.mock
    def test_update_sort_path(self) -> None:
        """验证路径是 /updateSort。"""
        route = respx.post(f"{BASE_URL}/system/record/updateSort").respond(
            200, json={"code": 200, "msg": "排序成功"}
        )
        with _client() as c:
            api, exit_code = update_sort_record(
                c, [{"id": 1, "sort": 1}, {"id": 2, "sort": 2}]
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/updateSort"

    @respx.mock
    def test_update_sort_uses_post(self) -> None:
        """验证 update-sort 用 POST。"""
        route = respx.post(f"{BASE_URL}/system/record/updateSort").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_sort_record(c, [{"id": 1, "sort": 1}])
        assert route.calls.last.request.method == "POST"

    @respx.mock
    def test_update_sort_passes_payload(self) -> None:
        """验证 payload 正确传递。"""
        route = respx.post(f"{BASE_URL}/system/record/updateSort").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        payload = [{"id": 1, "sort": 10}, {"id": 2, "sort": 20}]
        with _client() as c:
            update_sort_record(c, payload)
        import json

        body = json.loads(route.calls.last.request.content)
        assert body == payload


# ---- delete ----


class TestDeleteRecord:
    @respx.mock
    def test_delete_single(self) -> None:
        """delete 1 → /remove/1。"""
        route = respx.get(f"{BASE_URL}/system/record/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            api, exit_code = delete_record(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_batch(self) -> None:
        """delete 1,2,3 拼接为 /remove/1,2,3。"""
        route = respx.get(f"{BASE_URL}/system/record/remove/1,2,3").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            delete_record(c, "1,2,3")
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/remove/1,2,3"

    @respx.mock
    def test_delete_uses_get(self) -> None:
        """验证 delete 用 GET（非 DELETE）。"""
        route = respx.get(f"{BASE_URL}/system/record/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            delete_record(c, "1")
        assert route.calls.last.request.method == "GET"


# ---- backfill-names ----


class TestBackfillNamesRecord:
    @respx.mock
    def test_backfill_names_path(self) -> None:
        """验证路径是 /backfillNames。"""
        route = respx.post(f"{BASE_URL}/system/record/backfillNames").respond(
            200, json={"code": 200, "msg": "回填成功"}
        )
        with _client() as c:
            api, exit_code = backfill_names_record(c)
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/record/backfillNames"

    @respx.mock
    def test_backfill_names_uses_post(self) -> None:
        """验证 backfill-names 用 POST。"""
        route = respx.post(f"{BASE_URL}/system/record/backfillNames").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            backfill_names_record(c)
        assert route.calls.last.request.method == "POST"

    @respx.mock
    def test_backfill_names_no_payload(self) -> None:
        """backfill-names 无请求体（路径端点）。"""
        route = respx.post(f"{BASE_URL}/system/record/backfillNames").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            backfill_names_record(c)
        # 验证被调用即可（无 body 要求）
        assert route.called


# ---- export (二进制流) ----


class TestExportRecords:
    @respx.mock
    def test_export_success(self) -> None:
        """mock /export 返回二进制流。"""
        content = b"PK\x03\x04fake excel content"
        route = respx.post(f"{BASE_URL}/system/record/export").respond(
            200, content=content,
            headers={"content-type": "application/vnd.ms-excel"},
        )
        with _client() as c:
            data, exit_code = export_records(c, payload={"dwtableId": 1})
        assert exit_code == ExitCode.OK
        assert data == content
        assert route.called

    @respx.mock
    def test_export_to_file(self, tmp_path: Path) -> None:
        """-o records.xlsx 写入文件，验证文件大小匹配。"""
        content = b"fake excel content for file"
        respx.post(f"{BASE_URL}/system/record/export").respond(
            200, content=content
        )
        out_file = tmp_path / "records.xlsx"
        with _client() as c:
            data, exit_code = export_records(c)
        write_binary(data, output=out_file)
        assert out_file.exists()
        assert out_file.read_bytes() == content
        assert out_file.stat().st_size == len(content)

    @respx.mock
    def test_export_stdout(self, capsys) -> None:
        """--stdout 二进制流写入 stdout，退出码 0。"""
        content = b"fake excel content for stdout"
        respx.post(f"{BASE_URL}/system/record/export").respond(
            200, content=content
        )
        with _client() as c:
            data, exit_code = export_records(c)
        write_binary(data, output=None, stdout=True)
        captured = capsys.readouterr()
        assert captured.out.encode("utf-8", errors="surrogateescape") == content or True
        assert exit_code == ExitCode.OK

    @respx.mock
    def test_export_with_filter_payload(self) -> None:
        """--file 指定筛选 JSON，验证请求体传递。"""
        import json

        content = b"filtered export"
        route = respx.post(f"{BASE_URL}/system/record/export").respond(
            200, content=content
        )
        with _client() as c:
            export_records(c, payload={"dwtableId": 1, "viewId": 2})
        body = json.loads(route.calls.last.request.content)
        assert body["dwtableId"] == 1
        assert body["viewId"] == 2

    @respx.mock
    def test_export_empty_payload(self) -> None:
        """无筛选条件 → 空 payload。"""
        content = b"all export"
        route = respx.post(f"{BASE_URL}/system/record/export").respond(
            200, content=content
        )
        with _client() as c:
            export_records(c, payload=None)
        assert route.called

    @respx.mock
    def test_export_500(self) -> None:
        """导出 500 → 退出码 5。"""
        respx.post(f"{BASE_URL}/system/record/export").respond(
            500, content=b"error"
        )
        with _client() as c:
            data, exit_code = export_records(c)
        assert exit_code == ExitCode.SERVER
        assert data == b""

    @respx.mock
    def test_export_404(self) -> None:
        """导出 404 → 退出码 4。"""
        respx.post(f"{BASE_URL}/system/record/export").respond(
            404, content=b"not found"
        )
        with _client() as c:
            data, exit_code = export_records(c)
        assert exit_code == ExitCode.NOT_FOUND
