"""二进制流导出测试。

覆盖：Excel 导出 → 文件 / stdout（R9）。
"""

from __future__ import annotations

import io
import sys

import httpx
import respx

from rainote.errors import ExitCode
from rainote.client.http import RainoteClient
from rainote.output.binary import write_binary


BASE_URL = "http://localhost:8080"


class TestDownloadBinary:
    @respx.mock
    def test_download_success(self) -> None:
        """mock /export 返回二进制流，验证字节匹配。"""
        content = b"PK\x03\x04fake excel content"  # xlsx 文件头
        respx.get(f"{BASE_URL}/system/note/export").respond(
            200, content=content, headers={"content-type": "application/vnd.ms-excel"}
        )
        client = RainoteClient(base_url=BASE_URL, token="t", retry_delay=0)
        try:
            data, exit_code = client.download_binary("GET", "/system/note/export")
        finally:
            client.close()
        assert exit_code == ExitCode.OK
        assert data == content

    @respx.mock
    def test_download_404(self) -> None:
        """导出接口 404 → 退出码 4，返回空字节。"""
        respx.get(f"{BASE_URL}/system/note/export").respond(
            404, content=b"Not Found"
        )
        client = RainoteClient(base_url=BASE_URL, token="t", retry_delay=0)
        try:
            data, exit_code = client.download_binary("GET", "/system/note/export")
        finally:
            client.close()
        assert exit_code == ExitCode.NOT_FOUND
        assert data == b""

    @respx.mock
    def test_download_500(self) -> None:
        """导出接口 500 → 退出码 5。"""
        respx.get(f"{BASE_URL}/system/note/export").respond(500, content=b"error")
        client = RainoteClient(
            base_url=BASE_URL, token="t", retry_max=1, retry_delay=0
        )
        try:
            data, exit_code = client.download_binary("GET", "/system/note/export")
        finally:
            client.close()
        assert exit_code == ExitCode.SERVER


class TestWriteBinary:
    def test_write_to_file(self, tmp_path) -> None:
        """-o file.xlsx 写入文件，验证文件大小匹配。"""
        content = b"fake excel content"
        out_file = tmp_path / "notes.xlsx"
        write_binary(content, output=out_file)
        assert out_file.exists()
        assert out_file.read_bytes() == content
        assert out_file.stat().st_size == len(content)

    def test_write_to_stdout(self, capsys) -> None:
        """--stdout 直接写 stdout（二进制安全）。"""
        content = b"fake excel content"
        write_binary(content, output=None, stdout=True)
        captured = capsys.readouterr()
        assert captured.out == "fake excel content"

    def test_neither_file_nor_stdout_raises(self) -> None:
        """未指定 -o 或 --stdout → 抛出 ValueError。"""
        import pytest

        with pytest.raises(ValueError):
            write_binary(b"data", output=None, stdout=False)

    def test_file_overwrites(self, tmp_path) -> None:
        """输出文件已存在时覆盖。"""
        out_file = tmp_path / "out.xlsx"
        out_file.write_bytes(b"old content")
        write_binary(b"new content", output=out_file)
        assert out_file.read_bytes() == b"new content"
