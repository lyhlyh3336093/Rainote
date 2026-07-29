"""note CRUD 命令核心逻辑测试。

测试核心函数（不依赖 Typer/CliRunner），用 respx mock HTTP。
覆盖端点路径、分页参数、--check-revision 冲突检测。
"""

from __future__ import annotations

import httpx
import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.note.crud import (
    create_note,
    delete_notes,
    get_note,
    list_notes,
    update_note,
)
from rainote.errors import ExitCode


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


# ---- list ----


class TestListNotes:
    @respx.mock
    def test_list_success(self) -> None:
        """mock /pageList 返回 {code:200, rows:[...], total:5}。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "title": "笔记1"}],
                "total": 5,
            },
        )
        with _client() as c:
            api, exit_code = list_notes(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 5
        assert api.rows == [{"id": 1, "title": "笔记1"}]

    @respx.mock
    def test_list_pagination_params(self) -> None:
        """--page 2 --size 20 --order-by createTime --desc --reasonable → query string 正确。"""
        route = respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_notes(
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
        """筛选条件合并到 query。"""
        route = respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_notes(
                c,
                pagination=PaginationParams(page=1, size=10),
                filters={"title": "测试"},
            )
        params = dict(route.calls.last.request.url.params)
        assert params["title"] == "测试"
        assert params["pageNum"] == "1"


# ---- get ----


class TestGetNote:
    @respx.mock
    def test_get_exists(self) -> None:
        """mock /{id} 返回 {code:200, data:{...}}。"""
        respx.get(f"{BASE_URL}/system/note/42").respond(
            200, json={"code": 200, "msg": "操作成功", "data": {"id": 42, "title": "笔记"}}
        )
        with _client() as c:
            api, exit_code = get_note(c, "42")
        assert exit_code == ExitCode.OK
        assert api.has_data is True
        assert api.data == {"id": 42, "title": "笔记"}

    @respx.mock
    def test_get_not_found(self) -> None:
        """mock /{id} 返回 {code:200, msg:"操作成功"}（无 data 键）= 未找到。"""
        respx.get(f"{BASE_URL}/system/note/999").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = get_note(c, "999")
        assert exit_code == ExitCode.OK
        assert api.has_data is False

    @respx.mock
    def test_get_error_401(self) -> None:
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200, json={"code": 401, "msg": "未授权"}
        )
        with _client() as c:
            api, exit_code = get_note(c, "1")
        assert exit_code == ExitCode.AUTH


# ---- create ----


class TestCreateNote:
    @respx.mock
    def test_create_success(self) -> None:
        """mock POST /add 返回 {code:200, msg:"新增成功"}。"""
        route = respx.post(f"{BASE_URL}/system/note/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        with _client() as c:
            api, exit_code = create_note(c, {"title": "新笔记", "content": "内容"})
        assert exit_code == ExitCode.OK
        assert route.called
        # 验证请求体
        import json

        body = json.loads(route.calls.last.request.content)
        assert body["title"] == "新笔记"

    @respx.mock
    def test_create_path_is_add(self) -> None:
        """验证路径是 /add 非 /。"""
        route = respx.post(f"{BASE_URL}/system/note/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_note(c, {"title": "x"})
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/add"


# ---- update ----


class TestUpdateNote:
    @respx.mock
    def test_update_success(self) -> None:
        """mock POST /user/update 返回成功（验证方法为 POST，路径 /user/update）。"""
        route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_note(
                c, note_id="1", payload={"title": "更新标题"}
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "POST"
        assert route.calls.last.request.url.path == "/system/note/user/update"

    @respx.mock
    def test_update_injects_id(self) -> None:
        """payload 未含 id 时自动注入 note_id。"""
        route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_note(c, note_id="42", payload={"title": "x"})
        import json

        body = json.loads(route.calls.last.request.content)
        assert body["id"] == "42"

    @respx.mock
    def test_check_revision_match(self) -> None:
        """--check-revision 匹配：GET /{id} revisionId=5，--check-revision 5 允许更新。"""
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 1, "revisionId": 5}},
        )
        update_route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_note(
                c, note_id="1", payload={"title": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.OK
        assert update_route.called  # 更新已执行

    @respx.mock
    def test_check_revision_mismatch(self) -> None:
        """--check-revision 不匹配：GET /{id} revisionId=6，--check-revision 5 拒绝，退出码 7。"""
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 1, "revisionId": 6}},
        )
        update_route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            api, exit_code = update_note(
                c, note_id="1", payload={"title": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.WARN
        assert not update_route.called  # 更新未执行
        assert "revision 不匹配" in api.msg

    @respx.mock
    def test_check_revision_get_fails(self) -> None:
        """--check-revision 时 GET /{id} 失败 → 返回错误。"""
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200, json={"code": 404, "msg": "未找到"}
        )
        with _client() as c:
            api, exit_code = update_note(
                c, note_id="1", payload={"title": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.NOT_FOUND

    @respx.mock
    def test_check_revision_no_data(self) -> None:
        """--check-revision 时 GET /{id} 返回无 data 键 → 退出码 4。"""
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200, json={"code": 200, "msg": "操作成功"}
        )
        with _client() as c:
            api, exit_code = update_note(
                c, note_id="1", payload={"title": "x"}, check_revision=5
            )
        assert exit_code == ExitCode.NOT_FOUND


# ---- delete ----


class TestDeleteNotes:
    @respx.mock
    def test_delete_single(self) -> None:
        """delete 1 → /remove/1。"""
        route = respx.get(f"{BASE_URL}/system/note/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            api, exit_code = delete_notes(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_batch(self) -> None:
        """delete 1,2,3 拼接为 /remove/1,2,3。"""
        route = respx.get(f"{BASE_URL}/system/note/remove/1,2,3").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            delete_notes(c, "1,2,3")
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/remove/1,2,3"

    @respx.mock
    def test_delete_uses_get(self) -> None:
        """验证 delete 用 GET（非 DELETE）。"""
        route = respx.get(f"{BASE_URL}/system/note/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            delete_notes(c, "1")
        assert route.calls.last.request.method == "GET"
