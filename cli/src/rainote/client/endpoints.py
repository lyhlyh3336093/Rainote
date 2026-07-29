"""手写端点注册表（KTD4）。

放弃 OpenAPI codegen：18 个 Note*Controller 全部零 ``@ApiOperation`` 注解，
``SwaggerConfig.java:59`` 的 ``withMethodAnnotation(ApiOperation.class)`` 过滤器
导致 ``/v3/api-docs`` 不含 Note 端点。故手写映射，注释标注源文件:行号便于漂移检测。

结构：``{controller: {operation_key: EndpointSpec}}``。命令实现仅声明
``(controller, operation_key)`` + 参数，由 :mod:`rainote.client.http` 统一调度。

每条端点信息从后端 Controller Java 文件逐条核实（2026-07-29），行号对应
``ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/`` 下的源文件。

注意若干后端代码事实（CLI 以代码为准，非 REST 惯例）：
- note/dwtable/record/block 的 remove 用 GET（非 DELETE），仅 notelink remove 用 DELETE
- notelink add 用 POST 根路径（无 /add），update 用 PUT 根路径
- dwtable/record 的 getData 路径是 ``/data/{id}``（方法名 getData 但 URL 是 /data）
- column update 路径是 ``/update``（非 /edit），dwtable/block 的 update 路径是 ``/edit``
- column updateSort 接收 ``NoteRecordVo``（非 NoteColumnVo，疑似 copy-paste bug）
- column deduplicate 是 GET（与项目记忆"必须 POST"冲突，CLI 以代码为准）
- note update 路径是 ``/user/update``，方法 POST（非 PUT）
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

# 标准 RuoYi 分页查询参数（TableSupport.java:16-36）
PAGINATION_PARAMS: tuple[str, ...] = (
    "pageNum",
    "pageSize",
    "orderByColumn",
    "isAsc",
    "reasonable",
)

ResponseType = Literal["ajax_result", "table_page", "raw"]


@dataclass(frozen=True)
class EndpointSpec:
    """单个后端端点的规格说明。

    Attributes:
        http_method: HTTP 方法（GET/POST/PUT/DELETE）。
        path_template: 完整路径模板，路径参数用 ``{name}`` 占位，如 ``/system/note/{id}``。
        path_params: 路径参数名元组。
        query_params: 接受的查询参数名元组（分页端点含 PAGINATION_PARAMS）。
        response_type: 响应类型 —— ``ajax_result``（AjaxResult: code/msg/data）、
            ``table_page``（TableDataInfo: rows/total）、``raw``（二进制流，如 export）。
        source: 源文件:行号，用于漂移检测，如 ``NoteNoteController.java:225``。
        body_required: 是否需要 JSON body。
        destructive: 破坏性操作（KTD2 强制 /getInfo 预检不可跳过）。
        note: 额外说明（安全/代码异味提示）。
    """

    http_method: str
    path_template: str
    source: str
    response_type: ResponseType = "ajax_result"
    path_params: tuple[str, ...] = field(default_factory=tuple)
    query_params: tuple[str, ...] = field(default_factory=tuple)
    body_required: bool = False
    destructive: bool = False
    note: str = ""


def _page(query: tuple[str, ...] = ()) -> tuple[str, ...]:
    """返回分页参数 + 额外查询参数。"""
    return PAGINATION_PARAMS + query


# ============================== note ==============================
# NoteNoteController.java — 类级 @RequestMapping("/system/note")
NOTE: dict[str, EndpointSpec] = {
    "list": EndpointSpec("GET", "/system/note/list", "NoteNoteController.java:48",
                         query_params=_page(), note="@PreAuthorize note:note:query"),
    "search_note_list": EndpointSpec("GET", "/system/note/searchNoteList", "NoteNoteController.java:66",
                                     note="@PreAuthorize note:note:query"),
    "menu_list": EndpointSpec("GET", "/system/note/menuList", "NoteNoteController.java:78",
                              note="@PreAuthorize note:note:query"),
    "menu_auth_list": EndpointSpec("GET", "/system/note/menuAuthList", "NoteNoteController.java:91",
                                   note="@PreAuthorize note:note:query"),
    "user_data_list": EndpointSpec("GET", "/system/note/userDataList", "NoteNoteController.java:104",
                                   note="@PreAuthorize note:note:query"),
    "page_list": EndpointSpec("GET", "/system/note/pageList", "NoteNoteController.java:118",
                              response_type="table_page", query_params=_page(),
                              note="@PreAuthorize note:note:query"),
    "garbage_list": EndpointSpec("GET", "/system/note/garbageList", "NoteNoteController.java:132",
                                 note="@PreAuthorize note:note:query"),
    "template_list": EndpointSpec("GET", "/system/note/templateList", "NoteNoteController.java:146",
                                  note="@PreAuthorize note:note:query"),
    "export": EndpointSpec("POST", "/system/note/export", "NoteNoteController.java:161",
                           response_type="raw", body_required=True,
                           note="@PreAuthorize note:note:export"),
    "import": EndpointSpec("POST", "/system/note/import", "NoteNoteController.java:175",
                           body_required=True, note="@PreAuthorize note:note:export"),
    "get": EndpointSpec("GET", "/system/note/{id}", "NoteNoteController.java:192",
                        path_params=("id",),
                        note="code=200 + 无 data 键 = 未找到（AjaxResult data==null 不 put data 键）"),
    "get_data": EndpointSpec("GET", "/system/note/data/{id}", "NoteNoteController.java:203",
                             path_params=("id",)),
    "get_dwtables": EndpointSpec("GET", "/system/note/getDWTables/{id}", "NoteNoteController.java:214",
                                 path_params=("id",)),
    "add": EndpointSpec("POST", "/system/note/add", "NoteNoteController.java:225",
                        body_required=True, note="后端强制覆盖 auth=getUserId()，CLI 传 auth 被忽略"),
    "update": EndpointSpec("POST", "/system/note/user/update", "NoteNoteController.java:237",
                           body_required=True, note="@PreAuthorize note:note:edit；方法 POST 非 PUT；路径 /user/update"),
    "save_as_new": EndpointSpec("POST", "/system/note/saveAsNewNote", "NoteNoteController.java:250",
                                body_required=True, note="@PreAuthorize note:note:edit"),
    "update_first_login": EndpointSpec("GET", "/system/note/updateFirstLogin/{userId}",
                                       "NoteNoteController.java:261", path_params=("userId",)),
    "remove": EndpointSpec("GET", "/system/note/remove/{ids}", "NoteNoteController.java:273",
                           path_params=("ids",), destructive=True,
                           note="GET 写操作；ids 逗号拼 URL；@PreAuthorize note:note:delete"),
    "clear_garbage": EndpointSpec("GET", "/system/note/clearGarbage/{ids}", "NoteNoteController.java:286",
                                  path_params=("ids",), destructive=True,
                                  note="路径 /clearGarbage 非 /remove；@PreAuthorize note:note:delete"),
    "remove_all": EndpointSpec("GET", "/system/note/removeAll", "NoteNoteController.java:298",
                               destructive=True, note="管理员高危；@PreAuthorize note:note:delete"),
    "remove_all_data": EndpointSpec("GET", "/system/note/removeAllData", "NoteNoteController.java:315",
                                    destructive=True, note="管理员高危；@PreAuthorize note:note:delete"),
    "recover": EndpointSpec("GET", "/system/note/recoverNote/{ids}", "NoteNoteController.java:332",
                            path_params=("ids",), destructive=True,
                            note="@PreAuthorize 缺失 + permitAll → 匿名风险，README 需警告"),
    "collection_list": EndpointSpec("GET", "/system/note/collectionList", "NoteNoteController.java:342"),
    "cancel_collection": EndpointSpec("GET", "/system/note/cancelCollection/{id}",
                                      "NoteNoteController.java:354", path_params=("id",), destructive=True,
                                      note="@PreAuthorize 缺失"),
    "collection": EndpointSpec("GET", "/system/note/collectionNote/{id}", "NoteNoteController.java:363",
                               path_params=("id",), destructive=True, note="@PreAuthorize 缺失"),
    "set_template": EndpointSpec("GET", "/system/note/setTemplate/{id}", "NoteNoteController.java:375",
                                 path_params=("id",), destructive=True, note="@PreAuthorize 缺失；@Log 存在"),
    "remove_template": EndpointSpec("GET", "/system/note/removeTemplate/{id}", "NoteNoteController.java:387",
                                    path_params=("id",), destructive=True, note="@PreAuthorize 缺失；@Log 存在"),
}

# ============================== dwtable ==============================
# NoteDwtableController.java — 类级 @RequestMapping("/system/dwtable")
DWTABLE: dict[str, EndpointSpec] = {
    "page_list": EndpointSpec("GET", "/system/dwtable/pageList", "NoteDwtableController.java:44",
                              response_type="table_page", query_params=_page()),
    "list": EndpointSpec("GET", "/system/dwtable/list", "NoteDwtableController.java:57",
                         query_params=("noteId", "name", "url")),
    "export": EndpointSpec("POST", "/system/dwtable/export", "NoteDwtableController.java:69",
                           response_type="raw", body_required=True),
    "get": EndpointSpec("GET", "/system/dwtable/{id}", "NoteDwtableController.java:81",
                        path_params=("id",)),
    "get_data": EndpointSpec("GET", "/system/dwtable/data/{id}", "NoteDwtableController.java:92",
                             path_params=("id",), note="方法名 getData 但 URL 路径是 /data/{id}"),
    "add": EndpointSpec("POST", "/system/dwtable/add", "NoteDwtableController.java:106",
                        body_required=True),
    "update": EndpointSpec("POST", "/system/dwtable/edit", "NoteDwtableController.java:117",
                           body_required=True, note="方法 POST 非 PUT；路径 /edit；@PreAuthorize system:dwtable:edit"),
    "remove": EndpointSpec("GET", "/system/dwtable/remove/{ids}", "NoteDwtableController.java:127",
                           path_params=("ids",), destructive=True,
                           note="用 @RequestMapping(method=GET)；@PreAuthorize 被注释（L125）"),
}

# ============================== column ==============================
# NoteColumnController.java — 类级 @RequestMapping("/system/column")
COLUMN: dict[str, EndpointSpec] = {
    "list": EndpointSpec("GET", "/system/column/list", "NoteColumnController.java:48",
                         query_params=_page(("dwtableId",))),
    "column_list": EndpointSpec("GET", "/system/column/columnList", "NoteColumnController.java:61",
                                query_params=("dwtableId",), note="不分页"),
    "double_link_list": EndpointSpec(
        "GET", "/system/column/selectNoteDoubleLinkColumnList", "NoteColumnController.java:72",
        query_params=("dwtableId",)),
    "export": EndpointSpec("POST", "/system/column/export", "NoteColumnController.java:85",
                           response_type="raw", body_required=True),
    "get": EndpointSpec("GET", "/system/column/{id}", "NoteColumnController.java:97",
                        path_params=("id",)),
    "add": EndpointSpec("POST", "/system/column/add", "NoteColumnController.java:108",
                        body_required=True,
                        note="接收 NoteColumnVo（非 NoteColumn）；property 是 myHashMap，CLI 传 JSON object"),
    "update": EndpointSpec("POST", "/system/column/update", "NoteColumnController.java:128",
                           body_required=True, note="方法 POST 非 PUT；路径 /update 非 /edit"),
    "update_sort": EndpointSpec("POST", "/system/column/updateSort", "NoteColumnController.java:140",
                                body_required=True,
                                note="代码异味：接收 NoteRecordVo 而非 NoteColumnVo；CLI 按代码事实调用"),
    "remove": EndpointSpec("GET", "/system/column/remove/{ids}", "NoteColumnController.java:168",
                           path_params=("ids",), destructive=True,
                           note="用 @RequestMapping(method=GET)"),
    "deduplicate": EndpointSpec("GET", "/system/column/deduplicate", "NoteColumnController.java:179",
                                query_params=("columnId",), destructive=True,
                                note="GET 与项目记忆'必须 POST'冲突，CLI 以代码为准"),
}

# ============================== record ==============================
# NoteRecordController.java — 类级 @RequestMapping("/system/record")
RECORD: dict[str, EndpointSpec] = {
    "page_list": EndpointSpec("GET", "/system/record/pageList", "NoteRecordController.java:48",
                              response_type="table_page", query_params=_page()),
    "list": EndpointSpec("GET", "/system/record/list", "NoteRecordController.java:60",
                         query_params=("dwtableId", "viewId"), note="不分页，接收 NoteRecordVo"),
    "search_list": EndpointSpec("GET", "/system/record/searchList", "NoteRecordController.java:72"),
    "data_list": EndpointSpec("GET", "/system/record/dataList", "NoteRecordController.java:84"),
    "export": EndpointSpec("POST", "/system/record/export", "NoteRecordController.java:97",
                           response_type="raw", body_required=True),
    "get": EndpointSpec("GET", "/system/record/{id}", "NoteRecordController.java:109",
                        path_params=("id",)),
    "get_data": EndpointSpec("GET", "/system/record/data/{id}", "NoteRecordController.java:119",
                             path_params=("id",), note="方法名 getData 但 URL 路径是 /data/{id}"),
    "add": EndpointSpec("POST", "/system/record/add", "NoteRecordController.java:131",
                        body_required=True, note="接收 NoteRecordVo"),
    "update": EndpointSpec("POST", "/system/record/update", "NoteRecordController.java:158",
                           body_required=True, note="方法 POST 非 PUT；接收 NoteRecordVo；@Log 被注释（审计盲点）"),
    "update_sort": EndpointSpec("POST", "/system/record/updateSort", "NoteRecordController.java:188",
                                body_required=True, note="@Log 被注释（审计盲点）"),
    "remove": EndpointSpec("GET", "/system/record/remove/{ids}", "NoteRecordController.java:215",
                           path_params=("ids",), destructive=True,
                           note="用 @RequestMapping(method=GET)"),
    "backfill_names": EndpointSpec("POST", "/system/record/backfillNames", "NoteRecordController.java:228",
                                   destructive=True, note="管理员端点；@PreAuthorize system:record:backfill；CLI 强制 --confirm"),
}

# ============================== block ==============================
# NoteBlockController.java — 类级 @RequestMapping("/system/block")
BLOCK: dict[str, EndpointSpec] = {
    "list": EndpointSpec("GET", "/system/block/list", "NoteBlockController.java:41",
                         query_params=_page()),
    "export": EndpointSpec("POST", "/system/block/export", "NoteBlockController.java:54",
                           response_type="raw", body_required=True),
    "get": EndpointSpec("GET", "/system/block/{id}", "NoteBlockController.java:66",
                        path_params=("id",)),
    "add": EndpointSpec("POST", "/system/block/add", "NoteBlockController.java:77",
                        body_required=True, note="接收 NoteBlock entity"),
    "update": EndpointSpec("POST", "/system/block/update", "NoteBlockController.java:88",
                           body_required=True, note="方法 POST 非 PUT；接收 NoteBlock"),
    "link_to_dwtable": EndpointSpec("POST", "/system/block/linkToDwtable", "NoteBlockController.java:100",
                                    body_required=True, note="接收 NoteBlockVo"),
    "remove_link": EndpointSpec("POST", "/system/block/removeLink", "NoteBlockController.java:116",
                                body_required=True, note="接收 NoteBlockVo"),
    "update_batch": EndpointSpec("POST", "/system/block/updateBatch", "NoteBlockController.java:129",
                                 body_required=True, note="body 是 List<NoteBlock> 数组非对象"),
    "remove": EndpointSpec("GET", "/system/block/remove/{ids}", "NoteBlockController.java:146",
                           path_params=("ids",), destructive=True,
                           note="用 @RequestMapping(method=GET)"),
}

# ============================== notelink ==============================
# NoteNotelinkController.java — 类级 @RequestMapping("/system/notelink")
NOTELINK: dict[str, EndpointSpec] = {
    "list": EndpointSpec("GET", "/system/notelink/list", "NoteNotelinkController.java:41",
                         response_type="table_page", query_params=_page(),
                         note="@PreAuthorize system:notelink:list"),
    "export": EndpointSpec("POST", "/system/notelink/export", "NoteNotelinkController.java:54",
                           response_type="raw", body_required=True,
                           note="@PreAuthorize system:notelink:export"),
    "get": EndpointSpec("GET", "/system/notelink/{id}", "NoteNotelinkController.java:66",
                        path_params=("id",), note="@PreAuthorize system:notelink:query"),
    "cell": EndpointSpec("GET", "/system/notelink/cell/{linkColumnId}/{linkItemId}",
                         "NoteNotelinkController.java:77",
                         path_params=("linkColumnId", "linkItemId"),
                         note="@PreAuthorize system:notelink:query；按单元格查询"),
    "by_note": EndpointSpec("GET", "/system/notelink/byNote/{noteId}", "NoteNotelinkController.java:89",
                            path_params=("noteId",),
                            note="@PreAuthorize system:notelink:query；按笔记查询"),
    "add": EndpointSpec("POST", "/system/notelink", "NoteNotelinkController.java:101",
                        body_required=True, note="POST 根路径（无 /add）；@PreAuthorize system:notelink:add"),
    "update": EndpointSpec("PUT", "/system/notelink", "NoteNotelinkController.java:117",
                           body_required=True, note="PUT 根路径；@PreAuthorize system:notelink:edit"),
    "remove": EndpointSpec("DELETE", "/system/notelink/{ids}", "NoteNotelinkController.java:128",
                           path_params=("ids",), destructive=True,
                           note="DELETE /{ids}；@PathVariable Long[] ids（Spring 自动按逗号转 Long 数组）；@PreAuthorize system:notelink:remove"),
}

# 注册表总入口
ENDPOINTS: dict[str, dict[str, EndpointSpec]] = {
    "note": NOTE,
    "dwtable": DWTABLE,
    "column": COLUMN,
    "record": RECORD,
    "block": BLOCK,
    "notelink": NOTELINK,
}


def get_endpoint(controller: str, operation: str) -> EndpointSpec:
    """取端点规格。不存在时抛 KeyError（带可读消息）。"""
    try:
        return ENDPOINTS[controller][operation]
    except KeyError:
        available = ", ".join(sorted(ENDPOINTS.get(controller, {}).keys()))
        raise KeyError(
            f"未知端点: {controller}.{operation}。{controller} 可用操作: {available}"
        ) from None


def total_endpoint_count() -> int:
    """注册表端点总数（U1 验证用：应 >= 60）。"""
    return sum(len(ops) for ops in ENDPOINTS.values())


def destructive_operations() -> list[tuple[str, str]]:
    """返回所有破坏性操作 (controller, operation) 列表（KTD2 强制预检用）。"""
    return [
        (ctrl, op)
        for ctrl, ops in ENDPOINTS.items()
        for op, spec in ops.items()
        if spec.destructive
    ]
