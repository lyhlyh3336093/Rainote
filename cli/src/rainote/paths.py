"""路径与文件权限管理。

雨滴笔记 CLI 的运行时文件存放于 ``~/.rainote/``：
- ``credentials`` —— JWT token 凭据（权限 600，仅所有者可读写）
- ``config.toml`` —— 用户配置
- ``.gitignore`` —— 含 ``*``，防止误提交

Windows 注意：POSIX 的 ``0o600`` 在 Windows 上仅影响只读标志，无法真正限制访问。
因此 Windows 下额外调用 ``icacls`` 移除继承权限、仅保留当前用户；POSIX 下用
``os.chmod``。两者都为 best-effort，失败不阻断流程（凭据文件仍会创建）。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path


def rainote_home() -> Path:
    """返回 ``~/.rainote/`` 目录路径（不保证已创建）。

    若设置了 ``RAINOTE_HOME`` 环境变量，则使用其值（便于测试与自定义数据目录）。
    """
    override = os.environ.get("RAINOTE_HOME")
    if override:
        return Path(override)
    return Path.home() / ".rainote"


def credentials_path() -> Path:
    """返回 credentials 文件路径。"""
    return rainote_home() / "credentials"


def config_path() -> Path:
    """返回用户配置文件 ``~/.rainote/config.toml`` 路径。"""
    return rainote_home() / "config.toml"


def ensure_rainote_home() -> Path:
    """确保 ``~/.rainote/`` 存在，并生成 ``.gitignore``（含 ``*``）。

    目录权限在 POSIX 下设为 0o700。重复调用幂等：已存在时不报错、不覆盖已有
    ``.gitignore`` 之外的内容。
    """
    home = rainote_home()
    home.mkdir(parents=True, exist_ok=True)
    if os.name == "posix":
        try:
            os.chmod(home, 0o700)
        except OSError:
            pass
    gitignore = home / ".gitignore"
    if not gitignore.exists():
        gitignore.write_text("*\n", encoding="utf-8")
    return home


def restrict_file(path: Path) -> bool:
    """将文件权限限制为仅所有者可读写（0o600）。

    POSIX: ``os.chmod(path, 0o600)``。
    Windows: 调用 ``icacls`` 移除继承权限并仅授予当前用户完全控制（best-effort）。

    返回是否成功应用了限制。文件不存在时返回 False。
    """
    if not path.exists():
        return False

    if os.name == "posix":
        try:
            os.chmod(path, 0o600)
            return True
        except OSError:
            return False

    # Windows: 用 icacls 移除继承并仅保留当前用户
    if sys.platform == "win32":
        import subprocess

        user = os.environ.get("USERNAME") or os.environ.get("USER") or ""
        if not user:
            return False
        try:
            # /inheritance:r —— 移除继承的权限，仅保留显式授予的
            # /grant:r —— 替换该用户的权限为完全控制
            subprocess.run(
                ["icacls", str(path), "/inheritance:r", f"/grant:r", f"{user}:F"],
                check=True,
                capture_output=True,
                timeout=10,
            )
            return True
        except (subprocess.CalledProcessError, FileNotFoundError, OSError):
            return False

    return False
