"""U1 配置模块测试 —— 四层优先级、NO_COLOR、config.toml 损坏降级。"""

from __future__ import annotations

from pathlib import Path

import pytest

from rainote.config import Config, DEFAULT_BASE_URL, DEFAULT_OUTPUT, VALID_OUTPUTS


# ---------- 内置默认 ----------

def test_defaults_when_nothing_provided(monkeypatch, tmp_path):
    """config.toml 不存在、无环境变量、无 CLI 覆盖时使用内置默认不报错。"""
    # 清空相关环境变量
    for var in ("RAINOTE_BASE_URL", "RAINOTE_OUTPUT", "RAINOTE_TOKEN",
                "NO_COLOR", "RAINOTE_NO_COLOR"):
        monkeypatch.delenv(var, raising=False)
    toml = tmp_path / "nonexistent.toml"
    cfg = Config.load(toml_path=toml)
    assert cfg.base_url == DEFAULT_BASE_URL
    assert cfg.output == DEFAULT_OUTPUT
    assert cfg.token is None
    assert cfg.no_color is False
    assert cfg.verbose is False
    assert cfg.allow_anonymous is False


def test_base_url_trailing_slash_stripped(monkeypatch, tmp_path):
    """base_url 去除尾部斜杠，避免拼接出双斜杠。"""
    monkeypatch.delenv("RAINOTE_BASE_URL", raising=False)
    cfg = Config.load({"base_url": "http://api.example.com/"}, toml_path=tmp_path / "x.toml")
    assert cfg.base_url == "http://api.example.com"


def test_invalid_output_raises(monkeypatch, tmp_path):
    """无效 output 值抛 ValueError。"""
    for var in ("RAINOTE_BASE_URL", "RAINOTE_OUTPUT"):
        monkeypatch.delenv(var, raising=False)
    with pytest.raises(ValueError):
        Config.load({"output": "xml"}, toml_path=tmp_path / "x.toml")


# ---------- 四层优先级 ----------

def test_cli_overrides_env(monkeypatch, tmp_path):
    """CLI --output json 覆盖 RAINOTE_OUTPUT=csv。"""
    monkeypatch.setenv("RAINOTE_OUTPUT", "csv")
    cfg = Config.load({"output": "json"}, toml_path=tmp_path / "x.toml")
    assert cfg.output == "json"


def test_env_overrides_toml(monkeypatch, tmp_path):
    """环境变量 RAINOTE_OUTPUT 覆盖 config.toml。"""
    toml = tmp_path / "config.toml"
    toml.write_text('output = "csv"\n', encoding="utf-8")
    monkeypatch.setenv("RAINOTE_OUTPUT", "json")
    cfg = Config.load(toml_path=toml)
    assert cfg.output == "json"


def test_toml_overrides_default(monkeypatch, tmp_path):
    """config.toml 覆盖内置默认。"""
    for var in ("RAINOTE_BASE_URL", "RAINOTE_OUTPUT", "RAINOTE_TOKEN"):
        monkeypatch.delenv(var, raising=False)
    toml = tmp_path / "config.toml"
    toml.write_text(
        'base_url = "http://toml-host:9090"\noutput = "csv"\n', encoding="utf-8")
    cfg = Config.load(toml_path=toml)
    assert cfg.base_url == "http://toml-host:9090"
    assert cfg.output == "csv"


def test_cli_none_does_not_override(monkeypatch, tmp_path):
    """CLI 覆盖值为 None 视为未提供，不覆盖低层。"""
    monkeypatch.setenv("RAINOTE_OUTPUT", "json")
    cfg = Config.load({"output": None}, toml_path=tmp_path / "x.toml")
    assert cfg.output == "json"


def test_env_token(monkeypatch, tmp_path):
    """RAINOTE_TOKEN 环境变量注入 token。"""
    monkeypatch.setenv("RAINOTE_TOKEN", "env-token-123")
    cfg = Config.load(toml_path=tmp_path / "x.toml")
    assert cfg.token == "env-token-123"


def test_cli_token_overrides_env_token(monkeypatch, tmp_path):
    """CLI --token 覆盖 RAINOTE_TOKEN。"""
    monkeypatch.setenv("RAINOTE_TOKEN", "env-token")
    cfg = Config.load({"token": "cli-token"}, toml_path=tmp_path / "x.toml")
    assert cfg.token == "cli-token"


# ---------- NO_COLOR ----------

@pytest.mark.parametrize("var", ["NO_COLOR", "RAINOTE_NO_COLOR"])
def test_no_color_env_equivalent(monkeypatch, tmp_path, var):
    """NO_COLOR / RAINOTE_NO_COLOR 存在时等价于 --no-color。"""
    for v in ("NO_COLOR", "RAINOTE_NO_COLOR"):
        monkeypatch.delenv(v, raising=False)
    monkeypatch.setenv(var, "1")
    cfg = Config.load(toml_path=tmp_path / "x.toml")
    assert cfg.no_color is True


def test_no_color_cli_overrides_env(monkeypatch, tmp_path):
    """--no-color 显式 True 即使无 NO_COLOR 也生效。"""
    for v in ("NO_COLOR", "RAINOTE_NO_COLOR"):
        monkeypatch.delenv(v, raising=False)
    cfg = Config.load({"no_color": True}, toml_path=tmp_path / "x.toml")
    assert cfg.no_color is True


# ---------- config.toml 损坏降级 ----------

def test_corrupt_toml_degrades_gracefully(monkeypatch, tmp_path):
    """config.toml 解析失败时降级为默认，不抛异常。"""
    for var in ("RAINOTE_BASE_URL", "RAINOTE_OUTPUT", "RAINOTE_TOKEN"):
        monkeypatch.delenv(var, raising=False)
    toml = tmp_path / "config.toml"
    toml.write_text("this is = = not valid = toml {{{", encoding="utf-8")
    cfg = Config.load(toml_path=toml)
    assert cfg.output == DEFAULT_OUTPUT  # 回退到默认


def test_toml_unknown_keys_ignored(monkeypatch, tmp_path):
    """config.toml 中未知键被忽略，不报错。"""
    for var in ("RAINOTE_OUTPUT",):
        monkeypatch.delenv(var, raising=False)
    toml = tmp_path / "config.toml"
    toml.write_text('output = "json"\nunknown_key = 42\n', encoding="utf-8")
    cfg = Config.load(toml_path=toml)
    assert cfg.output == "json"


def test_valid_outputs_constant():
    """VALID_OUTPUTS 含 json/csv/table。"""
    assert set(VALID_OUTPUTS) == {"json", "csv", "table"}
