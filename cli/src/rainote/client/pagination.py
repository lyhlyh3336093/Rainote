"""分页参数序列化 —— CLI 分页选项 → RuoYi 后端 query string 约定。

RuoYi 后端 PageDomain 约定：
  - ``pageNum``：页码（从 1 起）
  - ``pageSize``：每页条数
  - ``orderByColumn``：排序字段
  - ``isAsc``：排序方向（``asc`` / ``desc``）
  - ``reasonable``：分页合理化（``true`` 时页码越界自动纠正）
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass
class PaginationParams:
    """分页参数。

    :ivar page: 页码（``--page``）→ ``pageNum``
    :ivar size: 每页大小（``--size``）→ ``pageSize``
    :ivar order_by: 排序字段（``--order-by``）→ ``orderByColumn``
    :ivar is_asc: 排序方向（``--desc`` → ``desc``、``--asc`` → ``asc``）→ ``isAsc``
    :ivar reasonable: 分页合理化（``--reasonable``）→ ``reasonable=true``
    """

    page: int | str | None = None
    size: int | str | None = None
    order_by: str | None = None
    is_asc: str | None = None
    reasonable: bool = False

    def to_query(self, extra: dict[str, Any] | None = None) -> dict[str, Any]:
        """序列化为后端 query 参数 dict。

        :param extra: 额外 query 参数（如筛选条件 ``title=xxx``），追加在分页参数之后
        :return: 合并后的 query 参数 dict；仅包含非 None 的参数

        ``reasonable=False`` 时不输出 ``reasonable`` 参数；``True`` 时输出 ``"true"``（字符串）。
        """
        query: dict[str, Any] = {}
        if self.page is not None:
            query["pageNum"] = self.page
        if self.size is not None:
            query["pageSize"] = self.size
        if self.order_by is not None:
            query["orderByColumn"] = self.order_by
        if self.is_asc is not None:
            query["isAsc"] = self.is_asc
        if self.reasonable:
            query["reasonable"] = "true"
        if extra:
            query.update(extra)
        return query
