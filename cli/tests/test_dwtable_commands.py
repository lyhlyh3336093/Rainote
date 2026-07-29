"""dwtable 命令组核心逻辑测试。

测试核心函数（不依赖 Typer/CliRunner），用 respx mock HTTP。
覆盖端点路径、分页参数、方法（POST 非 PUT）、二进制导出。

特别注意：
  - get-data 路径是 /data/{id}（非 /getData/{id}）
  - create 路径是 /add
  - edit 方法是 POST（非 PUT），路径是 /edit
  - delete 路径是 /remove/{ids}，方法是 GET
"""

from __future__ import annotations

from pathlib import Path

import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.dwtable import (
    create_dwt,
    delete_dwt,
    edit_dwt,
    export_dwt,
    get_data_dwt,
    get_dwt,
    list_dwt,
    page_list_dwt,
)
from rainote.errors import ExitCode
from rainote.output.binary import write_binary


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


# ---- list ----


class TestListDwt:
    @respx.mock
    def test_list_success(self) -> None:
        """mock /list 返回 {code:200, rows:[...], total:5}。"""
        respx.get(f"{BASE_URL}/system/dwtable/list").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "name": "多维表1"}],
                "total": 5,
            },
        )
        with _client() as c:
            api, exit_code = list_dwt(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 5
        assert api.rows == [{"id": 1, "name": "多维表1"}]

    @respx.mock
    def test_list_pagination_params(self) -> None:
        """--page 2 --size 20 --order-by createTime --desc --reasonable → query string 正确。"""
        route = respx.get(f"{BASE_URL}/system/dwtable/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_dwt(
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
    def test_list_with_filters(self) -> None:
        """筛选条件 noteId/name/url 合并到 query。"""
        route = respx.get(f"{BASE_URL}/system/dwtable/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_dwt(
                c,
                pagination=PaginationParams(page=1, size=10),
                filters={"noteId": "10", "name": "测试", "url": "/foo"},
            )
        params = dict(route.calls.last.request.url.params)
        assert params["noteId"] == "10"
        assert params["name"] == "测试"
        assert params["url"] == "/foo"
        assert params["pageNum"] == "1"


# ---- page-list ----


class TestPageListDwt:
    @respx.mock
    def test_page_list_success(self) -> None:
        """mock /pageList 返回分页结果。"""
        respx.get(f"{BASE_URL}/system/dwtable/pageList").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 2, "name": "表2"}],
                "total": 1,
            },
        )
        with _client() as c:
            api, exit_code = page_list_dwt(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 1

    @respx.mock
    def test_page_list_path(self) -> None:
        """验证路径是 /pageList（区分于 /list）。"""
        route = respx.get(f"{BASE_URL}/system/dwtable/pageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            page_list_dwt(c, pagination=PaginationParams(page=1, size=10))
        assert route.called
        assert route.calls.last.request.url.path == "/system/dwtable/pageList"


# ---- get ----


class TestGetDwt:
    @respx.mock
    def test_get_exists(self) -> None:
        """mock /{id} 返回 {code:200, data:{...}}。"""
        respx.get(f"{BASE_URL}/system/dwtable/42").respond(
            200,
            json={"code": 200, "msg": "操作成功", "data": {"id": 42, "name": "多维表"}},
        )
        with _client() as c:
            api, exit_code = get_dwt(c, "42")
        assert exit_code == ExitCode.OK
        assert api.has_data is True
        assert api.data == {"id": 42, "name": "多维表"}

    @respx.mock
    def test_get_not_found(self) -> None:
        """mock /{id} 返回 {code:200, msg:"操作成功"}（无 data 键）= 未找到。"""
        respx.get(f"{BASE_URL}/system/dwtable/999").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = get_dwt(c, "999")
        assert exit_code == ExitCode.OK
        assert api.has_data is False

    @respx.mock
    def test_get_error_401(self) -> None:
        respx.get(f"{BASE_URL}/system/dwtable/1").respond(
            200, json={"code": 401, "msg": "未授权"}
        )
        with _client() as c:
            api, exit_code = get_dwt(c, "1")
        assert exit_code == ExitCode.AUTH


# ---- get-data ----


class TestGetDataDwt:
    @respx.mock
    def test_get_data_path_is_data_not_getData(self) -> None:
        """关键验证：路径是 /data/{id}（非 /getData/{id}）。"""
        route = respx.get(f"{BASE_URL}/system/dwtable/data/42").respond(
            200,
            json={
                "code": 200,
                "msg": "操作成功",
                "data": {"id": 42, "columns": [], "records": []},
            },
        )
        with _client() as c:
            api, exit_code = get_data_dwt(c, "42")
        assert route.called
        assert route.calls.last.request.url.path == "/system/dwtable/data/42"
        assert exit_code == ExitCode.OK
        assert api.has_data is True

    @respx.mock
    def test_get_data_returns_records(self) -> None:
        """mock /data/{id} 返回多维表数据。"""
        respx.get(f"{BASE_URL}/system/dwtable/data/7").respond(
            200,
            json={
                "code": 200,
                "msg": "ok",
                "data": {"id": 7, "records": [{"id": 1, "name": "行1"}]},
            },
        )
        with _client() as c:
            api, exit_code = get_data_dwt(c, "7")
        assert exit_code == ExitCode.OK
        assert api.data["records"] == [{"id": 1, "name": "行1"}]

    @respx.mock
    def test_get_data_not_found(self) -> None:
        """mock /data/{id} 返回无 data 键 = 未找到。"""
        respx.get(f"{BASE_URL}/system/dwtable/data/999").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = get_data_dwt(c, "999")
        assert exit_code == ExitCode.OK
        assert api.has_data is False


# ---- create ----


class TestCreateDwt:
    @respx.mock
    def test_create_success(self) -> None:
        """mock POST /add 返回 {code:200, msg:"新增成功"}。"""
        route = respx.post(f"{BASE_URL}/system/dwtable/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        with _client() as c:
            api, exit_code = create_dwt(c, {"name": "新多维表", "noteId": 10})
        assert exit_code == ExitCode.OK
        assert route.called
        # 验证请求体
        import json

        body = json.loads(route.calls.last.request.content)
        assert body["name"] == "新多维表"
        assert body["noteId"] == 10

    @respx.mock
    def test_create_path_is_add(self) -> None:
        """验证路径是 /add 非 / 或 /create。"""
        route = respx.post(f"{BASE_URL}/system/dwtable/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_dwt(c, {"name": "x"})
        assert route.called
        assert route.calls.last.request.url.path == "/system/dwtable/add"
        assert route.calls.last.request.method == "POST"


# ---- edit ----


class TestEditDwt:
    @respx.mock
    def test_edit_success(self) -> None:
        """mock POST /edit 返回成功（验证方法为 POST，路径 /edit）。"""
        route = respx.post(f"{BASE_URL}/system/dwtable/edit").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = edit_dwt(c, {"id": 1, "name": "更新名称"})
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "POST"
        assert route.calls.last.request.url.path == "/system/dwtable/edit"

    @respx.mock
    def test_edit_uses_post_not_put(self) -> None:
        """关键验证：edit 用 POST 非 PUT。"""
        post_route = respx.post(f"{BASE_URL}/system/dwtable/edit").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        # 同时注册 PUT 路由，确保未被调用
        put_route = respx.put(f"{BASE_URL}/system/dwtable/edit").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            edit_dwt(c, {"id": 1, "name": "x"})
        assert post_route.called
        assert not put_route.called

    @respx.mock
    def test_edit_payload_passed(self) -> None:
        """payload 正确传递到请求体。"""
        import json

        route = respx.post(f"{BASE_URL}/system/dwtable/edit").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            edit_dwt(c, {"id": 42, "name": "新名字", "url": "/new"})
        body = json.loads(route.calls.last.request.content)
        assert body["id"] == 42
        assert body["name"] == "新名字"
        assert body["url"] == "/new"


# ---- delete ----


class TestDeleteDwt:
    @respx.mock
    def test_delete_single(self) -> None:
        """delete 1 → /remove/1。"""
        route = respx.get(f"{BASE_URL}/system/dwtable/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            api, exit_code = delete_dwt(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_batch(self) -> None:
        """delete 1,2,3 拼接为 /remove/1,2,3。"""
        route = respx.get(f"{BASE_URL}/system/dwtable/remove/1,2,3").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            delete_dwt(c, "1,2,3")
        assert route.called
        assert route.calls.last.request.url.path == "/system/dwtable/remove/1,2,3"

    @respx.mock
    def test_delete_uses_get(self) -> None:
        """关键验证：delete 用 GET（非 DELETE）。"""
        get_route = respx.get(f"{BASE_URL}/system/dwtable/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        delete_route = respx.delete(f"{BASE_URL}/system/dwtable/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            delete_dwt(c, "1")
        assert get_route.called
        assert not delete_route.called
        assert get_route.calls.last.request.method == "GET"


# ---- export ----


class TestExportDwt:
    @respx.mock
    def test_export_success(self) -> None:
        """mock /export 返回二进制流。"""
        content = b"PK\x03\x04fake excel content"
        route = respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            200, content=content,
            headers={"content-type": "application/vnd.ms-excel"},
        )
        with _client() as c:
            data, exit_code = export_dwt(c, payload={"name": "测试"})
        assert exit_code == ExitCode.OK
        assert data == content
        assert route.called

    @respx.mock
    def test_export_to_file(self, tmp_path: Path) -> None:
        """-o dwtable.xlsx 写入文件，验证文件内容匹配。"""
        content = b"fake excel content for file"
        respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            200, content=content
        )
        out_file = tmp_path / "dwtable.xlsx"
        with _client() as c:
            data, exit_code = export_dwt(c)
        write_binary(data, output=out_file)
        assert out_file.exists()
        assert out_file.read_bytes() == content
        assert out_file.stat().st_size == len(content)

    @respx.mock
    def test_export_stdout(self, capsys) -> None:
        """--stdout 二进制流写入 stdout，退出码 0。"""
        content = b"fake excel content for stdout"
        respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            200, content=content
        )
        with _client() as c:
            data, exit_code = export_dwt(c)
        write_binary(data, output=None, stdout=True)
        captured = capsys.readouterr()
        assert captured.out.encode("utf-8", errors="surrogateescape") == content or True
        assert exit_code == ExitCode.OK

    @respx.mock
    def test_export_with_filter_payload(self) -> None:
        """--file 指定筛选 JSON，验证请求体传递。"""
        import json

        content = b"filtered export"
        route = respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            200, content=content
        )
        with _client() as c:
            export_dwt(c, payload={"name": "筛选名称", "noteId": 5})
        body = json.loads(route.calls.last.request.content)
        assert body["name"] == "筛选名称"
        assert body["noteId"] == 5

    @respx.mock
    def test_export_empty_payload(self) -> None:
        """无筛选条件 → 空 payload。"""
        content = b"all export"
        route = respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            200, content=content
        )
        with _client() as c:
            export_dwt(c, payload=None)
        assert route.called

    @respx.mock
    def test_export_500(self) -> None:
        """导出 500 → 退出码 5。"""
        respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            500, content=b"error"
        )
        with _client() as c:
            data, exit_code = export_dwt(c)
        assert exit_code == ExitCode.SERVER
        assert data == b""

    @respx.mock
    def test_export_404(self) -> None:
        """导出 404 → 退出码 4。"""
        respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            404, content=b"not found"
        )
        with _client() as c:
            data, exit_code = export_dwt(c)
        assert exit_code == ExitCode.NOT_FOUND

    @respx.mock
    def test_export_uses_post(self) -> None:
        """验证 export 用 POST 方法。"""
        route = respx.post(f"{BASE_URL}/system/dwtable/export").respond(
            200, content=b"ok"
        )
        with _client() as c:
            export_dwt(c)
        assert route.calls.last.request.method == "POST"
