"""U1 端点注册表测试 —— 完整性与关键端点路径/方法正确性。

锁定计划中经核实的"修正"端点，防止回归。
"""

from __future__ import annotations

import pytest

from rainote.client.endpoints import (
    ENDPOINTS,
    get_endpoint,
    total_endpoint_count,
    destructive_operations,
)


# ---------- 完整性 ----------

def test_registry_has_six_controllers():
    """注册表含 6 个 Phase 1 控制器。"""
    assert set(ENDPOINTS.keys()) == {"note", "dwtable", "column", "record", "block", "notelink"}


def test_total_endpoint_count_ge_60():
    """端点总数 >= 60（U1 验证门槛）。实际 74。"""
    assert total_endpoint_count() >= 60


@pytest.mark.parametrize("controller,expected_min", [
    ("note", 20),
    ("dwtable", 8),
    ("column", 10),
    ("record", 12),
    ("block", 9),
    ("notelink", 8),
])
def test_controller_endpoint_counts(controller, expected_min):
    """各控制器端点数符合预期。"""
    assert len(ENDPOINTS[controller]) >= expected_min


def test_get_endpoint_raises_on_unknown():
    """未知端点抛 KeyError 且消息可读。"""
    with pytest.raises(KeyError, match="未知端点"):
        get_endpoint("note", "nonexistent_op")


def test_get_endpoint_raises_on_unknown_controller():
    with pytest.raises(KeyError, match="未知端点"):
        get_endpoint("unknownctrl", "list")


# ---------- 关键端点方法/路径锁定（计划的"修正"点） ----------

def test_note_add_is_post_add_path():
    """note create: POST /system/note/add（非 /）。"""
    spec = get_endpoint("note", "add")
    assert spec.http_method == "POST"
    assert spec.path_template == "/system/note/add"
    assert spec.body_required is True


def test_note_update_is_post_user_update():
    """note update: POST /system/note/user/update（非 PUT，路径 /user/update）。"""
    spec = get_endpoint("note", "update")
    assert spec.http_method == "POST"
    assert spec.path_template == "/system/note/user/update"


def test_note_clear_garbage_path():
    """note garbage clear: /clearGarbage/{ids}（非 /remove）。"""
    spec = get_endpoint("note", "clear_garbage")
    assert spec.path_template == "/system/note/clearGarbage/{ids}"
    assert spec.path_params == ("ids",)


def test_note_remove_is_get():
    """note delete: GET /system/note/remove/{ids}（后端用 GET）。"""
    spec = get_endpoint("note", "remove")
    assert spec.http_method == "GET"
    assert spec.path_template == "/system/note/remove/{ids}"


def test_note_recover_is_destructive():
    """note recover: 破坏性（强制预检）+ @PreAuthorize 缺失。"""
    spec = get_endpoint("note", "recover")
    assert spec.destructive is True
    assert "缺失" in spec.note


def test_dwtable_get_data_path():
    """dwtable get-data: /data/{id}（非 /getData/{id}）。"""
    spec = get_endpoint("dwtable", "get_data")
    assert spec.path_template == "/system/dwtable/data/{id}"


def test_dwtable_update_is_post_edit():
    """dwtable update: POST /edit（非 PUT）。"""
    spec = get_endpoint("dwtable", "update")
    assert spec.http_method == "POST"
    assert spec.path_template == "/system/dwtable/edit"


def test_column_update_is_post_update():
    """column update: POST /update（非 PUT，非 /edit）。"""
    spec = get_endpoint("column", "update")
    assert spec.http_method == "POST"
    assert spec.path_template == "/system/column/update"


def test_column_update_sort_note_record_vo():
    """column updateSort: 注释标注接收 NoteRecordVo 代码异味。"""
    spec = get_endpoint("column", "update_sort")
    assert "NoteRecordVo" in spec.note


def test_column_deduplicate_is_get():
    """column deduplicate: GET（与项目记忆'必须 POST'冲突，CLI 以代码为准）。"""
    spec = get_endpoint("column", "deduplicate")
    assert spec.http_method == "GET"
    assert spec.destructive is True


def test_record_get_data_path():
    """record get-data: /data/{id}（非 /getData/{id}）。"""
    spec = get_endpoint("record", "get_data")
    assert spec.path_template == "/system/record/data/{id}"


def test_record_backfill_names_exists():
    """record backfillNames 存在且是管理员端点。"""
    spec = get_endpoint("record", "backfill_names")
    assert spec.http_method == "POST"
    assert spec.path_template == "/system/record/backfillNames"
    assert spec.destructive is True
    assert "backfill" in spec.note


def test_block_update_batch_body_is_array_note():
    """block updateBatch: 注释标注 body 是数组。"""
    spec = get_endpoint("block", "update_batch")
    assert "数组" in spec.note


def test_notelink_add_is_post_root():
    """notelink create: POST /system/notelink（根路径，无 /add）。"""
    spec = get_endpoint("notelink", "add")
    assert spec.http_method == "POST"
    assert spec.path_template == "/system/notelink"


def test_notelink_update_is_put_root():
    """notelink update: PUT /system/notelink（根路径）。"""
    spec = get_endpoint("notelink", "update")
    assert spec.http_method == "PUT"
    assert spec.path_template == "/system/notelink"


def test_notelink_remove_is_delete():
    """notelink delete: DELETE /system/notelink/{ids}（唯一用 DELETE 的控制器）。"""
    spec = get_endpoint("notelink", "remove")
    assert spec.http_method == "DELETE"
    assert spec.path_template == "/system/notelink/{ids}"


def test_notelink_cell_two_path_params():
    """notelink cell: 两个路径参数 {linkColumnId}/{linkItemId}。"""
    spec = get_endpoint("notelink", "cell")
    assert spec.path_params == ("linkColumnId", "linkItemId")


# ---------- 破坏性操作 ----------

def test_destructive_operations_includes_note_remove_and_recover():
    """破坏性操作列表含 note.remove 与 note.recover（KTD2 强制预检）。"""
    destructive = set(destructive_operations())
    assert ("note", "remove") in destructive
    assert ("note", "recover") in destructive
    assert ("note", "clear_garbage") in destructive


def test_destructive_operations_excludes_read_only():
    """破坏性操作列表不含只读操作。"""
    destructive = set(destructive_operations())
    assert ("note", "get") not in destructive
    assert ("note", "page_list") not in destructive
    assert ("notelink", "list") not in destructive


# ---------- source 注释（漂移检测） ----------

def test_all_specs_have_source():
    """每条端点都有 source 标注（漂移检测用）。"""
    for controller, ops in ENDPOINTS.items():
        for op, spec in ops.items():
            assert spec.source, f"{controller}.{op} 缺 source 标注"
            assert ".java:" in spec.source, f"{controller}.{op} source 缺行号: {spec.source}"
