# 2026-10-15-001 导入链接列表头结构重建与缺参弹框

origin: docs/brainstorms（ce-debug 2026-10-14 收敛的结构缺口：导出文件的目标表身份不在 Excel 中，导入到无该列的表时只能建普通文本列）
base: feat/rainote-agent @ ee51ea75（链接列值往返已落地）

## Requirements

- R1：Excel 导入（替换式/补充式）遇表头 `X_文本`+`X_ID` **成对出现**、且目标表不存在名为 X 的 18/21/25 列时，识别为"待建双向链接列"（pendingLinkColumn），不再静默建普通文本列。
- R2：预检结果新增 pendingLinkColumns 分组（每项：裸名 X、textHeader、idHeader、建议配对列名"X的双向链接"、候选目标表全列表）；**未完成赋值则阻断确认**（零写入）。
- R3：确认请求携带 assignments（headerName→targetTableId，action=link|skip）；link=按原生 21 创建语义建列：本表 X 列 + 目标表配对列（名自动派生"X的双向链接"）+ property 互写 back_field_id/back_field_name/table_id/table_name + 两侧为既有记录补空 item（对齐 NoteColumnServiceImpl.insertNoteColumn 的 21 分支 L162-209）；建列后 `X_文本`/`X_ID` 值走现有 21 列路径（ID 优先+名称回退+对称写回）；skip=按现状未匹配文本列处理。
- R4：防重防御：目标表已有名为"X"或"X的双向链接"的列 → 失败清单报错整表零写入（提示改裸名）。
- R5：agent 路径无法交互：pending 形态表头进 linkFailures（"缺少目标表参数，无法自动建立双向链接列，请改走界面导入"），零写入；18 列/25 列/既有 21 列行为完全不变（回归红线）。

## DTO 契约（U1/U2 共同遵循）

- `ExcelImportPrecheckResult.pendingLinkColumns: List<PendingLinkColumn>`，字段：`headerName`(X)、`textHeader`、`idHeader`、`suggestedPairedName`、`candidateTables: List<{id,name}>`；`hasBlockingIssues` 将"存在 pending 且请求未带对应 assignment"计为阻断。
- 导入确认请求新增 `linkColumnAssignments: List<{headerName, targetTableId, action}>`。
- 预检接口原样返回候选表（库内全部 note_dwtable），前端下拉渲染。

## 设计决策

- 只在 `_文本`+`_ID` 成对出现时识别——与原生 21 导出形态一致，误判面最小；单独 `X_文本`/`X_ID` 维持现状（文本列）。
- 配对列名自动派生，不提供改名（与原生创建路径一致，保持界面/导入两条路径产物同构）。
- 建列在导入事务内按原生同语义实现（insertNoteColumn+updateNoteColumn+insertNoteDwtableItem），不抽公共方法（原生入口是控制器 vo 路径，耦合 myHashMap，抽公共属重构，超范围）。
- 弹框交互复用 ImportPrecheckModal 既有分组+确认门禁模式；每列一个目标表下拉 + "跳过（按文本列导入）"选项；`{ immediate: true }` watch 约定不触碰。

## Implementation Units

- U1 后端：ExcelColumnMatcher 形态识别（未匹配表头成对判定）→ 预检 pendingLinkColumns+候选表 → assignments 消费与校验（R2/R4）→ 事务内建列（R3）→ 值路径复用 → agent 降级（R5）→ 测试（含 18/既有 21/25 回归）。
- U2 前端：import.ts 类型、ImportPrecheckModal 新分组（渲染上限 50 复用 LINK_FAILURE_RENDER_LIMIT 模式）、canConfirm 门禁、确认请求携带 assignments。
- U3：全量回归（ruoyi-system,ruoyi-admin test + notepad build）+ 提交推送主线。

## Test scenarios（U1 级）

1. `X_文本`+`X_ID` 成对、X 不存在、赋值目标表 → 建两列互写 property、既有两侧记录补空 item、值按 ID 重建+对称写回、跳转可用
2. 同上但 action=skip → 两个文本列，无 21 结构
3. 未赋值确认 → 阻断零写入；赋值非法表 id → 失败清单
4. 目标表已有同名配对列 → 失败清单零写入
5. 只有 `X_文本` 或只有 `X_ID` → 现状文本列（不识别）
6. agent 路径 pending → linkFailures 零写入
7. 回归：既有 21 列往返、18 列机制、25 列、缺参选择器、级联删除——全部不变
