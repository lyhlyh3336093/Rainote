"""note 回收站命令核心逻辑测试。

覆盖：garbage list / clear / recover 端点路径与响应处理。
"""

from __future__ import annotations

import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.note.garbage import (
    garbage_clear,
    garbage_list,
    garbage_recover,
)
from rainote.errors import ExitCode


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


class TestGarbageList:
    @respx.mock
    def test_garbage_list_success(self) -> None:
        respx.get(f"{BASE_URL}/system/note/garbageList").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "title": "已删除笔记"}],
                "total": 1,
            },
        )
        with _client() as c:
            api, exit_code = garbage_list(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 1

    @respx.mock
    def test_garbage_list_path(self) -> None:
        """验证路径是 /garbageList。"""
        route = respx.get(f"{BASE_URL}/system/note/garbageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            garbage_list(c)
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/garbageList"


class TestGarbageClear:
    @respx.mock
    def test_clear_single(self) -> None:
        """garbage clear 1 → /clearGarbage/1（验证路径非 /remove）。"""
        route = respx.get(f"{BASE_URL}/system/note/clearGarbage/1").respond(
            200, json={"code": 200, "msg": "清除成功"}
        )
        with _client() as c:
            api, exit_code = garbage_clear(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/clearGarbage/1"

    @respx.mock
    def test_clear_batch(self) -> None:
        """garbage clear 1,2 → /clearGarbage/1,2。"""
        route = respx.get(f"{BASE_URL}/system/note/clearGarbage/1,2").respond(
            200, json={"code": 200, "msg": "清除成功"}
        )
        with _client() as c:
            garbage_clear(c, "1,2")
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/clearGarbage/1,2"

    @respx.mock
    def test_clear_not_remove_path(self) -> None:
        """验证路径是 /clearGarbage 非 /remove。"""
        route = respx.get(f"{BASE_URL}/system/note/clearGarbage/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            garbage_clear(c, "1")
        assert "/clearGarbage/" in route.calls.last.request.url.path
        assert "/remove/" not in route.calls.last.request.url.path


class TestGarbageRecover:
    @respx.mock
    def test_recover_single(self) -> None:
        """garbage recover 1 → /recoverNote/1。"""
        route = respx.get(f"{BASE_URL}/system/note/recoverNote/1").respond(
            200, json={"code": 200, "msg": "恢复成功"}
        )
        with _client() as c:
            api, exit_code = garbage_recover(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/recoverNote/1"

    @respx.mock
    def test_recover_batch(self) -> None:
        route = respx.get(f"{BASE_URL}/system/note/recoverNote/1,2").respond(
            200, json={"code": 200, "msg": "恢复成功"}
        )
        with _client() as c:
            garbage_recover(c, "1,2")
        assert route.called
        assert route.calls.last.request.url.path == "/system/note/recoverNote/1,2"

    @respx.mock
    def test_recover_path_is_recoverNote(self) -> None:
        """验证路径是 /recoverNote。"""
        route = respx.get(f"{BASE_URL}/system/note/recoverNote/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            garbage_recover(c, "1")
        assert "/recoverNote/" in route.calls.last.request.url.path
