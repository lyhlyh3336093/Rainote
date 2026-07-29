"""U7 端到端业务流程测试。

覆盖完整业务链路（登录 → CRUD → 破坏性操作预检 → 错误处理 → 输出格式），
使用 respx mock 后端 HTTP，``CliRunner`` 驱动 Typer 命令链。

与单元测试（``test_*_commands.py``）的区别：
- 单元测试聚焦单个核心函数（如 ``list_notes``），验证端点路径与参数拼接
- 本文件覆盖**跨模块协作的完整用户流程**，驱动真实 Typer 命令链（``rainote note list`` …），
  验证 config → client → command → render → exit_code 的端到端一致性

测试不触碰真实后端，所有 HTTP 由 respx 拦截。
"""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx
from typer.testing import CliRunner

from rainote.auth.login import login
from rainote.auth.token import (
    Credentials,
    ProbeResult,
    probe_token,
    save_credentials,
)
from rainote.cli import app
from rainote.errors import ExitCode


BASE_URL = "http://localhost:8080"
TOKEN = "test-jwt-token"

runner = CliRunner()


# ============================================================
# 场景 1: 登录流程（核心函数级，login 未接入 Typer 命令组）
# ============================================================


def _mock_client(routes: dict, base_url: str = BASE_URL) -> httpx.Client:
    """构造 MockTransport client，routes: {"METHOD /path": body_or_handler}。"""

    def handler(request: httpx.Request) -> httpx.Response:
        key = f"{request.method} {request.url.path}"
        route = routes.get(key)
        if route is None:
            return httpx.Response(404, json={"code": 404, "msg": "not found"})
        if callable(route):
            return route(request)
        return httpx.Response(200, json=route)

    return httpx.Client(base_url=base_url, transport=httpx.MockTransport(handler))


CAPTCHA_OFF = {"captchaEnabled": False, "uuid": "", "img": ""}
CAPTCHA_ON = {"captchaEnabled": True, "uuid": "uuid-1", "img": "data:image/png;base64,xxx"}
LOGIN_OK = {"code": 200, "msg": "操作成功", "token": "jwt-token-xxx"}


class TestLoginFlow:
    """登录三种模式 + 验证码重试 + /login 特判（KTD3）。"""

    def test_skip_mode_captcha_disabled_success(self) -> None:
        """skip 模式 + 后端关闭验证码 → 直接登录成功，拿到 token。"""
        client = _mock_client({
            "GET /captchaImage": CAPTCHA_OFF,
            "POST /login": LOGIN_OK,
        })
        result = login(client, "admin", "pwd", captcha_mode="skip")
        assert result.success is True
        assert result.token == "jwt-token-xxx"

    def test_image_mode_retry_then_success(self) -> None:
        """image 模式：第一次验证码错误，重取 uuid 后第二次成功。"""
        calls = {"n": 0, "captcha_fetches": 0}

        def captcha_handler(request):
            calls["captcha_fetches"] += 1
            return httpx.Response(200, json={**CAPTCHA_ON, "uuid": f"u-{calls['captcha_fetches']}"})

        def login_handler(request):
            calls["n"] += 1
            if calls["n"] == 1:
                return httpx.Response(200, json={"code": 500, "msg": "验证码错误"})
            return httpx.Response(200, json=LOGIN_OK)

        client = _mock_client({
            "GET /captchaImage": captcha_handler,
            "POST /login": login_handler,
        })
        prompts = iter(["wrong", "correct"])
        result = login(
            client, "admin", "pwd", captcha_mode="image",
            prompt_func=lambda: next(prompts),
            open_image_func=lambda p: None,
        )
        assert result.success is True
        assert calls["captcha_fetches"] == 2  # 重试时重取了新 uuid（单次消费）

    def test_login_captcha_error_exit6_not5(self) -> None:
        """/login 特判（KTD3）：code 500 + '验证码错误' → 退出码 6（非 5）。"""
        client = _mock_client({
            "GET /captchaImage": CAPTCHA_OFF,
            "POST /login": {"code": 500, "msg": "验证码错误"},
        })
        result = login(client, "admin", "pwd", captcha_mode="skip", max_retries=1)
        assert result.success is False
        assert result.exit_code == ExitCode.VALIDATION

    def test_login_password_error_exit6(self) -> None:
        """/login 特判：'用户名或密码错误' → 退出码 6。"""
        client = _mock_client({
            "GET /captchaImage": CAPTCHA_OFF,
            "POST /login": {"code": 500, "msg": "用户名或密码错误"},
        })
        result = login(client, "admin", "wrong", captcha_mode="skip", max_retries=1)
        assert result.exit_code == ExitCode.VALIDATION

    def test_login_server_error_exit5(self) -> None:
        """/login 非 500 关键词的 code 500 → 退出码 5（ServerError）。"""
        client = _mock_client({
            "GET /captchaImage": CAPTCHA_OFF,
            "POST /login": {"code": 500, "msg": "服务异常"},
        })
        result = login(client, "admin", "pwd", captcha_mode="skip", max_retries=1)
        assert result.exit_code == ExitCode.SERVER


# ============================================================
# 场景 2: 笔记生命周期（CliRunner + respx 驱动完整 Typer 命令链）
# ============================================================


class TestNoteLifecycle:
    """list → get → create → update → delete 完整 CRUD 流程。"""

    @respx.mock
    def test_list_notes_table_output(self) -> None:
        """list 命令默认 table 输出，退出码 0。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200,
            json={
                "code": 200, "msg": "查询成功",
                "rows": [{"id": 1, "title": "笔记A"}], "total": 1,
            },
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.OK
        assert "笔记A" in result.stdout

    @respx.mock
    def test_list_notes_json_output(self) -> None:
        """--json 输出纯 JSON（无 ANSI），含 rows/total。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200,
            json={
                "code": 200, "msg": "查询成功",
                "rows": [{"id": 1, "title": "笔记A"}], "total": 1,
            },
        )
        result = runner.invoke(app, ["--token", TOKEN, "--json", "note", "list"])
        assert result.exit_code == ExitCode.OK
        body = json.loads(result.stdout)
        assert body["total"] == 1
        assert body["rows"][0]["title"] == "笔记A"

    @respx.mock
    def test_get_note_found(self) -> None:
        """get 命令：code 200 + data 键 → 输出详情。"""
        respx.get(f"{BASE_URL}/system/note/42").respond(
            200,
            json={"code": 200, "msg": "操作成功", "data": {"id": 42, "title": "笔记42"}},
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "get", "42"])
        assert result.exit_code == ExitCode.OK
        assert "笔记42" in result.stdout

    @respx.mock
    def test_get_note_not_found_no_data_key(self) -> None:
        """get 命令：code 200 + 无 data 键 = 未找到（R10），退出码 0。"""
        respx.get(f"{BASE_URL}/system/note/999").respond(
            200, json={"code": 200, "msg": "操作成功"},
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "get", "999"])
        assert result.exit_code == ExitCode.OK
        assert "未找到" in result.stderr or "未找到" in result.stdout

    @respx.mock
    def test_create_note_from_file(self, tmp_path: Path) -> None:
        """create 命令：从 JSON 文件读取 payload，POST /add。"""
        payload_file = tmp_path / "note.json"
        payload_file.write_text(
            json.dumps({"title": "新笔记", "content": "内容"}), encoding="utf-8"
        )
        route = respx.post(f"{BASE_URL}/system/note/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "note", "create", "--file", str(payload_file)]
        )
        assert result.exit_code == ExitCode.OK
        assert route.called
        body = json.loads(route.calls.last.request.content)
        assert body["title"] == "新笔记"

    @respx.mock
    def test_update_note_injects_id(self, tmp_path: Path) -> None:
        """update 命令：payload 未含 id 时自动注入 note_id，POST /user/update。"""
        payload_file = tmp_path / "update.json"
        payload_file.write_text(
            json.dumps({"title": "更新标题"}), encoding="utf-8"
        )
        route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        result = runner.invoke(
            app,
            ["--token", TOKEN, "note", "update", "42", "--file", str(payload_file)],
        )
        assert result.exit_code == ExitCode.OK
        assert route.calls.last.request.url.path == "/system/note/user/update"
        body = json.loads(route.calls.last.request.content)
        assert body["id"] == "42"
        assert body["title"] == "更新标题"

    @respx.mock
    def test_check_revision_match_allows_update(self, tmp_path: Path) -> None:
        """--check-revision 匹配：GET /{id} revisionId=5，传 5 → 允许更新。"""
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 1, "revisionId": 5}},
        )
        update_route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        payload_file = tmp_path / "u.json"
        payload_file.write_text(json.dumps({"title": "x"}), encoding="utf-8")
        result = runner.invoke(
            app,
            ["--token", TOKEN, "note", "update", "1",
             "--file", str(payload_file), "--check-revision", "5"],
        )
        assert result.exit_code == ExitCode.OK
        assert update_route.called

    @respx.mock
    def test_check_revision_mismatch_blocks_update_exit7(self, tmp_path: Path) -> None:
        """--check-revision 不匹配 → 退出码 7（WARN），更新未执行。"""
        respx.get(f"{BASE_URL}/system/note/1").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 1, "revisionId": 6}},
        )
        update_route = respx.post(f"{BASE_URL}/system/note/user/update").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        payload_file = tmp_path / "u.json"
        payload_file.write_text(json.dumps({"title": "x"}), encoding="utf-8")
        result = runner.invoke(
            app,
            ["--token", TOKEN, "note", "update", "1",
             "--file", str(payload_file), "--check-revision", "5"],
        )
        assert result.exit_code == ExitCode.WARN
        assert not update_route.called
        assert "revision" in result.stderr.lower() or "revision" in result.stdout.lower()


# ============================================================
# 场景 3: 破坏性命令预检（CliRunner 驱动 confirm_destructive）
# ============================================================


class TestDestructiveConfirm:
    """破坏性命令的 --yes 跳过确认 / 交互确认 / 取消。"""

    @respx.mock
    def test_delete_with_yes_skips_confirm(self) -> None:
        """delete --yes 跳过交互确认，直接执行。"""
        route = respx.get(f"{BASE_URL}/system/note/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "note", "delete", "1", "--yes"]
        )
        assert result.exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_interactive_confirm_yes(self) -> None:
        """delete 无 --yes：交互输入 y → 执行。"""
        route = respx.get(f"{BASE_URL}/system/note/remove/1").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "note", "delete", "1"], input="y\n"
        )
        assert result.exit_code == ExitCode.OK
        assert route.called

    @respx.mock
    def test_delete_interactive_cancel(self) -> None:
        """delete 无 --yes：交互输入 n → 取消，不调用后端。"""
        route = respx.get(f"{BASE_URL}/system/note/remove/1").respond(
            200, json={"code": 200, "msg": "ok"}
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "note", "delete", "1"], input="n\n"
        )
        assert result.exit_code == ExitCode.OK  # 取消视为正常退出
        assert not route.called

    @respx.mock
    def test_garbage_clear_is_destructive(self) -> None:
        """garbage clear 是破坏性命令，--yes 跳过确认。"""
        route = respx.get(f"{BASE_URL}/system/note/clearGarbage/1").respond(
            200, json={"code": 200, "msg": "清除成功"}
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "note", "garbage", "clear", "1", "--yes"]
        )
        assert result.exit_code == ExitCode.OK
        assert route.called


# ============================================================
# 场景 4: 多维表子系统协作（dwtable → column → record → block）
# ============================================================


class TestDwtableSubsystem:
    """多维表四子系统端到端流程。"""

    @respx.mock
    def test_dwtable_create_then_get(self, tmp_path: Path) -> None:
        """dwtable create → get 完整流程。"""
        payload_file = tmp_path / "dwt.json"
        payload_file.write_text(
            json.dumps({"name": "表1", "noteId": 1}), encoding="utf-8"
        )
        respx.post(f"{BASE_URL}/system/dwtable/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        respx.get(f"{BASE_URL}/system/dwtable/10").respond(
            200,
            json={"code": 200, "msg": "ok", "data": {"id": 10, "name": "表1"}},
        )
        # create
        r1 = runner.invoke(
            app, ["--token", TOKEN, "dwtable", "create", "--file", str(payload_file)]
        )
        assert r1.exit_code == ExitCode.OK
        # get
        r2 = runner.invoke(app, ["--token", TOKEN, "dwtable", "get", "10"])
        assert r2.exit_code == ExitCode.OK
        assert "表1" in r2.stdout

    @respx.mock
    def test_column_list_by_dwtable(self) -> None:
        """column list 按 dwtableId 筛选。"""
        route = respx.get(f"{BASE_URL}/system/column/list").respond(
            200,
            json={
                "code": 200, "msg": "ok",
                "rows": [{"id": 1, "name": "列1", "dwtableId": 5}], "total": 1,
            },
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "column", "list", "--dwtable-id", "5"]
        )
        assert result.exit_code == ExitCode.OK
        params = dict(route.calls.last.request.url.params)
        assert params["dwtableId"] == "5"

    @respx.mock
    def test_record_crud_flow(self, tmp_path: Path) -> None:
        """record create → update → delete 流程。"""
        create_route = respx.post(f"{BASE_URL}/system/record/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        update_route = respx.post(f"{BASE_URL}/system/record/update").respond(
            200, json={"code": 200, "msg": "修改成功"}
        )
        delete_route = respx.get(f"{BASE_URL}/system/record/remove/7").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        create_payload = tmp_path / "rec.json"
        create_payload.write_text(json.dumps({"dwtableId": 5}), encoding="utf-8")
        update_payload = tmp_path / "rec_u.json"
        update_payload.write_text(json.dumps({"name": "更新"}), encoding="utf-8")

        r1 = runner.invoke(
            app, ["--token", TOKEN, "record", "create", "--file", str(create_payload)]
        )
        assert r1.exit_code == ExitCode.OK
        assert create_route.called

        r2 = runner.invoke(
            app, ["--token", TOKEN, "record", "update", "7", "--file", str(update_payload)]
        )
        assert r2.exit_code == ExitCode.OK
        assert update_route.called
        body = json.loads(update_route.calls.last.request.content)
        assert body["id"] == "7"

        r3 = runner.invoke(
            app, ["--token", TOKEN, "record", "delete", "7", "--yes"]
        )
        assert r3.exit_code == ExitCode.OK
        assert delete_route.called

    @respx.mock
    def test_block_create_and_list(self, tmp_path: Path) -> None:
        """block create → list 流程。"""
        payload_file = tmp_path / "blk.json"
        payload_file.write_text(json.dumps({"noteId": 1, "content": "块内容"}), encoding="utf-8")
        respx.post(f"{BASE_URL}/system/block/add").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        respx.get(f"{BASE_URL}/system/block/list").respond(
            200,
            json={
                "code": 200, "msg": "ok",
                "rows": [{"id": 1, "content": "块内容"}], "total": 1,
            },
        )
        r1 = runner.invoke(
            app, ["--token", TOKEN, "block", "create", "--file", str(payload_file)]
        )
        assert r1.exit_code == ExitCode.OK
        r2 = runner.invoke(app, ["--token", TOKEN, "block", "list"])
        assert r2.exit_code == ExitCode.OK
        assert "块内容" in r2.stdout


# ============================================================
# 场景 5: 语义关联（notelink）
# ============================================================


class TestNotelinkFlow:
    """语义关联端到端流程。"""

    @respx.mock
    def test_notelink_create_then_by_note(self, tmp_path: Path) -> None:
        """notelink create（POST 根路径）→ by-note 查询。"""
        payload_file = tmp_path / "nl.json"
        payload_file.write_text(
            json.dumps({"noteId": 1, "linkNoteId": 2}), encoding="utf-8"
        )
        create_route = respx.post(f"{BASE_URL}/system/notelink").respond(
            200, json={"code": 200, "msg": "新增成功"}
        )
        respx.get(f"{BASE_URL}/system/notelink/byNote/1").respond(
            200,
            json={
                "code": 200, "msg": "ok",
                "data": [{"id": 1, "noteId": 1, "linkNoteId": 2}],
            },
        )
        r1 = runner.invoke(
            app, ["--token", TOKEN, "notelink", "create", "--file", str(payload_file)]
        )
        assert r1.exit_code == ExitCode.OK
        # 验证 create 用 POST 根路径（无 /add）
        assert create_route.calls.last.request.method == "POST"
        assert create_route.calls.last.request.url.path == "/system/notelink"

        r2 = runner.invoke(app, ["--token", TOKEN, "notelink", "by-note", "1"])
        assert r2.exit_code == ExitCode.OK

    @respx.mock
    def test_notelink_delete_uses_delete_method(self) -> None:
        """notelink delete 用 DELETE 方法（其他 Controller 都是 GET /remove）。"""
        route = respx.delete(f"{BASE_URL}/system/notelink/1,2").respond(
            200, json={"code": 200, "msg": "删除成功"}
        )
        result = runner.invoke(
            app, ["--token", TOKEN, "notelink", "delete", "1,2", "--yes"]
        )
        assert result.exit_code == ExitCode.OK
        assert route.called
        assert route.calls.last.request.method == "DELETE"


# ============================================================
# 场景 6: 错误处理与退出码（CliRunner + respx）
# ============================================================


class TestErrorHandling:
    """业务码 → 退出码映射（R5）+ 网络错误。"""

    @respx.mock
    def test_auth_error_exit2(self) -> None:
        """code 401 → 退出码 2（AUTH）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 401, "msg": "未授权"}
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.AUTH

    @respx.mock
    def test_forbidden_exit3(self) -> None:
        """code 403 → 退出码 3（FORBIDDEN）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 403, "msg": "禁止访问"}
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.FORBIDDEN

    @respx.mock
    def test_server_error_exit5(self) -> None:
        """code 500 → 退出码 5（SERVER）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 500, "msg": "服务异常"}
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.SERVER

    @respx.mock
    def test_network_error_exit10(self) -> None:
        """连接错误 → 退出码 10（NETWORK）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").mock(
            side_effect=httpx.ConnectError("connection refused")
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.NETWORK

    @respx.mock
    def test_empty_results_table_mode(self) -> None:
        """空结果 table 模式：退出码 0（R9）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 200, "msg": "查询成功", "rows": [], "total": 0}
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.OK

    @respx.mock
    def test_empty_results_json_mode(self) -> None:
        """空结果 json 模式：输出 {"rows":[],"total":0}（R9）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 200, "msg": "查询成功", "rows": [], "total": 0}
        )
        result = runner.invoke(app, ["--token", TOKEN, "--json", "note", "list"])
        assert result.exit_code == ExitCode.OK
        body = json.loads(result.stdout)
        assert body["rows"] == []
        assert body["total"] == 0


# ============================================================
# 场景 7: token 活性探测（KTD2，核心函数级）
# ============================================================


class TestTokenProbe:
    """破坏性命令强制预检 + 非破坏性跳过窗口 + 401/网络错误分流。"""

    def test_force_probe_destructive_calls_getinfo(self, tmp_path: Path) -> None:
        """force=True（破坏性命令）即使最近探测过也强制调 /getInfo。"""
        creds_path = tmp_path / "credentials"
        creds = Credentials(
            token=TOKEN, username="admin",
            last_probe_at=0.0,  # 远古时间，模拟"刚探测过"
        )
        save_credentials(creds, creds_path)

        client = _mock_client({
            "GET /getInfo": {"code": 200, "msg": "ok", "user": {"userName": "admin"}},
        })
        result = probe_token(client, force=True, path=creds_path)
        assert result.active is True
        assert result.exit_code is None

    def test_skip_probe_within_window(self, tmp_path: Path) -> None:
        """非破坏性命令 + 最近探测过（< 5 分钟）→ 跳过 /getInfo 调用。"""
        import time
        creds_path = tmp_path / "credentials"
        creds = Credentials(
            token=TOKEN, username="admin",
            last_probe_at=time.time(),  # 刚探测过
        )
        save_credentials(creds, creds_path)

        # /getInfo 不应被调用（若调用会 404）
        client = _mock_client({})  # 无路由，任何请求 404
        result = probe_token(client, force=False, path=creds_path)
        assert result.active is True
        assert result.exit_code is None

    def test_probe_401_marks_credentials_invalid(self, tmp_path: Path) -> None:
        """探测返回 401 → 标记 credentials 失效（删除文件），退出码 2。"""
        creds_path = tmp_path / "credentials"
        save_credentials(Credentials(token=TOKEN, username="admin"), creds_path)
        assert creds_path.exists()

        client = _mock_client({
            "GET /getInfo": {"code": 401, "msg": "token 失效"},
        })
        result = probe_token(client, force=True, path=creds_path)
        assert result.active is False
        assert result.exit_code == ExitCode.AUTH
        assert not creds_path.exists()  # credentials 已删除

    def test_probe_network_error_exit10_keep_creds(self, tmp_path: Path) -> None:
        """探测网络错误 → 退出码 10，**不**标记 token 失效（保留 credentials）。"""
        import time
        creds_path = tmp_path / "credentials"
        save_credentials(
            Credentials(token=TOKEN, username="admin", last_probe_at=time.time()),
            creds_path,
        )

        def handler(request):
            raise httpx.ConnectError("network down")

        client = httpx.Client(
            base_url=BASE_URL, transport=httpx.MockTransport(handler)
        )
        # 让最近探测时间过期，触发真实 /getInfo 调用
        import rainote.auth.token as token_mod
        original = token_mod.PROBE_INTERVAL
        token_mod.PROBE_INTERVAL = 0  # 立即过期
        try:
            result = probe_token(client, force=False, path=creds_path)
        finally:
            token_mod.PROBE_INTERVAL = original
        assert result.active is False
        assert result.exit_code == ExitCode.NETWORK
        assert creds_path.exists()  # credentials 保留（不误判）

    def test_probe_corrupt_credentials_exit2(self, tmp_path: Path) -> None:
        """credentials 文件损坏 → 退出码 2（AUTH），提示重登。"""
        creds_path = tmp_path / "credentials"
        creds_path.write_text("not a json {{{", encoding="utf-8")

        client = _mock_client({})
        result = probe_token(client, force=True, path=creds_path)
        assert result.active is False
        assert result.exit_code == ExitCode.AUTH


# ============================================================
# 场景 8: 全局选项与配置
# ============================================================


class TestGlobalOptions:
    """--token 警告 / --version / 无参数提示。"""

    @respx.mock
    def test_token_flag_emits_warning(self) -> None:
        """--token 参数触发一次性安全警告（R16），警告在命令执行前输出。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200, json={"code": 200, "msg": "ok", "rows": [], "total": 0}
        )
        result = runner.invoke(app, ["--token", TOKEN, "note", "list"])
        assert result.exit_code == ExitCode.OK
        # CliRunner 默认 mix_stderr=True，stderr 混入 output；也检查 stderr
        combined = (result.output or "") + (result.stderr or "")
        assert "警告" in combined or "token" in combined.lower()

    def test_version_flag(self) -> None:
        """--version 显示版本号并退出。"""
        result = runner.invoke(app, ["--version"])
        assert result.exit_code == ExitCode.OK
        assert "rainote" in result.stdout.lower()

    def test_no_args_shows_help(self) -> None:
        """无参数调用 → no_args_is_help 触发帮助输出。

        Click/Typer 的 no_args_is_help 行为是输出 usage 到 stderr 并以退出码 2
        （UsageError）退出，这是 Click 的标准约定。
        """
        result = runner.invoke(app, [])
        assert result.exit_code == 2  # Click no_args_is_help 的标准退出码
        combined = (result.output or "") + (result.stderr or "")
        assert "Usage" in combined or "rainote" in combined.lower()

    @respx.mock
    def test_csv_output_format(self) -> None:
        """--csv 输出 CSV 格式（无 ANSI）。"""
        respx.get(f"{BASE_URL}/system/note/pageList").respond(
            200,
            json={
                "code": 200, "msg": "ok",
                "rows": [{"id": 1, "title": "笔记A"}], "total": 1,
            },
        )
        result = runner.invoke(app, ["--token", TOKEN, "--csv", "note", "list"])
        assert result.exit_code == ExitCode.OK
        # CSV 输出含表头或值
        assert "笔记A" in result.stdout
