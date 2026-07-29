"""models.common 数据模型测试。"""

from __future__ import annotations

from rainote.models.common import (
    BIZ_OK,
    BIZ_SERVER_ERROR,
    BIZ_UNAUTHORIZED,
    ApiResponse,
    ErrorPayload,
    TablePage,
)


class TestApiResponse:
    def test_ok_code_200(self) -> None:
        r = ApiResponse(code=200, msg="操作成功", raw={"code": 200, "msg": "操作成功"})
        assert r.ok is True

    def test_ok_code_500(self) -> None:
        r = ApiResponse(code=500, msg="服务异常", raw={"code": 500, "msg": "服务异常"})
        assert r.ok is False

    def test_ok_code_401(self) -> None:
        r = ApiResponse(code=401, msg="未授权", raw={"code": 401})
        assert r.ok is False

    def test_has_data_present(self) -> None:
        """data 键存在 → has_data=True（即使 data 值为 None）。"""
        r = ApiResponse(code=200, msg="ok", data=None, raw={"code": 200, "data": None})
        assert r.has_data is True

    def test_has_data_absent(self) -> None:
        """后端 AjaxResult 在 data==null 时不 put data 键 → has_data=False。"""
        r = ApiResponse(code=200, msg="操作成功", raw={"code": 200, "msg": "操作成功"})
        assert r.has_data is False

    def test_has_data_with_value(self) -> None:
        r = ApiResponse(
            code=200, msg="ok", data={"id": 1}, raw={"code": 200, "data": {"id": 1}}
        )
        assert r.has_data is True

    def test_defaults(self) -> None:
        r = ApiResponse(code=200, msg="ok")
        assert r.data is None
        assert r.raw == {}
        assert r.is_page is False
        assert r.rows is None
        assert r.total is None

    def test_page_response(self) -> None:
        r = ApiResponse(
            code=200,
            msg="查询成功",
            is_page=True,
            rows=[{"id": 1}, {"id": 2}],
            total=42,
            raw={"code": 200, "rows": [{"id": 1}, {"id": 2}], "total": 42},
        )
        assert r.is_page is True
        assert r.rows == [{"id": 1}, {"id": 2}]
        assert r.total == 42
        assert r.ok is True

    def test_as_error(self) -> None:
        r = ApiResponse(
            code=BIZ_UNAUTHORIZED, msg="未授权", raw={"code": 401, "msg": "未授权"}
        )
        err = r.as_error(exit_code=2)
        assert isinstance(err, ErrorPayload)
        assert err.code == 401
        assert err.msg == "未授权"
        assert err.exit_code == 2
        assert err.raw == {"code": 401, "msg": "未授权"}


class TestTablePage:
    def test_fields(self) -> None:
        p = TablePage(rows=[{"id": 1}], total=10)
        assert p.rows == [{"id": 1}]
        assert p.total == 10

    def test_empty(self) -> None:
        p = TablePage(rows=[], total=0)
        assert p.rows == []
        assert p.total == 0


class TestErrorPayload:
    def test_fields(self) -> None:
        e = ErrorPayload(code=500, msg="服务异常", exit_code=5)
        assert e.code == 500
        assert e.msg == "服务异常"
        assert e.exit_code == 5
        assert e.raw is None

    def test_with_raw(self) -> None:
        raw = {"code": 500, "msg": "服务异常"}
        e = ErrorPayload(code=500, msg="服务异常", exit_code=5, raw=raw)
        assert e.raw == raw


class TestBizConstants:
    def test_biz_constants(self) -> None:
        assert BIZ_OK == 200
        assert BIZ_UNAUTHORIZED == 401
        assert BIZ_SERVER_ERROR == 500
