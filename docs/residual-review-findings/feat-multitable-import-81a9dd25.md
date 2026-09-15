# 残留审查发现 — feat/multitable-import（Excel 导入）

来源：ce-code-review 运行 excel-import-20260914-163253（11 审查员，26 项主发现，17+3 项已修复应用）
状态：6 项设计决策类发现经用户确认接受为已知残余，随分支交付；可修复类与 pre-existing 归属校验已在 fix(review) 提交中应用。

## 已接受的设计决策类残余

- **#8 重算编排量级放大（P1，计划已标"中高"已知风险）** — `NoteDwtableExcelImportServiceImpl.executeRecompute`：每 lookup 列对全表存量记录无差别 select+update，"值未变跳过 update"未实现——10 万记录 × 5 lookup 列可推演事务内数百万条 SQL、分钟级事务并全程持锁。缓解方向已记录于计划 Risks：实测事务时长设监控门槛；重算方法值比对跳过 update；超门槛再决策分批/异步化。触发条件：仅大表（数万存量记录 × 多 lookup 列）导入时显现；小表导入事务秒级。
- **#18 两阶段列级分析平行实现（P2，advisory）** — 预检 `analyzeColumns` 与导入 `buildImportPlan` 内联分析是两套独立实现，接口 javadoc"同一私有方法链"声明与实际不符。漂移检查正确性依赖两套实现判定口径一致（当前一致，无对照测试锁定）。后续改动列分析逻辑时必须两侧同步；建议未来提取单一"列分析器"或补两阶段一致性对照测试。
- **#21 选项集修改后 default 失配阻断列保存（P3，advisory）** — 单选/多选列修改选项集删除了默认值选项时，回显发射的旧 default 使整个列保存被校验拒绝（fail-safe 方向，无数据损坏）。用户需三步操作（清默认→改选项→重设默认）。可在后续迭代改为"继承产生的 default 失配自动清除"。
- **#22 relationCandidates 100 条上限（P3，advisory）** — 被关联表超过 100 条记录时，sort 100 之外的目标记录在选择器中不可见不可选，未命中名只能留空。根本解（归属校验过的远程搜索端点）属后续迭代；短期可提高 CANDIDATE_LIMIT 并按响应体积封顶。
- **#23 重算列级吞异常无失败信号（P3，advisory，KTD8 已知局限的边界扩大）** — `recomputeSetOperationColumn` 列级 catch 吞整列重算失败，导入成功响应无派生列失败信号。与既有 recompute 方法同模式；后续可加失败计数日志/响应提示。
- **#25 KTD6 路由判定内联 Controller 层（P3，manual）** — NoteColumnController.edit 内联 isDefaultOnlyChange 判定并直接依赖 impl 包工具类，偏离 RuoYi 薄壳惯例且 originColumn 双查。功能正确（归属校验已由第二轮修复统一到端点入口）；重构方向：判定下沉 service。

## 已知残余风险（审查确认，计划 Risks 已记录）

- R25 张力：漂移/补参异常消息内嵌记录名会经 GlobalExceptionHandler `log.error` 进入服务端日志（sys_oper_log 已被 @Log 双 isSave=false 阻断；服务端日志未脱敏——SQL 导入先例同类张力）。
- 大事务超 600s 前端超时窗口：服务端继续执行，用户处于未知状态（防重复文案已引导；导入非幂等无系统级防护）。
- TOCTOU 窄窗口（前置段快照→事务段间隙记录被删）：漂移校验已覆盖列删除方向，记录删除方向依赖 FOR UPDATE 锁内复核。
- 并发 upsert 间隙锁重复创建 / 导入大事务与 UI 死锁对：MySQL 回滚一方可重试（计划已知接受）。
- 分片合并后逻辑表上限 50000 已修，但 10MB 恶意多 entry 解压放大（zip 总量上限未实现，仅单元格总数 100 万间接约束）——完整 zip 预检方案归后续安全迭代。

## 交付前手测清单（需运行环境）

1. ExcelUtil 导入路径回归（POI 5.4 升级影响，如系统用户 Excel 导入一次）
2. Excel 导出下载正常
3. 前端全流程：干净文件直导 / 缺参弹框逐值补参 / 轻量确认分支 / 漂移失败关闭弹框 / 上万分组渲染上限与过滤 / .sql 与 .zip 既有路径回归
4. 含中文补参的完整导入（multipart params 编码）
