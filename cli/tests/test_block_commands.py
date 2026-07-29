"""block 命令组核心逻辑测试。

测试核心函数（不依赖 Typer/CliRunner），用 respx mock HTTP。
覆盖端点路径、分页参数、update-batch 数组 body、delete GET 方法、export 二进制流。
"""

from __future__ import annotations

import json

import respx

from rainote.client.http import RainoteClient
from rainote.client.pagination import PaginationParams
from rainote.commands.block import (
    create_block,
    delete_block,
    export_blocks,
    get_block,
    link_to_dwtable,
    list_blocks,
    remove_link,
    update_batch_blocks,
    update_block,
)
from rainote.errors import ExitCode


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


# ---- list ----


class TestListBlocks:
    @respx.mock
    def test_list_success(self) -> None:
        """mock /list 返回 {code:200, rows:[...], total:3}。"""
        respx.get(f"{BASE_URL}/system/block/list").respond(
            200,
            json={
                "code": 200,
                "msg": "查询成功",
                "rows": [{"id": 1, "name": "块1"}],
                "total": 3,
            },
        )
        with _client() as c:
            api, exit_code = list_blocks(
                c, pagination=PaginationParams(page=1, size=10)
            )
        assert exit_code == ExitCode.OK
        assert api.is_page is True
        assert api.total == 3
        assert api.rows == [{"id": 1, "name": "块1"}]

    @respx.mock
    def test_list_pagination_params(self) -> None:
        """--page 2 --size 20 --order-by createTime --desc --reasonable → query string 正确。"""
        route = respx.get(f"{BASE_URL}/system/block/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_blocks(
                c,
                pagination=PaginationParams(
                    page=2, size=20, order_by="createTime",
                    is_asc="desc", reasonable=True,
                ),
            )
        params = dict(route.calls.last.request.url.params)
        assert params["pageNum"] == "2"
        assert params["pageSize"] == "20"
        assert params["orderByColumn"] == "createTime"
        assert params["isAsc"] == "desc"
        assert params["reasonable"] == "true"

    @respx.mock
    def test_list_path_is_list(self) -> None:
        """验证路径是 /system/block/list。"""
        route = respx.get(f"{BASE_URL}/system/block/list").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        with _client() as c:
            list_blocks(c, pagination=PaginationParams(page=1, size=10))
        assert route.calls.last.request.url.path == "/system/block/list"


# ---- get ----


class TestGetBlock:
    @respx.mock
    def test_get_exists(self) -> None:
        """mock /{id} 返回 {code:200, data:{...}}。"""
        respx.get(f"{BASE_URL}/system/block/42").respond(
            200,
            json={"code": 200, "msg": "操作成功", "data": {"id": 42, "name": "块"}},
        )
        with _client() as c:
            api, exit_code = get_block(c, "42")
        assert exit_code == ExitCode.OK
        assert api.has_data is True
        assert api.data == {"id": 42, "name": "块"}

    @respx.mock
    def test_get_path_with_id(self) -> None:
        """验证路径是 /system/block/{id}。"""
        route = respx.get(f"{BASE_URL}/system/block/99").respond(
            200, json={"code": 200, "msg": "ok", "data": {"id": 99}}
        )
        with _client() as c:
            get_block(c, "99")
        assert route.calls.last.request.url.path == "/system/block/99"


# ---- create ----


class TestCreateBlock:
    @respx.mock
    def test_create_success(self) -> None:
        """mock POST /add 返回 {code:200, msg:"新增成功"}。"""
        route = respx.post(f"{BASE_URL}/system/block/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        with _client() as c:
            api, exit_code = create_block(c, {"name": "新块", "type": "text"})
        assert exit_code == ExitCode.OK
        assert route.called
        body = json.loads(route.calls.last.request.content)
        assert body["name"] == "新块"

    @respx.mock
    def test_create_path_is_add(self) -> None:
        """验证路径是 /add。"""
        route = respx.post(f"{BASE_URL}/system/block/add").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            create_block(c, {"name": "x"})
        assert route.calls.last.request.url.path == "/system/block/add"


# ---- update ----


class TestUpdateBlock:
    @respx.mock
    def test_update_success(self) -> None:
        """mock POST /update 返回成功（验证方法为 POST，路径 /update）。"""
        route = respx.post(f"{BASE_URL}/system/block/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        with _client() as c:
            api, exit_code = update_block(c, {"id": 1, "name": "更新名称"})
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "POST"
        assert route.calls.last.request.url.path == "/system/block/update"

    @respx.mock
    def test_update_not_put(self) -> None:
        """验证 update 用 POST 非 PUT。"""
        route = respx.post(f"{BASE_URL}/system/block/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_block(c, {"id": 1, "name": "x"})
        assert route.calls.last.request.method == "POST"


# ---- update-batch ----


class TestUpdateBatchBlocks:
    @respx.mock
    def test_update_batch_success(self) -> None:
        """mock POST /updateBatch 返回成功。"""
        route = respx.post(f"{BASE_URL}/system/block/updateBatch").respond(
            200, json={"code": 200, "msg": "批量修改成功"}
        )
        with _client() as c:
            api, exit_code = update_batch_blocks(
                c, [{"id": 1, "name": "块1"}, {"id": 2, "name": "块2"}]
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/block/updateBatch"

    @respx.mock
    def test_update_batch_body_is_array(self) -> None:
        """验证 body 是数组（非对象）—— update-batch 的关键约束。"""
        route = respx.post(f"{BASE_URL}/system/block/updateBatch").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        blocks = [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]
        with _client() as c:
            update_batch_blocks(c, blocks)
        body = json.loads(route.calls.last.request.content)
        assert isinstance(body, list)  # 关键：body 是数组
        assert not isinstance(body, dict)  # 非对象
        assert len(body) == 2
        assert body == blocks

    @respx.mock
    def test_update_batch_empty_array(self) -> None:
        """空数组也是合法 body。"""
        route = respx.post(f"{BASE_URL}/system/block/updateBatch").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            update_batch_blocks(c, [])
        body = json.loads(route.calls.last.request.content)
        assert isinstance(body, list)
        assert body == []


# ---- delete ----


class TestDeleteBlock:
    @respx.mock
    def test_delete_single(self) -> None:
        """delete 1 → /remove/1。"""
        route = respx.get(f"{BASE_URL}/system/block/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            api, exit_code = delete_block(c, "1")
        assert exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_batch(self) -> None:
        """delete 1,2,3 拼接为 /remove/1,2,3。"""
        route = respx.get(f"{BASE_URL}/system/block/remove/1,2,3").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        with _client() as c:
            delete_block(c, "1,2,3")
        assert route.called
        assert route.calls.last.request.url.path == "/system/block/remove/1,2,3"

    @respx.mock
    def test_delete_uses_get(self) -> None:
        """验证 delete 用 GET（非 DELETE）。"""
        route = respx.get(f"{BASE_URL}/system/block/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            delete_block(c, "1")
        assert route.calls.last.request.method == "GET"


# ---- link-to-dwtable ----


class TestLinkToDwtable:
    @respx.mock
    def test_link_success(self) -> None:
        """mock POST /linkToDwtable 返回成功。"""
        route = respx.post(f"{BASE_URL}/system/block/linkToDwtable").respond(
            200, json={"code": 200, "msg": "关联成功"}
        )
        with _client() as c:
            api, exit_code = link_to_dwtable(
                c, {"blockId": 1, "dwtableId": 10}
            )
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/block/linkToDwtable"

    @respx.mock
    def test_link_payload_passed(self) -> None:
        """验证 NoteBlockVo payload 透传。"""
        route = respx.post(f"{BASE_URL}/system/block/linkToDwtable").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            link_to_dwtable(c, {"blockId": 5, "dwtableId": 7})
        body = json.loads(route.calls.last.request.content)
        assert body["blockId"] == 5
        assert body["dwtableId"] == 7


# ---- remove-link ----


class TestRemoveLink:
    @respx.mock
    def test_remove_link_success(self) -> None:
        """mock POST /removeLink 返回成功。"""
        route = respx.post(f"{BASE_URL}/system/block/removeLink").respond(
            200, json={"code": 200, "msg": "移除关联成功"}
        )
        with _client() as c:
            api, exit_code = remove_link(c, {"blockId": 1})
        assert exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.url.path == "/system/block/removeLink"

    @respx.mock
    def test_remove_link_payload_passed(self) -> None:
        """验证 payload 透传。"""
        route = respx.post(f"{BASE_URL}/system/block/removeLink").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        with _client() as c:
            remove_link(c, {"blockId": 9})
        body = json.loads(route.calls.last.request.content)
        assert body["blockId"] == 9


# ---- export ----


class TestExportBlocks:
    @respx.mock
    def test_export_success(self) -> None:
        """mock /export 返回二进制流。"""
        content = b"PK\x03\x04fake excel content"
        route = respx.post(f"{BASE_URL}/system/block/export").respond(
            200,
            content=content,
            headers={"content-type": "application/vnd.ms-excel"},
        )
        with _client() as c:
            data, exit_code = export_blocks(c, payload={"name": "测试"})
        assert exit_code == ExitCode.OK
        assert data == content
        assert route.called

    @respx.mock
    def test_export_empty_payload(self) -> None:
        """无筛选条件 → 空 payload。"""
        content = b"all export"
        route = respx.post(f"{BASE_URL}/system/block/export").respond(
            200, content=content
        )
        with _client() as c:
            export_blocks(c, payload=None)
        assert route.called

    @respx.mock
    def test_export_with_filter_payload(self) -> None:
        """--file 指定筛选 JSON，验证请求体传递。"""
        content = b"filtered export"
        route = respx.post(f"{BASE_URL}/system/block/export").respond(
            200, content=content
        )
        with _client() as c:
            export_blocks(c, payload={"name": "筛选名称", "type": "text"})
        body = json.loads(route.calls.last.request.content)
        assert body["name"] == "筛选名称"
        assert body["type"] == "text"

    @respx.mock
    def test_export_500(self) -> None:
        """导出 500 → 退出码 5。"""
        respx.post(f"{BASE_URL}/system/block/export").respond(500, content=b"error")
        with _client() as c:
            data, exit_code = export_blocks(c)
        assert exit_code == ExitCode.SERVER
        assert data == b""

    @respx.mock
    def test_export_404(self) -> None:
        """导出 404 → 退出码 4。"""
        respx.post(f"{BASE_URL}/system/block/export").respond(
            404, content=b"not found"
        )
        with _client() as c:
            data, exit_code = export_blocks(c)
        assert exit_code == ExitCode.NOT_FOUND
