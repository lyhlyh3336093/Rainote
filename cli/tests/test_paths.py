"""U1 路径模块测试 —— ~/.rainote/ 创建与 .gitignore 自动生成。"""

from __future__ import annotations

from pathlib import Path

from rainote.paths import (
    rainote_home,
    credentials_path,
    config_path,
    ensure_rainote_home,
    restrict_file,
)


def test_rainote_home_respects_env(monkeypatch, tmp_path):
    """RAINOTE_HOME 环境变量覆盖默认主目录。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    assert rainote_home() == tmp_path


def test_ensure_home_creates_dir_and_gitignore(monkeypatch, tmp_path):
    """首次创建 ~/.rainote/ 时 .gitignore 自动生成且含 *。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    home = ensure_rainote_home()
    assert home.exists()
    assert home.is_dir()
    gitignore = home / ".gitignore"
    assert gitignore.exists()
    content = gitignore.read_text(encoding="utf-8")
    assert "*" in content


def test_ensure_home_is_idempotent(monkeypatch, tmp_path):
    """重复调用 ensure_rainote_home 不报错、不覆盖已有 .gitignore 之外的内容。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    ensure_rainote_home()
    # 写入一个用户文件
    (rainote_home() / "user_file").write_text("keep me", encoding="utf-8")
    ensure_rainote_home()
    assert (rainote_home() / "user_file").read_text(encoding="utf-8") == "keep me"


def test_credentials_and_config_paths_under_home(monkeypatch, tmp_path):
    """credentials 与 config.toml 路径位于 home 下。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    assert credentials_path() == tmp_path / "credentials"
    assert config_path() == tmp_path / "config.toml"


def test_restrict_file_on_nonexistent_returns_false(monkeypatch, tmp_path):
    """restrict_file 对不存在的文件返回 False。"""
    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    assert restrict_file(tmp_path / "nope") is False


def test_restrict_file_attempts_restriction(monkeypatch, tmp_path):
    """restrict_file 对存在文件尝试限制权限（POSIX 验证 mode，Windows 验证不崩溃）。"""
    import os

    monkeypatch.setenv("RAINOTE_HOME", str(tmp_path))
    f = tmp_path / "secret"
    f.write_text("token", encoding="utf-8")
    result = restrict_file(f)
    # 至少不崩溃；POSIX 下应成功并设 0o600
    if os.name == "posix":
        assert result is True
        mode = oct(os.stat(f).st_mode & 0o777)
        assert mode == "0o600"
