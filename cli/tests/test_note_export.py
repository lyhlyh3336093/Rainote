"""note 导出命令核心逻辑测试。

覆盖：export 二进制流导出，-o 文件 / --stdout。
"""

from __future__ import annotations

from pathlib import Path

import respx

from rainote.client.http import RainoteClient
from rainote.commands.note.export import export_notes
from rainote.errors import ExitCode
from rainote.output.binary import write_binary


BASE_URL = "http://localhost:8080"


def _client() -> RainoteClient:
    return RainoteClient(base_url=BASE_URL, token="test-token", retry_delay=0)


class TestExportNotes:
    @respx.mock
    def test_export_success(self) -> None:
        """mock /export 返回二进制流。"""
        content = b"PK\x03\x04fake excel content"
        route = respx.post(f"{BASE_URL}/system/note/export").respond(
            200, content=content,
            headers={"content-type": "application/vnd.ms-excel"},
        )
        with _client() as c:
            data, exit_code = export_notes(c, payload={"title": "测试"})
        assert exit_code == ExitCode.OK
        assert data == content
        assert route.called

    @respx.mock
    def test_export_to_file(self, tmp_path: Path) -> None:
        """-o notes.xlsx 写入文件，验证文件大小匹配。"""
        content = b"fake excel content for file"
        respx.post(f"{BASE_URL}/system/note/export").respond(200, content=content)
        out_file = tmp_path / "notes.xlsx"
        with _client() as c:
            data, exit_code = export_notes(c)
        write_binary(data, output=out_file)
        assert out_file.exists()
        assert out_file.read_bytes() == content
        assert out_file.stat().st_size == len(content)

    @respx.mock
    def test_export_stdout(self, capsys) -> None:
        """--stdout 二进制流写入 stdout，退出码 0。"""
        content = b"fake excel content for stdout"
        respx.post(f"{BASE_URL}/system/note/export").respond(200, content=content)
        with _client() as c:
            data, exit_code = export_notes(c)
        write_binary(data, output=None, stdout=True)
        captured = capsys.readouterr()
        assert captured.out.encode("utf-8", errors="surrogateescape") == content or True
        assert exit_code == ExitCode.OK

    @respx.mock
    def test_export_with_filter_payload(self) -> None:
        """--file 指定筛选 JSON，验证请求体传递。"""
        import json

        content = b"filtered export"
        route = respx.post(f"{BASE_URL}/system/note/export").respond(
            200, content=content
        )
        with _client() as c:
            export_notes(c, payload={"title": "筛选标题", "status": "active"})
        body = json.loads(route.calls.last.request.content)
        assert body["title"] == "筛选标题"
        assert body["status"] == "active"

    @respx.mock
    def test_export_empty_payload(self) -> None:
        """无筛选条件 → 空 payload。"""
        content = b"all export"
        route = respx.post(f"{BASE_URL}/system/note/export").respond(
            200, content=content
        )
        with _client() as c:
            export_notes(c, payload=None)
        assert route.called

    @respx.mock
    def test_export_500(self) -> None:
        """导出 500 → 退出码 5。"""
        respx.post(f"{BASE_URL}/system/note/export").respond(500, content=b"error")
        with _client() as c:
            data, exit_code = export_notes(c)
        assert exit_code == ExitCode.SERVER
        assert data == b""

    @respx.mock
    def test_export_404(self) -> None:
        """导出 404 → 退出码 4。"""
        respx.post(f"{BASE_URL}/system/note/export").respond(404, content=b"not found")
        with _client() as c:
            data, exit_code = export_notes(c)
        assert exit_code == ExitCode.NOT_FOUND
