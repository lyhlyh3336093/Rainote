"""client/response.py 响应归一化测试。

覆盖 AjaxResult / TableDataInfo → ApiResponse 归一化，含 has_data 检测（KTD2）。
"""

from __future__ import annotations

import httpx

from rainote.errors import ExitCode
from rainote.client.response import parse_body, parse_response


# ---- parse_body: AjaxResult ----


class TestParseBodyAjaxResult:
    def test_success_with_data(self) -> None:
        api, exit_code = parse_body({"code": 200, "msg": "操作成功", "data": {"id": 1}})
        assert api.ok is True
        assert api.code == 200
        assert api.msg == "操作成功"
        assert api.data == {"id": 1}
        assert api.has_data is True
        assert api.is_page is False
        assert exit_code == ExitCode.OK

    def test_success_data_missing(self) -> None:
        """后端 AjaxResult 在 data==null 时不 put data 键 → has_data=False。"""
        api, exit_code = parse_body({"code": 200, "msg": "操作成功"})
        assert api.ok is True
        assert api.has_data is False
        assert api.data is None
        assert exit_code == ExitCode.OK

    def test_success_data_null(self) -> None:
        """data 键存在但值为 null → has_data=True（兼容后端可能输出 data:null）。"""
        api, _ = parse_body({"code": 200, "msg": "ok", "data": None})
        assert api.has_data is True
        assert api.data is None

    def test_error_401(self) -> None:
        """mock 后端返回 HTTP 200 + {code:401} → 退出码 2。"""
        api, exit_code = parse_body({"code": 401, "msg": "未授权"})
        assert api.ok is False
        assert api.code == 401
        assert exit_code == ExitCode.AUTH

    def test_error_403(self) -> None:
        api, exit_code = parse_body({"code": 403, "msg": "禁止访问"})
        assert exit_code == ExitCode.FORBIDDEN

    def test_error_404(self) -> None:
        api, exit_code = parse_body({"code": 404, "msg": "未找到"})
        assert exit_code == ExitCode.NOT_FOUND

    def test_error_500(self) -> None:
        api, exit_code = parse_body({"code": 500, "msg": "服务异常"})
        assert exit_code == ExitCode.SERVER

    def test_error_601_warn(self) -> None:
        api, exit_code = parse_body({"code": 601, "msg": "revision 不匹配"})
        assert exit_code == ExitCode.WARN

    def test_login_special_case(self) -> None:
        """/login 特判：{code:500, msg:"验证码错误"} → 退出码 6（非 5）。"""
        api, exit_code = parse_body(
            {"code": 500, "msg": "验证码错误"}, is_login=True
        )
        assert exit_code == ExitCode.VALIDATION

    def test_login_non_validation_500(self) -> None:
        """/login：{code:500, msg:"服务异常"} → 退出码 5（不匹配关键词）。"""
        api, exit_code = parse_body(
            {"code": 500, "msg": "服务异常"}, is_login=True
        )
        assert exit_code == ExitCode.SERVER


# ---- parse_body: TableDataInfo ----


class TestParseBodyTableDataInfo:
    def test_page_response(self) -> None:
        body = {"code": 200, "msg": "查询成功", "rows": [{"id": 1}, {"id": 2}], "total": 42}
        api, exit_code = parse_body(body)
        assert api.is_page is True
        assert api.rows == [{"id": 1}, {"id": 2}]
        assert api.total == 42
        assert api.ok is True
        assert exit_code == ExitCode.OK

    def test_page_empty(self) -> None:
        """空结果：{rows:[], total:0}。"""
        body = {"code": 200, "msg": "查询成功", "rows": [], "total": 0}
        api, _ = parse_body(body)
        assert api.is_page is True
        assert api.rows == []
        assert api.total == 0

    def test_page_without_code_defaults_200(self) -> None:
        """TableDataInfo 可能省略 code（默认 200）。"""
        body = {"rows": [{"id": 1}], "total": 1}
        api, _ = parse_body(body)
        assert api.is_page is True
        assert api.code == 200
        assert api.ok is True

    def test_page_raw_preserved(self) -> None:
        body = {"code": 200, "msg": "查询成功", "rows": [], "total": 0}
        api, _ = parse_body(body)
        assert api.raw == body


# ---- parse_response: httpx.Response 包装 ----


class TestParseResponse:
    def test_ajax_result_json(self) -> None:
        resp = httpx.Response(
            200, json={"code": 200, "msg": "ok", "data": {"id": 1}}
        )
        api, exit_code = parse_response(resp)
        assert api.ok is True
        assert api.data == {"id": 1}
        assert exit_code == ExitCode.OK

    def test_page_json(self) -> None:
        resp = httpx.Response(
            200, json={"code": 200, "msg": "查询成功", "rows": [{"id": 1}], "total": 1}
        )
        api, _ = parse_response(resp)
        assert api.is_page is True
        assert api.rows == [{"id": 1}]

    def test_non_json_fallback_http_status(self) -> None:
        """body 非 JSON → 回退 HTTP 状态码。

        mock 后端返回 HTTP 502（非 JSON）→ 退出码 5。
        """
        resp = httpx.Response(
            502, content=b"<html>Bad Gateway</html>", headers={"content-type": "text/html"}
        )
        api, exit_code = parse_response(resp)
        assert exit_code == ExitCode.SERVER
        assert api.code == 502

    def test_non_json_404_fallback(self) -> None:
        resp = httpx.Response(404, content=b"Not Found", headers={"content-type": "text/plain"})
        api, exit_code = parse_response(resp)
        assert exit_code == ExitCode.NOT_FOUND

    def test_login_response_special_case(self) -> None:
        """parse_response 传递 is_login。"""
        resp = httpx.Response(200, json={"code": 500, "msg": "验证码错误"})
        api, exit_code = parse_response(resp, is_login=True)
        assert exit_code == ExitCode.VALIDATION

    def test_empty_body_fallback(self) -> None:
        """空 body → 回退 HTTP 状态码。"""
        resp = httpx.Response(200, content=b"")
        api, exit_code = parse_response(resp)
        assert exit_code == ExitCode.OK

    def test_error_response_with_data(self) -> None:
        """错误响应也可能携带 data（如校验错误详情）。"""
        resp = httpx.Response(
            200,
            json={"code": 400, "msg": "参数错误", "data": {"field": "title"}},
        )
        api, exit_code = parse_response(resp)
        assert exit_code == ExitCode.VALIDATION
        assert api.has_data is True
        assert api.data == {"field": "title"}
