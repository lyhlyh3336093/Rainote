"""notelink 命令组核心逻辑测试。

覆盖：list/get/cell/by-note/create/update/delete/export 端点路径与方法。
特别注意：create 用 POST 根路径，update 用 PUT 根路径，delete 用 DELETE 方法。
"""

from __future__ import annotations

from pathlib import Path

import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.notelink import (
    by_note_notelinks,
    cell_notelinks,
    create_notelink,
    delete_notelinks,
    export_notelinks,
    get_notelink,
    list_notelinks,
    update_notelink,
)
from rainote.errors import ExitCode
from rainote.output.binary import write_binary


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


class TestListNotelinks:
    @respx.mock
    def test_list_success(self) -> None:
        respx.get(f"{BASE_URL}/system/notelink/list").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "linkNoteId": 10}],
                "total": 1,
            },
        )
        with _client() as c:
            api, exit_code = list_notelinks(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 1

    @respx.mock
    def test_list_pagination(self) -> None:
        route = respx.get(f"{BASE_URL}/system/notelink/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_notelinks(c, pagination=PaginationParams(page=2, size=20))
        params = dict(route.calls.last.request.url.params)
        assert params["pageNum"] == "2"
        assert params["pageSize"] == "20"


class TestGetNotelink:
    @respx.mock
    def test_get_exists(self) -> None:
        respx.get(f"{BASE_URL}/system/notelink/42").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 42, "linkNoteId": 10}},
        )
        with _client() as c:
            api, exit_code = get_notelink(c, "42")
        assert exit_code == ExitCode.OK
        assert api.has_data is True
        assert api.data["linkNoteId"] == 10

    @respx.mock
    def test_get_not_found(self) -> None:
        respx.get(f"{BASE_URL}/system/notelink/999").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = get_notelink(c, "999")
        assert exit_code == ExitCode.OK
        assert api.has_data is False


class TestCellNotelinks:
    @respx.mock
    def test_cell_query(self) -> None:
        """mock /cell/{linkColumnId}/{linkItemId} 返回列表。"""
        route = respx.get(
            f"{BASE_URL}/system/notelink/cell/5/100"
        ).respond(
            200,
            json={
                "code": 200,
                "msg": "ok",
                "data": [{"id": 1, "linkColumnId": 5, "linkItemId": 100}],
            },
        )
        with _client() as c:
            api, exit_code = cell_notelinks(
                c, link_column_id="5", link_item_id="100"
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/notelink/cell/5/100"

    @respx.mock
    def test_cell_path_two_params(self) -> None:
        """验证路径含两个路径参数。"""
        route = respx.get(
            f"{BASE_URL}/system/notelink/cell/7/200"
        ).respond(200, json={"code": 200, "msg": "ok", "data": []})
        with _client() as c:
            cell_notelinks(c, link_column_id="7", link_item_id="200")
        assert route.called


class TestByNoteNotelinks:
    @respx.mock
    def test_by_note_query(self) -> None:
        """mock /byNote/{noteId} 返回列表。"""
        route = respx.get(f"{BASE_URL}/system/notelink/byNote/10").respond(
            200,
            json={
                "code": 200,
                "msg": "ok",
                "data": [{"id": 1, "linkNoteId": 10}],
            },
        )
        with _client() as c:
            api, exit_code = by_note_notelinks(c, note_id="10")
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/notelink/byNote/10"


class TestCreateNotelink:
    @respx.mock
    def test_create_root_path(self) -> None:
        """create 用 POST 根路径（无 /add）。"""
        import json

        route = respx.post(f"{BASE_URL}/system/notelink").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        payload = {
            "linkColumnId": 5,
            "linkItemId": 100,
            "linkNoteId": 10,
            "targetNoteId": 20,
        }
        with _client() as c:
            api, exit_code = create_notelink(c, payload)
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/notelink"
        body = json.loads(route.calls.last.request.content)
        assert body["linkColumnId"] == 5

    @respx.mock
    def test_create_not_add_path(self) -> None:
        """验证路径是根路径 /system/notelink，非 /system/notelink/add。"""
        route = respx.post(f"{BASE_URL}/system/notelink").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_notelink(c, {"linkNoteId": 1})
        assert route.calls.last.request.url.path == "/system/notelink"
        assert "/add" not in route.calls.last.request.url.path


class TestUpdateNotelink:
    @respx.mock
    def test_update_put_root_path(self) -> None:
        """update 用 PUT 根路径（非 POST /update）。"""
        route = respx.put(f"{BASE_URL}/system/notelink").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_notelink(c, {"id": 1, "linkNoteId": 10})
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "PUT"
        assert route.calls.last.request.url.path == "/system/notelink"


class TestDeleteNotelinks:
    @respx.mock
    def test_delete_single(self) -> None:
        """delete 用 DELETE 方法（非 GET）。"""
        route = respx.delete(f"{BASE_URL}/system/notelink/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            api, exit_code = delete_notelinks(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "DELETE"

    @respx.mock
    def test_delete_batch(self) -> None:
        """delete 1,2,3 → DELETE /1,2,3。"""
        route = respx.delete(f"{BASE_URL}/system/notelink/1,2,3").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            delete_notelinks(c, "1,2,3")
        assert route.called
        assert route.calls.last.request.url.path == "/system/notelink/1,2,3"

    @respx.mock
    def test_delete_method_is_delete(self) -> None:
        """验证 delete 方法是 DELETE（非 GET /remove）。"""
        route = respx.delete(f"{BASE_URL}/system/notelink/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            delete_notelinks(c, "1")
        assert route.calls.last.request.method == "DELETE"
        assert "/remove/" not in route.calls.last.request.url.path


class TestExportNotelinks:
    @respx.mock
    def test_export_success(self) -> None:
        content = b"PK\x03\x04fake excel"
        route = respx.post(f"{BASE_URL}/system/notelink/export").respond(
            200, content=content
        )
        with _client() as c:
            data, exit_code = export_notelinks(c, payload={"linkNoteId": 1})
        assert exit_code == ExitCode.OK
        assert data == content
        assert route.called

    @respx.mock
    def test_export_to_file(self, tmp_path: Path) -> None:
        content = b"notelink export content"
        respx.post(f"{BASE_URL}/system/notelink/export").respond(200, content=content)
        out_file = tmp_path / "notelinks.xlsx"
        with _client() as c:
            data, exit_code = export_notelinks(c)
        write_binary(data, output=out_file)
        assert out_file.exists()
        assert out_file.read_bytes() == content

    @respx.mock
    def test_export_stdout(self, capsys) -> None:
        content = b"stdout notelink export"
        respx.post(f"{BASE_URL}/system/notelink/export").respond(200, content=content)
        with _client() as c:
            data, _ = export_notelinks(c)
        write_binary(data, output=None, stdout=True)
        captured = capsys.readouterr()
        assert "stdout notelink export" in captured.out
