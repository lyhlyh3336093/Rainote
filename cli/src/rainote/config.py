"""四层配置优先级。

优先级（高 → 低）：
1. CLI 全局选项（``--base-url``/``--output``/``--json``/``--token`` 等）
2. 环境变量（``RAINOTE_BASE_URL``/``RAINOTE_OUTPUT``/``RAINOTE_TOKEN``/``NO_COLOR``/``RAINOTE_NO_COLOR``）
3. ``~/.rainote/config.toml``
4. 内置默认

config.toml 示例::

    base_url = "http://localhost:8080"
    output = "table"
    no_color = false
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Any

from .paths import config_path


DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_OUTPUT = "table"

VALID_OUTPUTS = ("json", "csv", "table")


@dataclass
class Config:
    """CLI 运行配置。所有字段均可被四层中任意一层覆盖。"""

    base_url: str = DEFAULT_BASE_URL
    output: str = DEFAULT_OUTPUT
    token: str | None = None
    no_color: bool = False
    verbose: bool = False
    allow_anonymous: bool = False

    def __post_init__(self) -> None:
        if self.output not in VALID_OUTPUTS:
            raise ValueError(
                f"无效的 output 值 {self.output!r}，可选：{VALID_OUTPUTS}"
            )
        # base_url 去除尾部斜杠，避免拼接出双斜杠
        self.base_url = self.base_url.rstrip("/")

    # ---- 各层加载 ----

    @classmethod
    def _defaults(cls) -> dict[str, Any]:
        """第 4 层：内置默认。"""
        return {f.name: f.default for f in fields(cls)}

    @classmethod
    def _from_toml(cls, path: Path | None = None) -> dict[str, Any]:
        """第 3 层：~/.rainote/config.toml。文件不存在时返回空 dict。"""
        path = path or config_path()
        if not path.exists():
            return {}
        try:
            with open(path, "rb") as fh:
                data = tomllib.load(fh)
        except (OSError, tomllib.TOMLDecodeError):
            # config.toml 损坏时降级为空，不阻断启动
            return {}
        return {k: v for k, v in data.items() if k in {f.name for f in fields(cls)}}

    @classmethod
    def _from_env(cls) -> dict[str, Any]:
        """第 2 层：环境变量。"""
        out: dict[str, Any] = {}
        if v := os.environ.get("RAINOTE_BASE_URL"):
            out["base_url"] = v
        if v := os.environ.get("RAINOTE_OUTPUT"):
            out["output"] = v
        if v := os.environ.get("RAINOTE_TOKEN"):
            out["token"] = v
        # NO_COLOR 是事实标准（https://no-color.org/）；RAINOTE_NO_COLOR 为补充别名
        if "NO_COLOR" in os.environ or "RAINOTE_NO_COLOR" in os.environ:
            out["no_color"] = True
        return out

    @classmethod
    def load(
        cls,
        cli_overrides: dict[str, Any] | None = None,
        toml_path: Path | None = None,
    ) -> "Config":
        """按四层优先级合并并返回 Config。

        ``cli_overrides`` 中值为 ``None`` 的字段视为"未提供"，不覆盖低层。
        ``toml_path`` 用于测试注入自定义 config.toml 路径；默认取 config_path()。
        """
        cli_overrides = cli_overrides or {}
        merged = cls._defaults()
        merged.update(cls._from_toml(toml_path))
        merged.update(cls._from_env())
        # 第 1 层：仅取非 None 的 CLI 覆盖
        for key, val in cli_overrides.items():
            if val is not None:
                merged[key] = val
        return cls(**merged)
