"""token 活性探测与 credentials 管理（KTD2）。

后端 JWT 不含 ``exp`` claim（``TokenService.createToken`` L172-178），过期由 Redis TTL
（``expireTime=3000`` 即 3000 分钟 / 50 小时）管理。因此 CLI 无法离线判断有效期，
通过调用 ``GET /getInfo`` 探测 token 活性。

分流逻辑：
- 非破坏性命令：距上次成功探测 < 5 分钟时跳过 /getInfo 调用
- 破坏性命令（force=True）：强制预检不可跳过
- code 200 → token 有效，更新 last_probe_at
- code 401 → token 失效，标记 credentials 失效，退出码 2（AUTH）
- 网络失败（超时/连接错误）→ 退出码 10（NETWORK），**不标记 token 失效**

注意：后端 /getInfo 仅在 token 剩余 ≤ 20 分钟时才 refresh 续签，CLI 不依赖"调用即续签"假设。
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional

import httpx

from ..errors import ExitCode
from ..paths import credentials_path, ensure_rainote_home, restrict_file

# 非破坏性命令的探测跳过窗口（秒）
PROBE_INTERVAL = 300  # 5 分钟


@dataclass
class Credentials:
    """credentials 文件内容。"""

    token: str
    username: Optional[str] = None
    expires_at: Optional[float] = None  # JWT 无 exp，保留字段供未来扩展
    login_at: Optional[float] = None
    last_probe_at: Optional[float] = None


class CredentialsCorruptError(Exception):
    """credentials 文件损坏（JSON 解析失败）。"""


@dataclass
class ProbeResult:
    """token 探测结果。"""

    active: bool
    exit_code: Optional[int] = None  # None 表示无错误


def _path(path: Path | None = None) -> Path:
    return path or credentials_path()


def load_credentials(path: Path | None = None) -> Credentials | None:
    """读取 credentials 文件。文件不存在返回 None；损坏抛 CredentialsCorruptError。

    token 为空（已标记失效）时返回 None。
    """
    p = _path(path)
    if not p.exists():
        return None
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        raise CredentialsCorruptError(
            "credentials 文件损坏，请重新 `rainote login` 或设置 RAINOTE_TOKEN"
        ) from exc
    if not data.get("token"):
        return None  # 已标记失效
    return Credentials(
        token=data["token"],
        username=data.get("username"),
        expires_at=data.get("expires_at"),
        login_at=data.get("login_at"),
        last_probe_at=data.get("last_probe_at"),
    )


def save_credentials(creds: Credentials, path: Path | None = None) -> None:
    """写入 credentials 文件（权限 600）。"""
    ensure_rainote_home()
    p = _path(path)
    p.write_text(
        json.dumps(asdict(creds), ensure_ascii=False), encoding="utf-8"
    )
    restrict_file(p)


def mark_credentials_invalid(path: Path | None = None) -> None:
    """标记 credentials 失效：删除文件。"""
    p = _path(path)
    if p.exists():
        try:
            p.unlink()
        except OSError:
            pass


def probe_token(
    client: httpx.Client,
    *,
    force: bool = False,
    path: Path | None = None,
) -> ProbeResult:
    """探测 token 活性（GET /getInfo）。

    Args:
        client: 已配置鉴权头的 httpx.Client。
        force: True 表示破坏性命令强制预检（不可跳过）。
        path: credentials 文件路径（测试注入）。

    Returns:
        ProbeResult(active, exit_code)。exit_code 为 None 表示无错误。
    """
    p = _path(path)
    try:
        creds = load_credentials(p)
    except CredentialsCorruptError:
        # credentials 损坏：提示重登
        return ProbeResult(active=False, exit_code=ExitCode.AUTH)

    # 非破坏性命令 + 最近探测过 → 跳过 /getInfo 调用
    if (
        not force
        and creds is not None
        and creds.last_probe_at is not None
        and (time.time() - creds.last_probe_at) < PROBE_INTERVAL
    ):
        return ProbeResult(active=True, exit_code=None)

    # 调用 /getInfo 探测
    try:
        resp = client.get("/getInfo")
        body = resp.json()
    except (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError):
        # 网络错误 → 退出码 10，不标记 token 失效
        return ProbeResult(active=False, exit_code=ExitCode.NETWORK)

    code = body.get("code")
    if code == 200:
        # 更新 last_probe_at
        if creds is not None:
            creds.last_probe_at = time.time()
            save_credentials(creds, p)
        return ProbeResult(active=True, exit_code=None)
    if code == 401:
        mark_credentials_invalid(p)
        return ProbeResult(active=False, exit_code=ExitCode.AUTH)
    # 其他业务码视为服务错误
    return ProbeResult(active=False, exit_code=ExitCode.SERVER)


def get_effective_token(
    config=None,
    path: Path | None = None,
) -> Optional[str]:
    """解析有效 token，优先级：CLI --token（config.token）> RAINOTE_TOKEN 环境变量 > credentials。"""
    if config is not None and config.token:
        return config.token
    env_token = os.environ.get("RAINOTE_TOKEN")
    if env_token:
        return env_token
    try:
        creds = load_credentials(path)
    except CredentialsCorruptError:
        return None
    return creds.token if creds is not None else None
