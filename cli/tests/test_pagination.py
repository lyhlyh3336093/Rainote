"""分页参数序列化测试。

覆盖：``--page``/``--size``/``--order-by``/``--desc``/``--reasonable`` → RuoYi 后端
query string 约定（pageNum/pageSize/orderByColumn/isAsc/reasonable）。
"""

from __future__ import annotations

from rainote.client.pagination import PaginationParams


class TestPaginationParams:
    def test_full_params(self) -> None:
        """--page 2 --size 20 --order-by createTime --desc --reasonable。"""
        p = PaginationParams(
            page=2, size=20, order_by="createTime", is_asc="desc", reasonable=True
        )
        query = p.to_query()
        assert query == {
            "pageNum": 2,
            "pageSize": 20,
            "orderByColumn": "createTime",
            "isAsc": "desc",
            "reasonable": "true",
        }

    def test_asc_order(self) -> None:
        p = PaginationParams(page=1, size=10, order_by="createTime", is_asc="asc")
        query = p.to_query()
        assert query["isAsc"] == "asc"

    def test_page_only(self) -> None:
        p = PaginationParams(page=3, size=15)
        query = p.to_query()
        assert query == {"pageNum": 3, "pageSize": 15}

    def test_defaults_empty(self) -> None:
        """无任何参数 → 空 query。"""
        p = PaginationParams()
        assert p.to_query() == {}

    def test_reasonable_false_omitted(self) -> None:
        """reasonable=False → 不传 reasonable 参数。"""
        p = PaginationParams(page=1, size=10, reasonable=False)
        query = p.to_query()
        assert "reasonable" not in query

    def test_reasonable_true_string(self) -> None:
        """reasonable=True → reasonable="true"（字符串）。"""
        p = PaginationParams(page=1, size=10, reasonable=True)
        query = p.to_query()
        assert query["reasonable"] == "true"

    def test_order_by_without_direction(self) -> None:
        """--order-by 不带 --desc/--asc → 不传 isAsc。"""
        p = PaginationParams(page=1, size=10, order_by="createTime")
        query = p.to_query()
        assert query["orderByColumn"] == "createTime"
        assert "isAsc" not in query

    def test_desc_shortcut(self) -> None:
        """--desc → isAsc=desc。"""
        p = PaginationParams(page=1, size=10, is_asc="desc")
        assert p.to_query()["isAsc"] == "desc"

    def test_merge_with_extra(self) -> None:
        """分页参数可与额外 query 参数合并。"""
        p = PaginationParams(page=1, size=10)
        query = p.to_query(extra={"title": "笔记", "status": "active"})
        assert query == {
            "pageNum": 1,
            "pageSize": 10,
            "title": "笔记",
            "status": "active",
        }

    def test_string_page_size(self) -> None:
        """page/size 接受 int 或 str。"""
        p = PaginationParams(page="2", size="20")
        query = p.to_query()
        assert query["pageNum"] == "2"
        assert query["pageSize"] == "20"
