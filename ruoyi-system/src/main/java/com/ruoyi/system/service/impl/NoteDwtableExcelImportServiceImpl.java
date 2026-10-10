package com.ruoyi.system.service.impl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.agent.annotation.AgentParam;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.NoteView;
import com.ruoyi.system.domain.dto.ExcelImportAgentResult;
import com.ruoyi.system.domain.dto.ExcelImportParams;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.AmbiguityItem;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.DefaultValueFill;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.FileFingerprint;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.LinkFailure;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissingParam;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissName;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.NewOption;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RecordCandidate;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.ReplaceImpact;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.DeletedColumnInfo;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RepointedLinkColumn;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.WillClearLinkEntry;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RelationCandidates;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SkippedHeader;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SymmetricWriteImpact;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNotelinkMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.mapper.NoteViewMapper;
import com.ruoyi.system.service.INoteDwtableExcelImportService;
import com.ruoyi.system.service.INoteRecordService;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.ColumnMapping;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.HeaderEntry;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.SheetSelection;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.ParsedSheet;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.RowData;

/**
 * 多维表格 Excel 导入服务（U4 阶段一预检 + U5 阶段二导入写入；<b>替换式语义</b>）。
 * <p>
 * 替换式语义：导入成功后目标表现有数据行全部删除、列集合对齐 Excel——同名列保留配置只换数据，
 * Excel 独有表头自动新建纯文本列，表中 Excel 未含的非系统列（含公式/查找/隐藏列）删除，
 * 被删 21 双向关联列在被关联表的配对列级联删除，其他表指向本表的关联列入链按
 * "旧 item→新 item"映射精准重指向（取代原整列清空，U5）。
 * <p>
 * 预检流程（R9/R10/R11/R14/R15/R23/R24/R25，KTD4/KTD5）：
 * <ol>
 *   <li>查目标表（防御性复查 noteId 匹配，调用端已做过归属校验）；</li>
 *   <li>{@link ExcelWorkbookReader#parse} 安全解析全部 sheet；</li>
 *   <li>{@link ExcelColumnMatcher#selectSheet} 选定逻辑表（目标表名）；
 *       无任何非空表头 → 拒绝（防误删）；</li>
 *   <li>查目标表全部列定义 → {@link ExcelColumnMatcher#mapColumns} 映射
 *       （传全部列，隐藏列参与匹配、排除类型列由 matcher 过滤）；</li>
 *   <li>对每个映射到的 18/21 关联列：解析 property 取 table_id → R23 归属校验
 *       （被关联表须存在、与目标表同 noteId 且过 {@link AgentOwnershipChecker}，
 *       失败抛 {@link ServiceException} 含列名与原因，阻断整次预检）→
 *       KTD4 批量预解析（一次查被关联表全部 NoteRecord，按 sort 升序构建
 *       name → 有序记录列表，同名多条保留全部：首条=预选，size&gt;1=歧义）；</li>
 *   <li>逐行汇总：缺参项（缺值/类型违规/关联未命中）、歧义项、默认值填充项、新选项创建项、
 *       对称写入影响、忽略提示；</li>
 *   <li>替换影响面（{@link ReplaceImpact}）：将删除的行/列、将新建的列、跨表入链重指向
 *       （将重指向的列/单元格数 + 将单元格级清空并报告的条目，U6 语义迁移）与取消隐藏清单
 *       ——删除类与跨表入链影响字段非空/非零即前端必须弹框确认；</li>
 *   <li>文件指纹（KTD5：size + md5）随结果返回，供阶段二比对。</li>
 * </ol>
 * 文本匹配（KTD4 整串优先）：单元格文本（trim）先整串匹配记录名，命中即用（不拆逗号）；
 * 未命中再按英文逗号拆分逐个（trim）匹配。每个未命中的名字（distinct）收集行号集。
 * 空单元格不建立关联、不算缺参不算未命中（R15）。
 * <p>
 * 21 列双轨制（U3，R5/R7-R9）：{@code 列名_ID} 锚点列参与消费——单元格先按 `_ID` 逐 id 校验
 * 目标记录存在性，全命中即按 ID 精确重建（名称被外部改写以 ID 为准）；`_ID` 缺失/未命中位置
 * 按同位名称条目经 {@link LinkNameResolver} 回退（空名称条目=U1 悬空导出回读的死链，跳过
 * 不失败）；歧义/未命中进链接失败清单（与缺参清单并列阻断，确认前零写入）。18 列的
 * `列名_文本` 机制、缺参记录选择器与同名取 sort 最小维持不变（回归红线）。
 * <p>
 * 25 列双轨制（U4，R2/R6-R9）：语义关联列移出排除集参与导入——{@code 列名_ID} 载体为
 * NoteNotelink 行 id，单元格先按 `_ID` 逐条目校验行存在性（旧行核心字段按行 id 捕获备用），
 * 命中且<b>归属当前列与本表</b>（linkColumnId/linkDwTableId 校验——防 `_ID` 越权改写他列/
 * 他表行与旧版导出文件 _ID=笔记 id 撞行错配）才按行 id 恢复原行，同列重复 `_ID` 检出进
 * 失败清单；`_ID` 缺失/未命中按同位名称条目（"引用笔记名（锚词）"语法）经
 * {@link LinkNameResolver} 对当前用户可见笔记标题（auth 归属过滤，admin 通行）回退
 * （空名称条目=悬空导出回读的死链，跳过不失败）；
 * 歧义/未命中进同一链接失败清单（多值任一失败整表零写入，R9）。执行段 (h3)（21 对称写回之后、
 * 跨表重算之前）逐列重建：先按列 {@code deleteNoteNotelinkByColumnId} 清旧行，`_ID` 命中按行 id
 * upsert 恢复原行（保留行 id 与 blockId——源笔记锚点 data-link-id 不断链）、名称回退 insert 新行
 * （blockId=null 锚点降级、contextText=锚词、linkNoteId=本表归属笔记规范值）。
 * <p>
 * 预检不触碰任何写路径（零 insert/update mapper 调用）；
 * 日志只记录列名/行号/跳过原因等元数据，不含单元格值（R25）。
 * <p>
 * 导入流程（U5，R16-R26，KTD3/KTD5/KTD8）：非事务前置段（指纹校验 → 同一私有方法链
 * 重新解析/映射/匹配 → params 解析 → 补参校验 R26 → 反向漂移 fail-fast）→
 * {@code @Transactional} 写入段（替换阶段：入链捕获与重指向预判（全删前，U5）→
 * 全删本表行与 items → 级联删被删 21 列的配对列 → 删旧列+清孤儿 notelink →
 * 建新列 → 取消隐藏；写入阶段：逐行 NoteRecord + items 批量 ≥500 flush + 选项追加 +
 * 全量最终 flush → 入链重指向（建新落库回填新 id 后、21 对称写回之前，取代原整列清空）→
 * 双链对称写入 + 重算编排——重算无条件执行，仅表头无数据行的清空场景也要跑）→
 * 返回 recordCount；失败整体回滚（R22）。
 * <p>
 * 入链重指向（U5，R11-R13，2026-10-10 裁决）：全删前按 linkRecordId 全量反查捕获他表
 * 指向本表旧记录的 21/18 入链（不复用列扫描，防属性残缺入链漏捕）；建新落库后以
 * Excel 首列 record_id + 回填新 id 构造"旧 item→新 item"精确映射，Java 侧读-改-写
 * （FOR UPDATE 锁读现值→拆 CSV 串→逐 id 映射→去重合并→join，value 去重键=recordId
 * 保三串等长）三字段重写 linkRecordId/linkItemId/value（value 刷新为新记录名；21 配对列
 * 与 18 入链同口径；禁集合式 SQL REPLACE——逗号串子串误替换）。record_id 精确映射未覆盖
 * （列缺失的外部手写文件全量 / 陈旧导出文件 id 失配）→ 名称回退（{@link LinkNameResolver}，
 * 歧义进失败清单零写入）；回退也失败（旧记录未出现在 Excel 中）→ 单元格级全清并报告
 * （理由注明"文件可能为旧版导出，建议重新导出"）；21 配对列中导入计划已无对侧互指声明的
 * 旧条目→条目级清空并报告（互指一致性；18 列单向、无条件重指向）；被级联删除的配对列
 * 条目跳过（对齐预检口径）。kept 配对列与他表 18 列
 * 不再整列清空；被删列的配对列级联删除语义不变；notelink 目标行处置归 25 列重建
 * （U4），重指向不碰 notelink。
 *
 * @author ruoyi
 */
@Service
public class NoteDwtableExcelImportServiceImpl implements INoteDwtableExcelImportService
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExcelImportServiceImpl.class);

    /** 六类基础列（R7 默认值支持范围，亦为缺参分析范围） */
    private static final Set<Long> BASIC_TYPES = new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L, 7L));

    /** 单选 */
    private static final long TYPE_SINGLE_SELECT = 3L;

    /** 多选 */
    private static final long TYPE_MULTI_SELECT = 4L;

    /** 复选框 */
    private static final long TYPE_CHECKBOX = 7L;

    /** lookup 列 */
    private static final long TYPE_LOOKUP = 26L;

    /** 集合运算列 */
    private static final long TYPE_SET_OPERATION = 24L;

    /** 关联列选择器候选首批上限（按 sort 升序前 N 条，KTD7） */
    private static final int CANDIDATE_LIMIT = 100;

    /** NoteDwtableItem 批量 insert 单批上限（单元格数，对齐 SQL 导入 BATCH_LIMIT） */
    private static final int BATCH_LIMIT = 500;

    /** 每列每次导入新选项上限（R19，超限中止回滚） */
    private static final int NEW_OPTION_LIMIT = 100;

    /** 导入参数单段条目数上限（columnValues / relationSelections 各自适用，防超大 params 放大） */
    private static final int PARAM_ENTRIES_LIMIT = 10000;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    /** 替换式导入：被删列的孤儿语义链接清理（byColumnId）+ 25 列重建（U4） */
    @Autowired
    private NoteNotelinkMapper noteNotelinkMapper;

    /** 25 列名称回退的候选笔记标题来源（U4/R6：候选=库内笔记标题，一次查全量） */
    @Autowired
    private NoteNoteMapper noteNoteMapper;

    /** 导入记录的目标视图解析（网格 dataList 按视图 viewId 过滤记录） */
    @Autowired
    private NoteViewMapper noteViewMapper;

    @Autowired
    private AgentOwnershipChecker agentOwnershipChecker;

    /** 重算编排入口（KTD8：经 Spring 代理调用加入导入事务） */
    @Autowired
    private INoteRecordService noteRecordService;

    /** 共享名称解析组件（U2，R7：21 列名称回退的转义还原/整串优先/归一化/唯一前缀/歧义判定） */
    @Autowired
    private LinkNameResolver linkNameResolver;

    /**
     * 自注入代理引用：导入入口（非事务前置段）完成后经代理进入 {@code @Transactional}
     * 写入段（同类自调用不经代理，事务由本字段承载；注入具体类依赖 CGLIB 代理，
     * 与 {@code NoteDwtableServiceImpl} 注入 {@code NoteViewServiceImpl} 同一先例）。
     */
    @Autowired
    private NoteDwtableExcelImportServiceImpl self;

    /** 供单元测试注入未代理实例（生产环境由 Spring 注入代理） */
    void setSelf(NoteDwtableExcelImportServiceImpl self)
    {
        this.self = self;
    }

    // ==================== Agent 操作（dwtable.importExcel，对称 dwtable.importSql） ====================

    /**
     * Agent 操作入口：导入 Excel（xlsx）文件到指定多维表格（<b>替换式语义</b>，与界面导入等价），
     * 返回成功/失败双态结果（{@link ExcelImportAgentResult}）。
     * <p>
     * agent 为单次计划模型（执行结果不回传 LLM 重试），与界面两阶段交互的差异处理：
     * <ul>
     *   <li>文件来源：LLM 无法提供二进制内容，{@code filePath} 须为 {@code /common/upload}
     *       上传返回的 {@code fileName}（{@code /profile/upload/...} 路径），服务端从磁盘读取
     *       （路径解析限定 profile/upload 根内，防穿越；大小超 {@link ExcelWorkbookReader#MAX_FILE_SIZE} 拒绝）；</li>
     *   <li>缺参语义：先复用预检管线（同请求内预检即导入，指纹天然一致），基础列缺参
     *       （缺值/类型违规）仅当 {@code columnValues} 覆盖全部时才执行导入，
     *       否则<b>零写入</b>返回结构化缺参清单——LLM 将清单呈现给用户，用户在对话中
     *       提供补值后由 LLM 生成带 {@code columnValues} 的第二次调用；</li>
     *   <li>关联列未命中无法文本补值（须选择被关联表记录 id），一律进失败清单，
     *       提示用户改走界面导入；</li>
     *   <li>21/25 列链接解析失败（U3/U4，R10）：`_ID` 未命中且名称歧义/未命中为强失败，
     *       零写入返回结构化失败清单（与界面共用 DTO），修正指引按失败类型区分
     *       （单元格条目失败修正链接名称或 _ID；入链重指向歧义保留 record_id 首列
     *       或消除重名）后重导；</li>
     *   <li>歧义（同名多条，18 列）：按 sort 靠前预选建立关联（与界面预检默认一致），不阻断，
     *       成功结果中报告歧义处理清单；</li>
     *   <li>列默认值/新选项/对称写入等静默变更与<b>替换影响</b>（删除行/删除列/新建列/
     *     跨表入链重指向与单元格级清空报告）在成功结果中报告（与界面预检清单对齐）。</li>
     * </ul>
     * 事务：本方法不加 {@code @Transactional}（对齐 HTTP 控制器直调 {@code importExcelData}，
     * 非事务前置段 + 写入段经自注入代理进入事务）；归属校验 + noteId 匹配断言自带（KTD6 对称）。
     *
     * @param noteId       目标笔记 id（LLM 提供）
     * @param dwtableId    目标多维表格 id（LLM 提供）
     * @param filePath     上传文件路径（LLM 提供，/common/upload 返回的 fileName）
     * @param columnValues 缺参列补值，键=列名、值=统一补值（LLM 提供，可选）
     * @param userId       框架注入的当前用户 id（不暴露给 LLM）
     * @return 成功/失败双态结果（失败=未执行任何写入）
     */
    @AgentOperation(name = "dwtable.importExcel", destructive = true,
            description = "导入 Excel（xlsx）文件到指定多维表格（替换式：成功后目标表现有数据行全部删除，列集合对齐 Excel——"
                    + "同名列保留配置只换数据，Excel 独有表头自动新建文本列，表中 Excel 未含的列被删除（系统列除外），"
                    + "被删双向关联列的配对列一并删除；他表指向本表记录的入链将按 record_id 映射重指向或单元格级清空），"
                    + "返回成功/失败双态结果。双向关联(21)与语义关联(25)列参与导入并重建链接（按 _ID 精确恢复或按名称回退）。"
                    + "文件须先经 /common/upload 上传，filePath 传其返回的 fileName（/profile/upload/... 路径）。"
                    + "有基础列缺参（缺值/类型违规）时仅当 columnValues 全部覆盖才执行导入，否则零写入并返回缺参清单"
                    + "（将清单告知用户，用户补充补值后带 columnValues 再次调用）。"
                    + "关联列未命中无法文本补值，须改走界面导入。"
                    + "双向关联(21)/语义关联(25)列链接解析失败（_ID 未命中且名称歧义/未命中）同样零写入，"
                    + "返回结构化失败清单（单元格定位/原因/修正指引——链接条目修正名称或 _ID；"
                    + "入链重指向歧义须保留 record_id 首列或消除重名）后重新导入。")
    public ExcelImportAgentResult importExcel(
            @AgentParam(value = "noteId", type = "long", required = true, description = "目标笔记 id")
            Long noteId,
            @AgentParam(value = "dwtableId", type = "long", required = true, description = "目标多维表格 id")
            Long dwtableId,
            @AgentParam(value = "filePath", type = "string", required = true,
                    description = "服务器上传文件路径（/common/upload 返回的 fileName，形如 /profile/upload/2026/09/16/数据.xlsx）")
            String filePath,
            @AgentParam(value = "columnValues", type = "object", required = false,
                    description = "缺参列补值：键=列名，值=统一补值（可为空串=显式补空），覆盖基础列缺参（缺值/类型违规）")
            Map<String, Object> columnValues,
            Long userId)
    {
        // KTD6 对称：agent 路径自带归属校验（admin 通行）+ noteId 匹配断言
        agentOwnershipChecker.checkDwtableOwnership(dwtableId, userId);
        MultipartFile file = readUploadedFile(filePath);
        // 同请求内预检即导入：文件指纹天然一致，无需两阶段回传
        ExcelImportPrecheckResult precheck = precheck(noteId, dwtableId, file, userId);

        // U3/U4（R8/R10）：21/25 列链接解析失败为强失败（无法补参覆盖）→ 结构化清单返回，零写入。
        // 清单与界面预检共用 DTO（单元格定位/原因/容错命中说明），修正指引按失败类型区分
        if (!precheck.getLinkFailures().isEmpty())
        {
            ExcelImportAgentResult result = new ExcelImportAgentResult();
            result.setSuccess(false);
            result.setLinkFailures(precheck.getLinkFailures());
            result.setMessage(linkFailureMessage(precheck.getLinkFailures()));
            log.info("[EXCEL-AGENT] 链接解析失败拒绝导入 noteId={}, dwtableId={}, userId={}, linkFailures={}",
                    noteId, dwtableId, userId, precheck.getLinkFailures().size());
            return result;
        }

        Map<String, String> fills = normalizeColumnValues(columnValues);
        List<MissingParam> uncovered = new ArrayList<>();
        for (MissingParam param : precheck.getMissingParams())
        {
            if (param == null)
            {
                continue;
            }
            boolean link = ExcelImportPrecheckResult.KIND_LINK_MISS.equals(param.getKind());
            if (link || !fills.containsKey(param.getColumnName()))
            {
                uncovered.add(param);
            }
        }
        if (!uncovered.isEmpty())
        {
            ExcelImportAgentResult result = new ExcelImportAgentResult();
            result.setSuccess(false);
            result.setMissingParams(uncovered);
            result.setMessage("导入未执行（零写入）：有 " + uncovered.size() + " 项缺参未覆盖。"
                    + "基础列缺参可通过 columnValues 提供补值后再次调用；"
                    + "关联列缺参（关联未命中）无法文本补值，请改走界面导入。");
            log.info("[EXCEL-AGENT] 缺参未覆盖拒绝导入 noteId={}, dwtableId={}, userId={}, uncovered={}",
                    noteId, dwtableId, userId, uncovered.size());
            return result;
        }

        ExcelImportParams params = buildAgentParams(precheck, fills);
        int count = importExcelData(noteId, dwtableId, file, JSONObject.toJSONString(params), userId);

        ExcelImportAgentResult result = new ExcelImportAgentResult();
        result.setSuccess(true);
        result.setImportedCount(Integer.valueOf(count));
        result.setDefaultValueFills(precheck.getDefaultValueFills());
        result.setNewOptions(precheck.getNewOptions());
        result.setSymmetricWriteImpact(precheck.getSymmetricWriteImpact());
        result.setAmbiguityApplied(precheck.getAmbiguityItems());
        result.setReplaceImpact(precheck.getReplaceImpact());
        ReplaceImpact replace = precheck.getReplaceImpact();
        result.setMessage("成功导入 " + count + " 条记录"
                + (replace == null ? "" : replaceSummary(replace))
                + (precheck.getNewOptions().isEmpty() ? "" : "，创建新选项 " + precheck.getNewOptions().size() + " 个")
                + (precheck.getAmbiguityItems().isEmpty() ? "" : "，歧义记录已按 sort 靠前预选建立关联 " + precheck.getAmbiguityItems().size() + " 项")
                + (precheck.getSymmetricWriteImpact().isEmpty() ? "" : "，双链对称写入影响 " + precheck.getSymmetricWriteImpact().size() + " 处"));
        log.info("[EXCEL-AGENT] 导入完成 noteId={}, dwtableId={}, userId={}, recordCount={}",
                noteId, dwtableId, userId, count);
        return result;
    }

    /**
     * agent 链接失败消息（U3/U4/U5 共用清单）：修正指引<b>按失败类型区分</b>——
     * 单元格条目失败（21/25 列名称歧义/未命中）指引修正 Excel 中的链接名称或 _ID；
     * 入链重指向名称歧义（reason 含"入链重指向"，重指向条目不在本表 Excel 中）指引
     * 保留 record_id 首列（在导出产物基础上修改）或消除重名。两类并存时合并指引。
     */
    private static String linkFailureMessage(List<LinkFailure> linkFailures)
    {
        boolean hasCellFailures = false;
        boolean hasRepointAmbiguity = false;
        for (LinkFailure failure : linkFailures)
        {
            if (failure == null || failure.getReason() == null)
            {
                continue;
            }
            if (failure.getReason().contains("入链重指向"))
            {
                hasRepointAmbiguity = true;
            }
            else
            {
                hasCellFailures = true;
            }
        }
        StringBuilder guidance = new StringBuilder();
        if (hasCellFailures)
        {
            guidance.append("请按清单修正 Excel 文件中的链接名称或 _ID 后重新导入");
        }
        if (hasRepointAmbiguity)
        {
            if (guidance.length() > 0)
            {
                guidance.append("；");
            }
            guidance.append("入链重指向歧义请在导出产物基础上修改以保留 record_id 首列，或消除重名后重新导入");
        }
        return "导入未执行（零写入）：有 " + linkFailures.size()
                + " 项链接解析失败（双向关联的目标记录/语义链接的引用笔记/入链重指向目标无法唯一确定），"
                + guidance;
    }

    /**
     * agent 成功消息的替换摘要（为 0/空的分句省略）。跨表入链影响按重指向口径
     * （U6 语义迁移）：重指向条目数按列聚合、清空条目逐条计数。
     */
    private static String replaceSummary(ReplaceImpact replace)
    {
        StringBuilder summary = new StringBuilder();
        if (replace.getDeletedRowCount() > 0)
        {
            summary.append("，替换：删除原 ").append(replace.getDeletedRowCount()).append(" 行");
        }
        if (!replace.getDeletedColumns().isEmpty())
        {
            summary.append("，删除 ").append(replace.getDeletedColumns().size()).append(" 列");
        }
        if (!replace.getNewColumnNames().isEmpty())
        {
            summary.append("，新建 ").append(replace.getNewColumnNames().size()).append(" 列");
        }
        int repointedEntries = 0;
        for (RepointedLinkColumn repointed : replace.getRepointedLinkColumns())
        {
            repointedEntries += repointed.getRepointedEntryCount();
        }
        if (repointedEntries > 0)
        {
            summary.append("，他表入链重指向 ").append(repointedEntries).append(" 条");
        }
        if (!replace.getWillClearLinkEntries().isEmpty())
        {
            summary.append("，清空他表悬空链接 ").append(replace.getWillClearLinkEntries().size()).append(" 条");
        }
        return summary.toString();
    }

    /**
     * 读取服务器上传文件为 {@link MultipartFile}：限定 {@code /profile/upload/} 前缀，
     * 解析到 profile/upload 根目录内（normalize 后 startsWith 校验，防目录穿越），
     * 大小超 {@link ExcelWorkbookReader#MAX_FILE_SIZE} 先拒绝（防大文件读入内存）。
     */
    private static MultipartFile readUploadedFile(String filePath)
    {
        if (filePath == null || filePath.trim().isEmpty())
        {
            throw new ServiceException("导入失败：filePath 不能为空（须为 /common/upload 返回的 fileName）");
        }
        String normalized = filePath.trim().replace('\\', '/');
        if (!normalized.startsWith("/profile/upload/"))
        {
            throw new ServiceException("导入失败：filePath 须为 /profile/upload/ 开头的上传文件路径");
        }
        Path root = Paths.get(RuoYiConfig.getProfile(), "upload").toAbsolutePath().normalize();
        Path target = root.resolve(normalized.substring("/profile/upload/".length())).toAbsolutePath().normalize();
        if (!target.startsWith(root))
        {
            throw new ServiceException("导入失败：filePath 包含非法路径（目录穿越），已拒绝");
        }
        if (!Files.isRegularFile(target))
        {
            throw new ServiceException("导入失败：上传文件不存在（" + normalized + "），请确认已通过 /common/upload 上传");
        }
        try
        {
            if (Files.size(target) > ExcelWorkbookReader.MAX_FILE_SIZE)
            {
                throw new ServiceException("导入失败：文件大小超过上限 10MB");
            }
            return new ByteArrayMultipartFile(target.getFileName().toString(), Files.readAllBytes(target));
        }
        catch (IOException e)
        {
            throw new ServiceException("导入失败：读取上传文件失败，请重试");
        }
    }

    /**
     * 归一化 LLM 提供的 columnValues（{@code Map<String,Object>} → {@code Map<String,String>}）：
     * 键 trim（与列名/补参校验口径一致）、值 toString（LLM 可能生成数字/布尔字面量），
     * null 值归一为空串（显式补空）；条目数超 {@link #PARAM_ENTRIES_LIMIT} 先拒绝。
     */
    private static Map<String, String> normalizeColumnValues(Map<String, Object> columnValues)
    {
        Map<String, String> fills = new LinkedHashMap<>();
        if (columnValues == null)
        {
            return fills;
        }
        for (Map.Entry<String, Object> entry : columnValues.entrySet())
        {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty())
            {
                continue;
            }
            Object value = entry.getValue();
            fills.put(key, value == null ? "" : String.valueOf(value));
        }
        if (fills.size() > PARAM_ENTRIES_LIMIT)
        {
            throw new ServiceException("导入失败：columnValues 条目数超限（" + PARAM_ENTRIES_LIMIT + "）");
        }
        return fills;
    }

    /**
     * 组装 agent 路径的阶段二参数：文件指纹（同请求预检结果直传）+ 预检基线
     * （缺参列名/未命中名/歧义预选/替换删除列，供漂移检查比对）+ LLM 补参。
     * 关联补参恒为空：agent 无法选择被关联表记录（未命中即失败态，歧义按预选）。
     */
    private static ExcelImportParams buildAgentParams(ExcelImportPrecheckResult precheck, Map<String, String> fills)
    {
        ExcelImportParams params = new ExcelImportParams();
        params.setFileFingerprint(precheck.getFileFingerprint());
        ExcelImportParams.PrecheckBaseline baseline = params.getPrecheckBaseline();
        Set<String> missingColumns = new LinkedHashSet<>();
        for (MissingParam param : precheck.getMissingParams())
        {
            if (param == null || param.getColumnName() == null)
            {
                continue;
            }
            missingColumns.add(param.getColumnName());
            if (param.getMissNames() != null)
            {
                for (MissName miss : param.getMissNames())
                {
                    if (miss == null || miss.getName() == null)
                    {
                        continue;
                    }
                    ExcelImportParams.BaselineName name = new ExcelImportParams.BaselineName();
                    name.setColumnName(param.getColumnName());
                    name.setName(miss.getName());
                    baseline.getMissNames().add(name);
                }
            }
        }
        baseline.setMissingColumns(new ArrayList<>(missingColumns));
        // 替换基线：预检时将删除/新建的列（阶段二漂移比对用）
        ReplaceImpact replace = precheck.getReplaceImpact();
        if (replace != null)
        {
            List<String> replaceDeleted = new ArrayList<>();
            for (DeletedColumnInfo deleted : replace.getDeletedColumns())
            {
                if (deleted != null && deleted.getColumnName() != null)
                {
                    replaceDeleted.add(deleted.getColumnName());
                }
            }
            baseline.setReplaceDeletedColumns(replaceDeleted);
            baseline.setReplaceNewColumns(new ArrayList<>(replace.getNewColumnNames()));
        }
        for (AmbiguityItem item : precheck.getAmbiguityItems())
        {
            if (item == null || item.getColumnName() == null || item.getName() == null)
            {
                continue;
            }
            ExcelImportParams.BaselineAmbiguity ambiguity = new ExcelImportParams.BaselineAmbiguity();
            ambiguity.setColumnName(item.getColumnName());
            ambiguity.setName(item.getName());
            ambiguity.setPreselectedRecordId(item.getPreselectedRecordId());
            baseline.getAmbiguity().add(ambiguity);
        }
        params.setColumnValues(fills);
        return params;
    }

    /**
     * 字节数组 → {@link MultipartFile} 适配器（agent 磁盘读文件后进入既有管线）。
     * {@code getBytes}/{@code getInputStream} 可重复读（预检指纹 + 阶段二指纹比对/解析共读多次）。
     */
    private static final class ByteArrayMultipartFile implements MultipartFile
    {
        private final String filename;
        private final byte[] content;

        ByteArrayMultipartFile(String filename, byte[] content)
        {
            this.filename = filename;
            this.content = content;
        }

        @Override
        public String getName()
        {
            return "file";
        }

        @Override
        public String getOriginalFilename()
        {
            return filename;
        }

        @Override
        public String getContentType()
        {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        @Override
        public boolean isEmpty()
        {
            return content.length == 0;
        }

        @Override
        public long getSize()
        {
            return content.length;
        }

        @Override
        public byte[] getBytes()
        {
            return content.clone();
        }

        @Override
        public InputStream getInputStream()
        {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException
        {
            try (InputStream in = getInputStream())
            {
                Files.copy(in, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override
    public ExcelImportPrecheckResult precheck(Long noteId, Long dwtableId, MultipartFile file, Long userId)
    {
        NoteDwtable dwtable = noteDwtableMapper.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("预检失败：多维表与笔记不匹配");
        }
        // 先算指纹（getBytes），再解析（getInputStream）——Tomcat multipart 对
        // getInputStream 后再 getBytes 在某些配置下失败（真实 HTTP 才触发，单测 mock 无法覆盖）
        ExcelImportPrecheckResult.FileFingerprint fp = fingerprint(file);
        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(file);
        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, dwtable.getName());
        verifyNonBlankHeaders(selection);

        NoteColumn query = new NoteColumn();
        query.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(query);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(selection.getHeaders(), columns);

        ExcelImportPrecheckResult result = new ExcelImportPrecheckResult();
        result.setFileFingerprint(fp);
        result.setIgnoredSheets(new ArrayList<>(selection.getIgnoredSheetNames()));
        appendSkippedHeaders(result, mapping, noteId, dwtableId);
        // U5 互指声明集（预检口径）：kept 21 列逐行解析目标，入链重指向影响面分类消费
        Map<Long, List<Set<Long>>> declaredTargetsByColumnId =
                analyzeColumns(result, noteId, dwtableId, userId, selection.getRows(), columns, mapping);
        buildReplaceImpact(result, noteId, dwtableId, columns, mapping);
        // U5（R12 裁决）+ U6：入链重指向预检分析——名称回退歧义进链接失败清单（与缺参并列
        // 阻断，确认前零写入）；重指向/单元格级清空影响面进 ReplaceImpact（呈现口径）
        analyzeInboundRepoint(result, noteId, dwtableId, selection, columns, mapping, declaredTargetsByColumnId);
        // U3（R8）：链接失败清单与缺参清单并列阻断（21 列强失败，确认前零写入）
        result.setHasBlockingIssues(!result.getMissingParams().isEmpty()
                || !result.getAmbiguityItems().isEmpty()
                || !result.getLinkFailures().isEmpty());

        log.info("[EXCEL-PRECHECK] 预检完成 noteId={}, dwtableId={}, userId={}, totalRows={}, missingParams={}, "
                        + "ambiguityItems={}, linkFailures={}, replaceImpact{{}行/{}删列/{}新列/{}重指向列/{}清空条目/{}取消隐藏}, "
                        + "defaultValueFills={}, newOptions={}, symmetricWriteImpact={}, ignoredSheets={}, skippedHeaders={}",
                noteId, dwtableId, userId, selection.getRows().size(),
                result.getMissingParams().size(), result.getAmbiguityItems().size(),
                result.getLinkFailures().size(),
                result.getReplaceImpact().getDeletedRowCount(), result.getReplaceImpact().getDeletedColumns().size(),
                result.getReplaceImpact().getNewColumnNames().size(),
                result.getReplaceImpact().getRepointedLinkColumns().size(),
                result.getReplaceImpact().getWillClearLinkEntries().size(),
                result.getReplaceImpact().getUnhiddenColumnNames().size(),
                result.getDefaultValueFills().size(), result.getNewOptions().size(),
                result.getSymmetricWriteImpact().size(), result.getIgnoredSheets().size(),
                result.getSkippedHeaders().size());
        return result;
    }

    /**
     * 空表头防误删 guard（预检与导入两阶段共用）：选定 sheet 无任何非空表头时，
     * 替换式导入将清空整表却无任何列可建，大概率选错文件/工作表——直接拒绝。
     */
    private static void verifyNonBlankHeaders(SheetSelection selection)
    {
        for (String header : selection.getHeaders())
        {
            if (header != null && !header.trim().isEmpty())
            {
                return;
            }
        }
        throw new ServiceException("选定工作表没有任何非空表头，为防止误删数据已拒绝导入（请检查是否选错了文件或工作表）");
    }

    // ==================== 列分析 ====================

    /**
     * 遍历参与导入的列（已映射到表头 且 类型 ∈ 六类基础 1/2/3/4/5/7 + 关联 18/21/25），
     * 逐列汇总预检清单。替换式语义：隐藏列映射即参与（导入时取消隐藏）；
     * 表头未映射的列将删除（进 replaceImpact），不再产生缺列/关联缺列缺参。
     * <p>
     * 关联列分流（U3/U4 双轨制）：18 走既有文本匹配（弹框补选救援不变）；
     * 21 走 ID 优先 + 名称回退（{@link #analyzeDoubleLinkColumn}，失败进链接失败清单强阻断）；
     * 25 走行 id 优先 + 笔记名回退（{@link #analyzeSemanticLinkColumn}，候选=库内全部笔记标题，
     * 失败同清单强阻断）。
     *
     * @return kept 21 列 id → 逐行声明的目标记录 id 集（U5 互指声明集预检口径，
     *         入链重指向影响面分类消费；与 buildImportPlan 的 declaredTargetsByColumnId 同构）
     */
    private Map<Long, List<Set<Long>>> analyzeColumns(ExcelImportPrecheckResult result, Long noteId,
            Long dwtableId, Long userId, List<RowData> rows, List<NoteColumn> columns, ColumnMapping mapping)
    {
        // 已映射列实例 → headerIndex（matcher 保证一个列至多被一个表头映射）
        Map<NoteColumn, Integer> headerIndexByColumn = new IdentityHashMap<>();
        for (HeaderEntry entry : mapping.getMappedEntries())
        {
            headerIndexByColumn.put(entry.getColumn(), Integer.valueOf(entry.getHeaderIndex()));
        }

        // U5 互指声明集（kept 21 列 id → 逐行声明目标记录 id 集，analyzeDoubleLinkColumn 填充）
        Map<Long, List<Set<Long>>> declaredTargetsByColumnId = new LinkedHashMap<>();

        // 被关联表上下文缓存（同表多列共享一次 KTD4 批量预解析）
        Map<Long, LinkTableContext> contextByTableId = new HashMap<>();

        // 25 列候选笔记上下文缓存（同一次预检共享一次全量笔记查询）
        SemanticNoteContext semanticNoteContext = null;

        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null)
            {
                continue;
            }
            Long type = column.getType();
            boolean link = type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK;
            boolean semantic = type == ExcelColumnMatcher.TYPE_SEMANTIC_LINK;
            if (!link && !semantic && !BASIC_TYPES.contains(type))
            {
                continue;
            }
            Integer headerIndex = headerIndexByColumn.get(column);
            if (headerIndex == null)
            {
                // 替换式语义：表头未映射的列将删除（replaceImpact.deletedColumns），
                // 不再产生缺列/关联缺列缺参，也不做默认值填充
                continue;
            }
            if (link)
            {
                if (type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
                {
                    // U3：21 列双轨制（ID 优先 + 名称回退，失败进链接失败清单）
                    analyzeDoubleLinkColumn(result, noteId, userId, column, headerIndex.intValue(),
                            mapping, rows, contextByTableId, declaredTargetsByColumnId);
                }
                else
                {
                    analyzeLinkColumn(result, noteId, userId, column, type, headerIndex.intValue(),
                            mapping, rows, contextByTableId);
                }
            }
            else if (semantic)
            {
                // U4：25 列双轨制（notelink 行 id 优先 + 笔记名回退，失败进链接失败清单）
                if (semanticNoteContext == null)
                {
                    semanticNoteContext = semanticNoteContext(userId);
                }
                analyzeSemanticLinkColumn(result, column, headerIndex.intValue(), mapping, rows,
                        dwtableId, semanticNoteContext);
            }
            else
            {
                analyzeBasicColumn(result, column, type, headerIndex.intValue(), mapping, rows);
            }
        }
        return declaredTargetsByColumnId;
    }

    /**
     * 基础列（1/2/3/4/5/7）逐行分析：缺值（默认值填充或缺参）、类型违规（缺参，附原因）、
     * 单选/多选未命中选项（新选项创建项）。
     * <p>
     * 同列缺值与类型违规合并为一条缺参（rowNumbers 取并集、kind 有违规时"类型违规"否则"缺值"、
     * reason 保留违规描述），避免同列两条缺参导致前端双行输入与 columnValues 同键覆盖。
     */
    private static void analyzeBasicColumn(ExcelImportPrecheckResult result, NoteColumn column, Long type,
            int headerIndex, ColumnMapping mapping, List<RowData> rows)
    {
        String defaultValue = ColumnDefaultValueSupport.read(column);
        Set<String> options = (type == TYPE_SINGLE_SELECT || type == TYPE_MULTI_SELECT)
                ? ColumnDefaultValueSupport.parseSelectOptions(column) : null;

        // 空单元格行号集（缺值分析，物理行号与 Excel UI 一致）
        TreeSet<Integer> emptyRows = new TreeSet<>();
        // 类型违规行号集（R11：违规按缺参走补值链路）
        TreeSet<Integer> violationRows = new TreeSet<>();
        String violationReason = null;
        // 未命中选项文本 → 出现次数（R19 新选项创建）
        Map<String, Integer> newOptionCounts = new LinkedHashMap<>();

        for (int r = 0; r < rows.size(); r++)
        {
            RowData row = rows.get(r);
            String text = mapping.cellText(row, headerIndex).trim();
            if (text.isEmpty())
            {
                emptyRows.add(Integer.valueOf(row.getRowNumber()));
                continue;
            }
            String violation = ExcelColumnMatcher.validateCellText(column, text);
            if (violation != null)
            {
                violationRows.add(Integer.valueOf(row.getRowNumber()));
                if (violationReason == null)
                {
                    violationReason = violation;
                }
                continue;
            }
            if (type == TYPE_SINGLE_SELECT)
            {
                // 单选整格匹配（R19）
                if (options != null && !options.contains(text))
                {
                    newOptionCounts.merge(text, Integer.valueOf(1), Integer::sum);
                }
            }
            else if (type == TYPE_MULTI_SELECT)
            {
                // 多选拆分逐个匹配（R19）：先全角逗号归一为半角（与选项集解析口径对称）
                for (String part : text.replace("，", ",").split(","))
                {
                    String candidate = part.trim();
                    if (!candidate.isEmpty() && options != null && !options.contains(candidate))
                    {
                        newOptionCounts.merge(candidate, Integer.valueOf(1), Integer::sum);
                    }
                }
            }
        }

        boolean missingEmptyRows = !emptyRows.isEmpty() && defaultValue == null;
        if (!emptyRows.isEmpty() && defaultValue != null)
        {
            // 有默认值的列不进弹框：默认值填充项（行数=空单元格行数）
            DefaultValueFill fill = new DefaultValueFill();
            fill.setColumnId(column.getId());
            fill.setColumnName(dtoColumnName(column));
            fill.setDefaultValue(defaultValue);
            fill.setRowCount(emptyRows.size());
            result.getDefaultValueFills().add(fill);
        }
        if (missingEmptyRows || !violationRows.isEmpty())
        {
            // 同列缺值+类型违规合并为一条（kind 有违规时"类型违规"，rowNumbers 取并集）
            MissingParam param = new MissingParam();
            param.setColumnId(column.getId());
            param.setColumnName(dtoColumnName(column));
            param.setColumnType(type);
            if (violationRows.isEmpty())
            {
                param.setKind(ExcelImportPrecheckResult.KIND_MISSING_VALUE);
                param.setRowNumbers(new ArrayList<>(emptyRows));
            }
            else
            {
                param.setKind(ExcelImportPrecheckResult.KIND_TYPE_VIOLATION);
                TreeSet<Integer> unionRows = new TreeSet<>(violationRows);
                if (missingEmptyRows)
                {
                    unionRows.addAll(emptyRows);
                }
                param.setRowNumbers(new ArrayList<>(unionRows));
                param.setReason(missingEmptyRows
                        ? violationReason + "；另有 " + emptyRows.size() + " 行缺值"
                        : violationReason);
            }
            result.getMissingParams().add(param);
        }
        for (Map.Entry<String, Integer> entry : newOptionCounts.entrySet())
        {
            NewOption option = new NewOption();
            option.setColumnId(column.getId());
            option.setColumnName(dtoColumnName(column));
            option.setOptionText(entry.getKey());
            option.setOccurrences(entry.getValue().intValue());
            result.getNewOptions().add(option);
        }
    }

    /**
     * 单向关联列（18）逐行分析（KTD4 整串优先；U3 起 18 专属——21 列走
     * {@link #analyzeDoubleLinkColumn} 双轨制）：命中收集歧义与对称写入影响，
     * 未命中名（distinct）收集行号集供 U6 逐值选择器；附选择器候选首批。
     */
    private void analyzeLinkColumn(ExcelImportPrecheckResult result, Long noteId, Long userId, NoteColumn column,
            Long type, int headerIndex, ColumnMapping mapping, List<RowData> rows,
            Map<Long, LinkTableContext> contextByTableId)
    {
        LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
        Map<String, List<NoteRecord>> recordsByName = context.getRecordsByName();

        // 未命中名 → 行号集（distinct 名，LinkedHashMap 保持首次出现顺序）
        Map<String, TreeSet<Integer>> missRowsByName = new LinkedHashMap<>();
        // 歧义名 → 同名记录列表（匹配中才登记）
        Map<String, List<NoteRecord>> ambiguityByName = new LinkedHashMap<>();

        for (int r = 0; r < rows.size(); r++)
        {
            RowData row = rows.get(r);
            String text = mapping.cellText(row, headerIndex).trim();
            if (text.isEmpty())
            {
                // R15：空单元格不建立关联，不算缺参不算未命中
                continue;
            }
            // KTD4 整串优先（candidateNames）：整串命中即返回整串（不拆逗号，处理记录名含逗号），
            // 未命中再按英文逗号拆分逐个（trim）匹配
            for (String name : candidateNames(text, recordsByName))
            {
                List<NoteRecord> matched = recordsByName.get(name);
                if (matched != null)
                {
                    collectMatch(name, matched, ambiguityByName);
                }
                else
                {
                    missRowsByName.computeIfAbsent(name, k -> new TreeSet<>())
                            .add(Integer.valueOf(row.getRowNumber()));
                }
            }
        }

        for (Map.Entry<String, List<NoteRecord>> entry : ambiguityByName.entrySet())
        {
            AmbiguityItem item = new AmbiguityItem();
            item.setColumnId(column.getId());
            item.setColumnName(dtoColumnName(column));
            item.setName(entry.getKey());
            item.setCandidateCount(entry.getValue().size());
            // 预选 sort 最靠前的同名记录（列表已按 sort 升序）
            item.setPreselectedRecordId(entry.getValue().get(0).getId());
            result.getAmbiguityItems().add(item);
        }
        if (!missRowsByName.isEmpty())
        {
            MissingParam param = new MissingParam();
            param.setColumnId(column.getId());
            param.setColumnName(dtoColumnName(column));
            param.setColumnType(type);
            param.setKind(ExcelImportPrecheckResult.KIND_LINK_MISS);
            List<MissName> missNames = new ArrayList<>(missRowsByName.size());
            for (Map.Entry<String, TreeSet<Integer>> entry : missRowsByName.entrySet())
            {
                MissName miss = new MissName();
                miss.setName(entry.getKey());
                miss.setRowNumbers(new ArrayList<>(entry.getValue()));
                missNames.add(miss);
            }
            param.setMissNames(missNames);
            result.getMissingParams().add(param);
        }
        // 选择器候选（U6 内嵌数据源）：按 sort 升序前 CANDIDATE_LIMIT 条
        emitRelationCandidates(result, column, context);
    }

    /**
     * 收集一次命中：同名多条登记歧义（R14）。
     */
    private static void collectMatch(String name, List<NoteRecord> matched,
            Map<String, List<NoteRecord>> ambiguityByName)
    {
        if (matched.size() > 1)
        {
            ambiguityByName.putIfAbsent(name, matched);
        }
    }

    /**
     * 发射关联列选择器候选（18/21 列预检共用，KTD7 内嵌数据源口径一致）：
     * 按 sort 升序前 {@link #CANDIDATE_LIMIT} 条（21 列已无补选入口，仅供前端兼容）。
     */
    private static void emitRelationCandidates(ExcelImportPrecheckResult result, NoteColumn column,
            LinkTableContext context)
    {
        RelationCandidates relationCandidates = new RelationCandidates();
        relationCandidates.setColumnId(column.getId());
        List<RecordCandidate> candidates = new ArrayList<>(
                Math.min(CANDIDATE_LIMIT, context.getSortedRecords().size()));
        for (int i = 0; i < context.getSortedRecords().size() && i < CANDIDATE_LIMIT; i++)
        {
            NoteRecord record = context.getSortedRecords().get(i);
            RecordCandidate candidate = new RecordCandidate();
            candidate.setRecordId(record.getId());
            candidate.setName(record.getName() == null ? "" : record.getName());
            candidates.add(candidate);
        }
        relationCandidates.setCandidates(candidates);
        result.getRelationCandidates().add(relationCandidates);
    }

    /**
     * 链接失败条目落地为预检 DTO（U3/U4 共用）：21/25 列单元格解析的失败条目按
     * （列、行号）定位组装 {@link LinkFailure}（R8 新分组，与缺参清单并列阻断）。
     */
    private static void appendLinkFailures(ExcelImportPrecheckResult result, NoteColumn column,
            int rowNumber, List<LinkEntryFailure> failures)
    {
        for (LinkEntryFailure failure : failures)
        {
            LinkFailure item = new LinkFailure();
            item.setColumnId(column.getId());
            item.setColumnName(dtoColumnName(column));
            item.setRowNumber(rowNumber);
            item.setEntryText(failure.entryText);
            item.setReason(failure.reason);
            item.setExplanation(failure.explanation);
            result.getLinkFailures().add(item);
        }
    }

    /**
     * `_ID` 锚点列单元格的逐 id 解析（U3/U4 双轨制共用）：逗号拆分、trim；空 token 记
     * null 占位（与同位名称条目等长对位——跳过会导致 `_ID` 与名称条目错位配对，如
     * ",5" 的名称条目 0 配到 id 5 上）；非法 token 亦记 null 占位（按未命中走名称回退）。
     */
    private static List<Long> parseIdEntries(String idText)
    {
        List<Long> ids = new ArrayList<>();
        if (idText != null && !idText.trim().isEmpty())
        {
            for (String part : idText.split(","))
            {
                String token = part.trim();
                Long id = null;
                if (!token.isEmpty())
                {
                    try
                    {
                        id = Long.valueOf(token);
                    }
                    catch (NumberFormatException e)
                    {
                        id = null;
                    }
                }
                ids.add(id);
            }
        }
        return ids;
    }

    // ==================== 21 列双轨解析（U3：ID 优先、名称回退） ====================

    /**
     * 双向关联列（21）逐行预检分析（U3，R5/R7-R9）：单元格先按 {@code _ID} 逐 id 校验
     * 目标记录存在性——全命中即通过（名称不参与，ID 为准）；{@code _ID} 缺失/未命中位置
     * 按同位名称条目经 {@link LinkNameResolver} 回退，歧义/未命中进<b>链接失败清单</b>
     * （R8 新分组，与缺参清单并列阻断，确认前零写入；多值任一条目失败整表零写入，R9）。
     * 对称写入影响与选择器候选的发射口径与 18 列现状一致（候选仅供前端兼容，21 列已无补选入口）。
     * 逐行解析目标同步登记互指声明集（U5：配对列旧条目存活 ⇔ 对侧行声明含该条目所在记录）。
     */
    private void analyzeDoubleLinkColumn(ExcelImportPrecheckResult result, Long noteId, Long userId,
            NoteColumn column, int headerIndex, ColumnMapping mapping, List<RowData> rows,
            Map<Long, LinkTableContext> contextByTableId, Map<Long, List<Set<Long>>> declaredTargetsByColumnId)
    {
        LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
        int idHeaderIndex = mapping.linkIdHeaderIndex(column);
        List<String> candidateNames = candidateNameList(context);

        // 命中的 distinct 记录 id（R11 影响面；ID 命中与名称回退命中并集）
        Set<Long> matchedRecordIds = new LinkedHashSet<>();
        // U5 互指声明集：逐行解析目标记录 id 集（与 buildImportPlan 的 declaredPerRow 同构）
        List<Set<Long>> declaredPerRow = new ArrayList<>(rows.size());
        for (RowData row : rows)
        {
            String idText = idHeaderIndex >= 0 ? mapping.cellText(row, idHeaderIndex) : "";
            String nameText = mapping.cellText(row, headerIndex);
            DoubleLinkCellResolution resolution = resolveDoubleLinkCell(idText, nameText, context, candidateNames);
            Set<Long> declared = new LinkedHashSet<>();
            for (NoteRecord record : resolution.records())
            {
                matchedRecordIds.add(record.getId());
                if (record != null && record.getId() != null)
                {
                    declared.add(record.getId());
                }
            }
            declaredPerRow.add(declared);
            appendLinkFailures(result, column, row.getRowNumber(), resolution.failures);
        }
        if (column.getId() != null)
        {
            declaredTargetsByColumnId.put(column.getId(), declaredPerRow);
        }

        // 对称写入影响（16/R11，口径与现状一致：命中的 distinct 记录数）
        if (!matchedRecordIds.isEmpty())
        {
            SymmetricWriteImpact impact = new SymmetricWriteImpact();
            impact.setTableName(context.getTable().getName());
            impact.setAffectedRecordCount(matchedRecordIds.size());
            result.getSymmetricWriteImpact().add(impact);
        }
        // 选择器候选（KTD7 发射口径与现状一致，供前端兼容）
        emitRelationCandidates(result, column, context);
    }

    /**
     * 21 列单元格解析（U3 双轨制，预检与执行两阶段共用；R5/R7-R9 + U1 悬空回读边界）。
     * <p>
     * 逐条目语义（{@code _ID} 与名称条目按导出顺序一一对应）：
     * <ul>
     *   <li>{@code _ID} 全命中 → 整格按 ID 重建（名称被外部改写不参与、不产生失败——ID 为准）；</li>
     *   <li>{@code _ID} 缺失/部分未命中 → 命中位置按 ID；未命中位置按同位名称条目经
     *       {@link LinkNameResolver} 回退，空名称条目 = U1 悬空导出回读的死链，按“无链接”
     *       跳过不计失败（导出时本就是死链）；</li>
     *   <li>无 {@code _ID} → 纯名称解析（整串优先 + CSV 转义还原拆分逐条目，空串条目跳过）；</li>
     *   <li>任一名称条目歧义/未命中 → 失败条目（多值任一失败由调用方整表阻断，R9）。</li>
     * </ul>
     */
    private DoubleLinkCellResolution resolveDoubleLinkCell(String idText, String nameText,
            LinkTableContext context, List<String> candidateNames)
    {
        DoubleLinkCellResolution resolution = new DoubleLinkCellResolution();
        Map<Long, NoteRecord> recordsById = context.getRecordsById();
        Map<String, List<NoteRecord>> recordsByName = context.getRecordsByName();

        // `_ID` 逐 id 解析（逗号拆分、trim、去空 token；非法 token 记 null 占位、按未命中走名称回退）
        List<Long> ids = parseIdEntries(idText);

        if (!ids.isEmpty())
        {
            boolean allHit = true;
            for (Long id : ids)
            {
                if (id == null || !recordsById.containsKey(id))
                {
                    allHit = false;
                    break;
                }
            }
            if (allHit)
            {
                // ID 全命中：整格按 ID 精确重建，名称不参与（R3：ID 与名称不一致以 ID 为准）
                for (Long id : ids)
                {
                    resolution.addRecord(recordsById.get(id));
                }
                return resolution;
            }
            // 部分未命中：命中位置按 ID；未命中位置按同位名称条目回退
            //（含空条目的 CSV 拆分保持与 `_ID` 的位置一一对应，resolve 会跳过空条目丢失位置）
            List<String> nameEntries = LinkNameResolver.splitCsvEntries(
                    nameText == null ? "" : nameText.trim());
            for (int i = 0; i < ids.size(); i++)
            {
                Long id = ids.get(i);
                NoteRecord byId = id == null ? null : recordsById.get(id);
                if (byId != null)
                {
                    resolution.addRecord(byId);
                    continue;
                }
                String entry = i < nameEntries.size() ? nameEntries.get(i).trim() : "";
                if (entry.isEmpty())
                {
                    // U1 悬空导出回读：`_ID` 指向已删记录且名称为空 → 该条目按“无链接”跳过不失败
                    continue;
                }
                // 单条目解析（不拆 CSV——条目本身含逗号时按整条目匹配，
                // 修复"已还原单条目被二次逗号拆散"错配/误报）
                LinkNameResolver.EntryResult entryResult = linkNameResolver.resolveEntry(entry, candidateNames);
                if (entryResult != null)
                {
                    applyNameEntryResult(entryResult, recordsByName, true, resolution);
                }
            }
            return resolution;
        }

        // 无 `_ID`：纯名称解析（整串优先 + CSV 拆分逐条目）
        if (nameText != null && !nameText.trim().isEmpty())
        {
            for (LinkNameResolver.EntryResult entryResult : linkNameResolver.resolve(nameText, candidateNames))
            {
                applyNameEntryResult(entryResult, recordsByName, false, resolution);
            }
        }
        return resolution;
    }

    /**
     * 名称解析结果落地：MATCHED → 命中记录（resolver 语义保证唯一，取首条防御）；
     * AMBIGUOUS/NOT_FOUND → 失败条目（原因区分纯名称与 ID 回退两来源，容错命中说明随附）。
     */
    private static void applyNameEntryResult(LinkNameResolver.EntryResult entryResult,
            Map<String, List<NoteRecord>> recordsByName, boolean idFallback, DoubleLinkCellResolution resolution)
    {
        if (entryResult.isMatched())
        {
            List<NoteRecord> matched = recordsByName.get(entryResult.getMatchedName());
            if (matched != null && !matched.isEmpty())
            {
                resolution.addRecord(matched.get(0));
            }
            return;
        }
        String prefix = idFallback ? "ID 未命中且" : "";
        String reason = entryResult.isAmbiguous()
                ? prefix + "名称歧义（多条同名候选，双向关联列无法唯一确定目标记录）"
                : prefix + "名称未命中（被关联表不存在同名记录）";
        resolution.failures.add(new LinkEntryFailure(entryResult.getRawEntry(), reason,
                entryResult.getExplanation()));
    }

    /**
     * 被关联表全部记录名列表（{@link LinkNameResolver} 候选名口径：同名重复保留——
     * 重复名在组件内判歧义，R7）。
     */
    private static List<String> candidateNameList(LinkTableContext context)
    {
        List<NoteRecord> sortedRecords = context.getSortedRecords();
        List<String> names = new ArrayList<>(sortedRecords.size());
        for (NoteRecord record : sortedRecords)
        {
            names.add(record.getName() == null ? "" : record.getName());
        }
        return names;
    }

    // ==================== 25 列双轨解析（U4：行 id 优先、笔记名回退） ====================

    /**
     * 语义关联列（25）逐行预检分析（U4，R2/R6-R9）：单元格先按 {@code _ID} 逐条目校验
     * NoteNotelink 行存在性——命中且<b>归属当前列与本表</b>（linkColumnId/linkDwTableId
     * 校验）才按行 id 恢复该行（名称不参与，ID 为准）；不匹配按"未命中"走名称回退
     * （防 `_ID` 越权改写他列/他表行，及旧版导出文件 _ID=笔记 id 撞行错配）。{@code _ID}
     * 缺失/未命中位置按同位名称条目经 {@link LinkNameResolver} 对<b>当前用户可见笔记标题</b>
     * 回退，歧义/未命中进<b>链接失败清单</b>（与 21 列同组、同缺参清单并列阻断，确认前
     * 零写入；多值任一条目失败整表零写入，R9）。空名称条目 = U1 悬空导出回读的死链，
     * 跳过不失败。同列重复 `_ID`（同一链接行被两条数据行恢复会互相抢占行内坐标）检出
     * 进链接失败清单。
     */
    private void analyzeSemanticLinkColumn(ExcelImportPrecheckResult result, NoteColumn column,
            int headerIndex, ColumnMapping mapping, List<RowData> rows, Long dwtableId,
            SemanticNoteContext noteContext)
    {
        int idHeaderIndex = mapping.linkIdHeaderIndex(column);
        // notelink 旧行查询缓存（同列共享，null 值占位防重复查库）
        Map<Long, NoteNotelink> notelinkById = new HashMap<>();
        // 同列已恢复的 notelink 行 id 集（重复 `_ID` 检出）
        Set<Long> restoredLinkIds = new HashSet<>();

        for (RowData row : rows)
        {
            String idText = idHeaderIndex >= 0 ? mapping.cellText(row, idHeaderIndex) : "";
            String nameText = mapping.cellText(row, headerIndex);
            SemanticCellResolution resolution =
                    resolveSemanticLinkCell(idText, nameText, dwtableId, column.getId(),
                            noteContext, notelinkById, restoredLinkIds);
            appendLinkFailures(result, column, row.getRowNumber(), resolution.failures);
        }
    }

    /**
     * 25 列候选笔记上下文（U4/R6 + R23 归属过滤）：一次查库内全部笔记（delFlag=0）后按
     * <b>当前用户可见</b>过滤（note_note.auth=userId，admin 通行——对齐 21 列 R23 归属
     * 校验先例，防他人笔记标题经名称回退被引用/经失败清单泄露），构建候选标题列表
     * （同名保留——重复名在 resolver 内判歧义）与 标题→笔记 id 映射
     * （回退命中后取引用来源笔记 id；同名重复时后写覆盖无碍——同名必判歧义不会 MATCHED）。
     */
    private SemanticNoteContext semanticNoteContext(Long userId)
    {
        List<NoteNote> notes = noteNoteMapper.selectAllNoteList();
        boolean admin = SecurityUtils.isAdmin(userId);
        List<String> titles = new ArrayList<>(notes.size());
        Map<String, Long> noteIdByTitle = new HashMap<>();
        for (NoteNote note : notes)
        {
            if (note == null)
            {
                continue;
            }
            if (!admin && (note.getAuth() == null || !note.getAuth().equals(userId)))
            {
                // R23 归属口径：他人笔记不进候选池（auth=所属用户 id）
                continue;
            }
            String title = note.getTitle() == null ? "" : note.getTitle();
            titles.add(title);
            if (note.getId() != null)
            {
                noteIdByTitle.put(title, note.getId());
            }
        }
        return new SemanticNoteContext(titles, noteIdByTitle);
    }

    /**
     * 25 列单元格解析（U4 双轨制，预检与执行两阶段共用；R2/R6-R9 + U1 悬空回读边界）。
     * <p>
     * 逐条目语义（{@code _ID} 与名称条目按导出顺序一一对应，载体为 NoteNotelink 行 id）：
     * <ul>
     *   <li>{@code _ID} 命中（行存在）且<b>归属校验通过</b>（linkColumnId=当前 25 列 id、
     *       linkDwTableId=本表 id——防 `_ID` 越权改写他列/他表行，及旧版导出文件
     *       _ID=笔记 id 撞行错配）→ 按行 id 恢复原行（捕获旧行核心字段备用，名称被外部
     *       改写不参与、不产生失败——ID 为准）；归属不匹配按"未命中"走名称回退；
     *       同列重复 `_ID` → 失败条目（同一链接行被两条数据行恢复会互相抢占行内坐标）；</li>
     *   <li>{@code _ID} 缺失/未命中 → 同位名称条目经 {@link LinkNameResolver} 回退
     *       （候选=当前用户可见笔记标题，条目语法"笔记名（锚词）"的锚词由 resolver 剥离），
     *       命中 → 以笔记 id + 锚词新建行（blockId 降级为 null）；空名称条目 =
     *       U1 悬空导出回读的死链，按“无链接”跳过不计失败；</li>
     *   <li>任一名称条目歧义/未命中 → 失败条目（多值任一失败由调用方整表阻断，R9）。</li>
     * </ul>
     */
    private SemanticCellResolution resolveSemanticLinkCell(String idText, String nameText,
            Long dwtableId, Long columnId, SemanticNoteContext noteContext,
            Map<Long, NoteNotelink> notelinkById, Set<Long> restoredLinkIds)
    {
        SemanticCellResolution resolution = new SemanticCellResolution();

        // `_ID` 逐行 id 解析（逗号拆分、trim；空/非法 token 记 null 占位、按未命中走名称回退）
        List<Long> ids = parseIdEntries(idText);

        if (!ids.isEmpty())
        {
            // 含空条目的 CSV 拆分保持与 `_ID` 的位置一一对应（resolve 会跳过空条目丢失位置）
            List<String> nameEntries = LinkNameResolver.splitCsvEntries(
                    nameText == null ? "" : nameText.trim());
            for (int i = 0; i < ids.size(); i++)
            {
                Long id = ids.get(i);
                NoteNotelink existing = id == null ? null : lookupNotelink(id, notelinkById);
                // 行 id 命中须同时归属当前 25 列与本表（linkColumnId/linkDwTableId 校验）：
                // 不匹配按"未命中"走名称回退——堵住 `_ID` 越权改写他列/他表行，
                // 以及旧版导出文件（_ID=笔记 id）撞行错配
                if (existing != null && columnId != null && columnId.equals(existing.getLinkColumnId())
                        && dwtableId != null && dwtableId.equals(existing.getLinkDwTableId()))
                {
                    if (!restoredLinkIds.add(id))
                    {
                        // 同列重复 `_ID`：同一语义链接行不能被两个条目/两条数据行同时恢复
                        //（upsert 会互相抢占行内坐标）
                        resolution.failures.add(new LinkEntryFailure(String.valueOf(id),
                                "`_ID` 重复指向同一语义链接行（同一链接行只能恢复一次）", null));
                        continue;
                    }
                    // 行 id 命中：恢复原行（核心字段已随行捕获，事务段按行 id upsert）
                    resolution.restored.add(existing);
                    continue;
                }
                String entry = i < nameEntries.size() ? nameEntries.get(i).trim() : "";
                if (entry.isEmpty())
                {
                    // U1 悬空导出回读：`_ID` 指向已删 notelink 行且名称为空 → 该条目按“无链接”跳过不失败
                    continue;
                }
                resolveSemanticNameEntry(entry, noteContext, true, resolution);
            }
            return resolution;
        }

        // 无 `_ID`：纯名称解析（整串优先 + CSV 拆分逐条目，空串条目跳过）
        if (nameText != null && !nameText.trim().isEmpty())
        {
            for (LinkNameResolver.EntryResult entryResult
                    : linkNameResolver.resolve(nameText, noteContext.titles))
            {
                applySemanticEntryResult(entryResult, noteContext, false, resolution);
            }
        }
        return resolution;
    }

    /**
     * 单条目名称解析（`_ID` 回退路径）：不拆 CSV——条目已是拆分产物，本身含逗号的
     * 笔记标题按整条目匹配（修复"已还原单条目被二次逗号拆散"错配/误报）。
     */
    private void resolveSemanticNameEntry(String entry, SemanticNoteContext noteContext,
            boolean idFallback, SemanticCellResolution resolution)
    {
        LinkNameResolver.EntryResult entryResult = linkNameResolver.resolveEntry(entry, noteContext.titles);
        if (entryResult != null)
        {
            applySemanticEntryResult(entryResult, noteContext, idFallback, resolution);
        }
    }

    /**
     * 名称解析结果落地（25 列）：MATCHED → 笔记 id + 锚词的新建行预案（blockId 降级）；
     * AMBIGUOUS/NOT_FOUND → 失败条目（原因区分纯名称与 ID 回退两来源，容错命中说明随附）。
     */
    private static void applySemanticEntryResult(LinkNameResolver.EntryResult entryResult,
            SemanticNoteContext noteContext, boolean idFallback, SemanticCellResolution resolution)
    {
        if (entryResult.isMatched())
        {
            Long noteId = noteContext.noteIdByTitle.get(entryResult.getMatchedName());
            if (noteId != null)
            {
                resolution.fallbacks.add(new SemanticFallback(noteId, entryResult.getAnchor()));
            }
            return;
        }
        String prefix = idFallback ? "行 id 未命中且" : "";
        String reason = entryResult.isAmbiguous()
                ? prefix + "名称歧义（多条同名笔记，语义链接无法唯一确定引用来源笔记）"
                : prefix + "名称未命中（库内不存在同名笔记）";
        resolution.failures.add(new LinkEntryFailure(entryResult.getRawEntry(), reason,
                entryResult.getExplanation()));
    }

    /**
     * 按 id 查 NoteNotelink 行（带缓存；未命中缓存 null 占位防重复查库）。
     */
    private NoteNotelink lookupNotelink(Long id, Map<Long, NoteNotelink> notelinkById)
    {
        if (notelinkById.containsKey(id))
        {
            return notelinkById.get(id);
        }
        NoteNotelink link = noteNotelinkMapper.selectNoteNotelinkById(id);
        notelinkById.put(id, link);
        return link;
    }

    // ==================== 被关联表上下文（R23 + KTD4） ====================

    /**
     * 取（或构建）被关联表上下文：R23 归属校验（存在 + 同 noteId + AgentOwnershipChecker，
     * 失败抛 {@link ServiceException} 含列名与原因，阻断整次预检）→ 一次查全部 NoteRecord
     * 按 sort 升序构建 name → 有序记录列表（同表多列共享缓存）。
     */
    private LinkTableContext linkTableContext(NoteColumn column, Long noteId, Long userId,
            Map<Long, LinkTableContext> cache)
    {
        Long tableId = parseTableId(column);
        LinkTableContext cached = cache.get(tableId);
        if (cached != null)
        {
            return cached;
        }
        String columnName = column.getName() == null ? "" : column.getName();

        NoteDwtable related = noteDwtableMapper.selectNoteDwtableById(tableId);
        if (related == null)
        {
            throw new ServiceException("关联列“" + columnName + "”的被关联数据表不存在，无法导入");
        }
        if (!noteId.equals(related.getNoteId()))
        {
            throw new ServiceException("关联列“" + columnName + "”的被关联数据表不在当前笔记内，无法导入");
        }
        try
        {
            agentOwnershipChecker.checkDwtableOwnership(tableId, userId);
        }
        catch (ServiceException e)
        {
            throw new ServiceException("关联列“" + columnName + "”的被关联数据表归属校验失败：" + e.getMessage());
        }

        // KTD4 批量预解析：一次查被关联表全部记录，避免逐单元格 N+1
        NoteRecordVo query = new NoteRecordVo();
        query.setDwtableId(tableId);
        List<NoteRecord> records = new ArrayList<>(noteRecordMapper.selectNoteRecordList(query));
        records.sort(Comparator
                .comparing((NoteRecord record) -> record.getSort() == null ? Long.MAX_VALUE : record.getSort())
                .thenComparing(record -> record.getId() == null ? Long.MAX_VALUE : record.getId()));
        Map<String, List<NoteRecord>> recordsByName = new LinkedHashMap<>();
        Map<Long, NoteRecord> recordsById = new HashMap<>();
        for (NoteRecord record : records)
        {
            String name = record.getName() == null ? "" : record.getName();
            recordsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(record);
            recordsById.put(record.getId(), record);
        }
        LinkTableContext context = new LinkTableContext(related, records, recordsByName, recordsById);
        cache.put(tableId, context);
        return context;
    }

    /**
     * 解析关联列 property JSON 的 table_id（被关联表 id）；缺失或非法抛 {@link ServiceException} 含列名。
     * <p>
     * U5 兼容：18 单向关联列由前端建列时写入 {@code {select: 被关联表id}}（无 table_id 键），
     * table_id 缺失时对 18 列回退读取 select 的数字值；非数字 select 与缺失同路径报错。
     */
    private static Long parseTableId(NoteColumn column)
    {
        String columnName = column.getName() == null ? "" : column.getName();
        Long tableId = null;
        JSONObject prop = parsePropertySafe(column);
        if (!prop.isEmpty())
        {
            tableId = prop.getLong("table_id");
            if (tableId == null && column.getType() != null
                    && column.getType() == ExcelColumnMatcher.TYPE_SINGLE_LINK)
            {
                tableId = prop.getLong("select");
            }
        }
        if (tableId == null)
        {
            throw new ServiceException("关联列“" + columnName + "”的配置缺少被关联表（table_id），无法导入");
        }
        return tableId;
    }

    /**
     * 关联文本匹配的名字序列（KTD4 整串优先，预检与导入两阶段共用）：
     * 单元格文本（trim 后非空）先整串匹配记录名，命中返回整串单元素列表（不拆逗号）；
     * 未命中按英文逗号拆分逐个 trim 去空。
     */
    static List<String> candidateNames(String text, Map<String, List<NoteRecord>> recordsByName)
    {
        if (text == null || text.isEmpty())
        {
            return Collections.emptyList();
        }
        if (recordsByName.containsKey(text))
        {
            return Collections.singletonList(text);
        }
        List<String> names = new ArrayList<>();
        for (String part : text.split(","))
        {
            String name = part.trim();
            if (!name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
    }

    // ==================== 辅助 ====================

    /**
     * 忽略提示 + 跳过表头清单；后端日志记录跳过列名与原因（不含单元格值，R25/R7）。
     * 替换式语义：未匹配表头（{@link HeaderEntry#isUnmatched()}）为新建列候选，
     * 进 replaceImpact.newColumnNames，不进 skippedHeaders。
     */
    private static void appendSkippedHeaders(ExcelImportPrecheckResult result, ColumnMapping mapping,
            Long noteId, Long dwtableId)
    {
        for (HeaderEntry entry : mapping.getSkippedEntries())
        {
            if (entry.isUnmatched())
            {
                // 新建列候选（replaceImpact 已汇总），不算跳过
                continue;
            }
            result.getSkippedHeaders().add(new SkippedHeader(entry.getHeaderName(), entry.getReason()));
            log.info("[EXCEL-PRECHECK] 跳过表头 noteId={}, dwtableId={}, headerName={}, reason={}",
                    noteId, dwtableId, entry.getHeaderName(), entry.getReason());
        }
    }

    // ==================== 替换式影响面（预检弹框确认载体 + agent 替换摘要） ====================

    /**
     * 组装替换式导入影响面：
     * <ul>
     *   <li>deletedRowCount——目标表现有全部记录数（导入成功即全删）；</li>
     *   <li>deletedColumns——不被任何表头引用的非系统列（mapped 列 ∪ 同名排除类型跳过列之外的列），
     *       21 列标记配对列级联删除；</li>
     *   <li>newColumnNames——未匹配表头（同名去重，按出现顺序）；</li>
     *   <li>unhiddenColumnNames——映射到的隐藏列（导入时取消隐藏并导数据）；</li>
     *   <li>repointedLinkColumns / willClearLinkEntries——跨表入链重指向影响面由
     *       {@link #analyzeInboundRepoint} 按 linkRecordId 数据面口径产出（U6 语义迁移，
     *       取代旧"将清空的关联列"列扫描口径），不在此组装。</li>
     * </ul>
     */
    private void buildReplaceImpact(ExcelImportPrecheckResult result, Long noteId, Long dwtableId,
            List<NoteColumn> columns, ColumnMapping mapping)
    {
        ReplaceImpact impact = new ReplaceImpact();

        // 现有行数（导入成功即全删）
        NoteRecordVo recordQuery = new NoteRecordVo();
        recordQuery.setDwtableId(dwtableId);
        impact.setDeletedRowCount(noteRecordMapper.selectNoteRecordList(recordQuery).size());

        // 被引用列 id 集（mapped 列 ∪ 同名排除类型跳过列）+ 取消隐藏清单
        Set<Long> referencedIds = new HashSet<>();
        for (HeaderEntry entry : mapping.getEntries())
        {
            if (entry.getColumn() != null && entry.getColumn().getId() != null)
            {
                referencedIds.add(entry.getColumn().getId());
                if (ExcelColumnMatcher.isHidden(entry.getColumn()))
                {
                    impact.getUnhiddenColumnNames().add(entry.getHeaderName());
                }
            }
            if (entry.getSkippedColumn() != null && entry.getSkippedColumn().getId() != null)
            {
                referencedIds.add(entry.getSkippedColumn().getId());
            }
        }

        // 删除列 = 不被引用且非系统列
        for (NoteColumn column : columns)
        {
            if (column == null || column.getId() == null || referencedIds.contains(column.getId()))
            {
                continue;
            }
            Long type = column.getType();
            if (type != null && type >= 1001L && type <= 1005L)
            {
                continue;
            }
            DeletedColumnInfo deleted = new DeletedColumnInfo();
            deleted.setColumnId(column.getId());
            deleted.setColumnName(column.getName() == null ? "" : column.getName().trim());
            deleted.setColumnType(type);
            deleted.setCascadeDeletesPairedColumn(type != null
                    && type == ExcelColumnMatcher.TYPE_DOUBLE_LINK);
            impact.getDeletedColumns().add(deleted);
        }

        // 新建列名（未匹配表头，同名已去重）
        for (HeaderEntry entry : mapping.getUnmatchedEntries())
        {
            impact.getNewColumnNames().add(entry.getHeaderName());
        }

        result.setReplaceImpact(impact);
    }

    /**
     * 收集其他表中涉及跨表重算的关联列（kept 21 列在被关联表的配对列
     * + 同 note 其他表 type=18 且 property.table_id 指向本表的列）——仅作
     * {@code buildRecomputeTargets} 的重算编排扫描输入（原名 clearedLinkColumns 已按
     * 实际语义更名为"重算编排列"：导入路径由 U5 入链重指向取代整列清空后本清单不再
     * 触发任何清空动作，纯供重算扫描；预检呈现已迁移至 repointedLinkColumns/
     * willClearLinkEntries 数据面口径）。
     * 自引用表的配对列也在收集范围（其 items 已随本表全删，重算覆盖为 no-op，口径统一）。
     */
    private List<NoteColumn> collectCrossTableRecomputeColumns(Long noteId, Long dwtableId, ColumnMapping mapping)
    {
        List<NoteColumn> cleared = new ArrayList<>();
        Set<Long> collectedIds = new LinkedHashSet<>();
        for (HeaderEntry entry : mapping.getMappedEntries())
        {
            NoteColumn column = entry.getColumn();
            if (column == null || column.getType() == null
                    || column.getType() != ExcelColumnMatcher.TYPE_DOUBLE_LINK)
            {
                continue;
            }
            Long pairId = parseBackFieldId(column);
            if (pairId != null && collectedIds.add(pairId))
            {
                NoteColumn pair = noteColumnMapper.selectNoteColumnById(pairId);
                if (pair != null)
                {
                    cleared.add(pair);
                }
            }
        }
        for (NoteDwtable table : noteDwtableMapper.selectDWTableList(noteId))
        {
            if (table == null || table.getId() == null || table.getId().equals(dwtableId))
            {
                continue;
            }
            NoteColumn query = new NoteColumn();
            query.setDwtableId(table.getId());
            for (NoteColumn column : noteColumnMapper.selectNoteColumnList(query))
            {
                if (column == null || column.getId() == null || column.getType() == null
                        || column.getType() != ExcelColumnMatcher.TYPE_SINGLE_LINK)
                {
                    continue;
                }
                if (dwtableId.equals(parseTableIdLenient(column)) && collectedIds.add(column.getId()))
                {
                    cleared.add(column);
                }
            }
        }
        return cleared;
    }

    /**
     * 宽松解析关联列 property 的 table_id（缺失/非法返回 null，不抛异常；
     * 替换清理扫描用——配置残缺的列不阻断导入）。
     */
    private static Long parseTableIdLenient(NoteColumn column)
    {
        JSONObject prop = parsePropertySafe(column);
        if (prop.isEmpty())
        {
            return null;
        }
        Long tableId = prop.getLong("table_id");
        if (tableId == null && column.getType() != null
                && column.getType() == ExcelColumnMatcher.TYPE_SINGLE_LINK)
        {
            tableId = prop.getLong("select");
        }
        return tableId;
    }

    /**
     * 宽松解析 21 列 property 的 back_field_id（缺失/非法返回 null，不抛异常；
     * 级联删除与重算扫描用——被删列的配置残缺时跳过级联而非阻断导入）。
     */
    private static Long parseBackFieldIdLenient(NoteColumn column)
    {
        return parsePropertySafe(column).getLong("back_field_id");
    }

    /**
     * 计算文件指纹（KTD5）：size + 内容 MD5（JDK MessageDigest，32 位小写十六进制）。
     */
    private static FileFingerprint fingerprint(MultipartFile file)
    {
        FileFingerprint fingerprint = new FileFingerprint();
        fingerprint.setSize(file.getSize());
        fingerprint.setMd5(md5Hex(readBytes(file)));
        return fingerprint;
    }

    private static byte[] readBytes(MultipartFile file)
    {
        try
        {
            return file.getBytes();
        }
        catch (IOException e)
        {
            throw new ServiceException("读取上传文件失败，请重试");
        }
    }

    private static String md5Hex(byte[] bytes)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 预检 DTO 输出列名统一 trim（与阶段二 buildImportPlan 的漂移比对键、补参 columnValues
     * 取值键全链路一致——空格列名下基线回传不漂移、补参键不错位）。
     */
    private static String dtoColumnName(NoteColumn column)
    {
        return column.getName() == null ? "" : column.getName().trim();
    }

    /**
     * 宽松解析 property JSON：null/空/非法时返回空 JSONObject（不抛异常）；
     * 委托 {@link ColumnDefaultValueSupport#parseSafe}（property 解析唯一实现）。
     */
    private static JSONObject parsePropertySafe(NoteColumn column)
    {
        return column == null ? new JSONObject() : ColumnDefaultValueSupport.parseSafe(column.getProperty());
    }

    /**
     * 解析关联列 property 的 back_field_id（配对列）：21 双向关联必填（对称写入目标列），
     * 18 单向关联可选（前端建列时 18 的 property 无 back_field_id → null，对齐 UI 落库形态）。
     */
    private static Long parseBackFieldId(NoteColumn column)
    {
        Long backFieldId = parsePropertySafe(column).getLong("back_field_id");
        if (backFieldId == null && column.getType() != null
                && column.getType() == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
        {
            throw new ServiceException("关联列“" + (column.getName() == null ? "" : column.getName())
                    + "”的配置缺少配对列（back_field_id），无法导入");
        }
        return backFieldId;
    }

    /**
     * 被关联表上下文（KTD4 预解析产物，同表多列共享）：
     * 表信息 + sort 升序全量记录 + name → 有序记录列表（同名多条保留全部）
     * + id → 记录（recordId 存在性校验 O(1)）。
     */
    private static final class LinkTableContext
    {
        private final NoteDwtable table;

        private final List<NoteRecord> sortedRecords;

        private final Map<String, List<NoteRecord>> recordsByName;

        private final Map<Long, NoteRecord> recordsById;

        private LinkTableContext(NoteDwtable table, List<NoteRecord> sortedRecords,
                Map<String, List<NoteRecord>> recordsByName, Map<Long, NoteRecord> recordsById)
        {
            this.table = table;
            this.sortedRecords = sortedRecords;
            this.recordsByName = recordsByName;
            this.recordsById = recordsById;
        }

        private NoteDwtable getTable()
        {
            return table;
        }

        private List<NoteRecord> getSortedRecords()
        {
            return sortedRecords;
        }

        private Map<String, List<NoteRecord>> getRecordsByName()
        {
            return recordsByName;
        }

        private Map<Long, NoteRecord> getRecordsById()
        {
            return recordsById;
        }
    }

    // ==================== 阶段二：导入写入（U5） ====================

    @Override
    public int importExcelData(Long noteId, Long dwtableId, MultipartFile file, String paramsJson, Long userId)
    {
        // 防御性复查（Controller 已做归属校验 + noteId↔dwtableId 断言，照 importData 模式）
        NoteDwtable dwtable = noteDwtableMapper.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("导入失败：多维表与笔记不匹配");
        }
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("导入失败：上传文件为空");
        }
        ExcelImportParams params = parseParams(paramsJson);
        verifyFingerprint(file, params);
        // 非事务前置段：解析/匹配/漂移/补参校验不占事务（KTD5）
        ImportPlan plan = buildImportPlan(noteId, dwtableId, userId, dwtable, file, params);
        // 经自注入代理进入 @Transactional 写入段（同类直接调用不经代理）
        return self.executeImport(plan);
    }

    /**
     * 解析 params JSON（fastjson2 → {@link ExcelImportParams}），空/非法给明确拒绝文案。
     */
    private static ExcelImportParams parseParams(String paramsJson)
    {
        if (paramsJson == null || paramsJson.trim().isEmpty())
        {
            throw new ServiceException("导入参数（params）不能为空，请通过预检后导入");
        }
        ExcelImportParams params;
        try
        {
            params = JSONObject.parseObject(paramsJson, ExcelImportParams.class);
        }
        catch (Exception e)
        {
            throw new ServiceException("导入参数（params）格式错误，无法解析，请通过预检后重新导入");
        }
        if (params == null)
        {
            throw new ServiceException("导入参数（params）格式错误，无法解析，请通过预检后重新导入");
        }
        // 显式 null 段归一为空集合（后续遍历不做 null 防御）
        if (params.getPrecheckBaseline() == null)
        {
            params.setPrecheckBaseline(new ExcelImportParams.PrecheckBaseline());
        }
        if (params.getColumnValues() == null)
        {
            params.setColumnValues(new LinkedHashMap<String, String>());
        }
        if (params.getRelationSelections() == null)
        {
            params.setRelationSelections(new ArrayList<ExcelImportParams.RelationSelection>());
        }
        // 单段条目数上限：params 可达 10MB，防超大 columnValues/relationSelections 造成下游 CPU 放大
        if (params.getColumnValues().size() > PARAM_ENTRIES_LIMIT)
        {
            throw new ServiceException("导入参数条目数超限（columnValues 超过 " + PARAM_ENTRIES_LIMIT
                    + " 条），请重新预检");
        }
        if (params.getRelationSelections().size() > PARAM_ENTRIES_LIMIT)
        {
            throw new ServiceException("导入参数条目数超限（relationSelections 超过 " + PARAM_ENTRIES_LIMIT
                    + " 条），请重新预检");
        }
        return params;
    }

    /**
     * 文件指纹校验（KTD5）：size 优先（廉价）再 md5（重算后比对），
     * 不一致抛"文件与预检时不一致"（防预检 A 文件、导入 B 文件的补参错位命中）。
     */
    private static void verifyFingerprint(MultipartFile file, ExcelImportParams params)
    {
        FileFingerprint expected = params.getFileFingerprint();
        if (expected == null || expected.getMd5() == null || expected.getMd5().trim().isEmpty())
        {
            throw new ServiceException("导入参数缺少文件指纹，请重新预检");
        }
        if (file.getSize() != expected.getSize() || !md5Hex(readBytes(file)).equals(expected.getMd5()))
        {
            throw new ServiceException("文件与预检时不一致，请重新预检");
        }
    }

    /**
     * 构建导入计划（非事务前置段主体，<b>替换式语义</b>）：与预检同一私有方法链重新解析/映射/匹配 →
     * 替换分类（kept 参与列 / pending 新建列 / deleted 删除列 / unhide 取消隐藏 / cleared 跨表清空）→
     * 阶段二原始状态汇总 → 反向漂移 fail-fast → 补参校验（R26）→
     * 三源合并 + 补参应用构建逐行写入计划 → 重算编排清单（KTD8）。
     */
    private ImportPlan buildImportPlan(Long noteId, Long dwtableId, Long userId, NoteDwtable dwtable,
            MultipartFile file, ExcelImportParams params)
    {
        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(file);
        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, dwtable.getName());
        verifyNonBlankHeaders(selection);

        NoteColumn query = new NoteColumn();
        query.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(query);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(selection.getHeaders(), columns);
        List<RowData> rows = selection.getRows();
        int rowCount = rows.size();

        // ===== 替换式分类 =====
        // 补参列名校验用全列索引（与现状一致）
        Map<String, NoteColumn> columnByName = new HashMap<>();
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null)
            {
                continue;
            }
            if (column.getName() != null && !column.getName().trim().isEmpty())
            {
                columnByName.putIfAbsent(column.getName().trim(), column);
            }
        }

        // kept 参与列 = 已映射且类型 ∈ 六类基础 + 18/21/25（隐藏列映射即参与，导入时取消隐藏）
        Map<NoteColumn, Integer> headerIndexByColumn = new IdentityHashMap<>();
        List<NoteColumn> participants = new ArrayList<>();
        Set<Long> referencedIds = new HashSet<>();
        for (HeaderEntry entry : mapping.getEntries())
        {
            if (entry.getColumn() != null && entry.getColumn().getId() != null)
            {
                referencedIds.add(entry.getColumn().getId());
            }
            if (entry.getSkippedColumn() != null && entry.getSkippedColumn().getId() != null)
            {
                referencedIds.add(entry.getSkippedColumn().getId());
            }
        }
        for (HeaderEntry entry : mapping.getMappedEntries())
        {
            NoteColumn column = entry.getColumn();
            headerIndexByColumn.put(column, Integer.valueOf(entry.getHeaderIndex()));
            Long type = column.getType();
            if (type != null && (BASIC_TYPES.contains(type)
                    || type == ExcelColumnMatcher.TYPE_SINGLE_LINK
                    || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK
                    || type == ExcelColumnMatcher.TYPE_SEMANTIC_LINK))
            {
                participants.add(column);
            }
        }

        // pending 新建列 = 未匹配表头（纯文本列，值在计划期预解析，事务段 insert 后组装 items）
        List<PendingColumn> pendingColumns = new ArrayList<>();
        for (HeaderEntry entry : mapping.getUnmatchedEntries())
        {
            NoteColumn pending = new NoteColumn();
            pending.setName(entry.getHeaderName());
            pending.setType(1L);
            pending.setDwtableId(dwtableId);
            pending.setIsShow(0L);
            String[] values = new String[rowCount];
            for (int r = 0; r < rowCount; r++)
            {
                values[r] = mapping.cellText(rows.get(r), entry.getHeaderIndex()).trim();
            }
            PendingColumn pendingColumn = new PendingColumn();
            pendingColumn.column = pending;
            pendingColumn.headerIndex = entry.getHeaderIndex();
            pendingColumn.values = values;
            pendingColumns.add(pendingColumn);
        }

        // unhide 取消隐藏列 = kept 中的隐藏列
        List<NoteColumn> unhideColumns = new ArrayList<>();
        for (NoteColumn participant : participants)
        {
            if (ExcelColumnMatcher.isHidden(participant))
            {
                unhideColumns.add(participant);
            }
        }

        // deleted 删除列 = 不被任何表头引用（mapped ∪ 排除类型同名列）的非系统列
        List<NoteColumn> deletedColumns = new ArrayList<>();
        for (NoteColumn column : columns)
        {
            if (column == null || column.getId() == null || referencedIds.contains(column.getId()))
            {
                continue;
            }
            Long type = column.getType();
            if (type != null && type >= 1001L && type <= 1005L)
            {
                continue;
            }
            deletedColumns.add(column);
        }

        // name 派生源列（替换后最终列集合）：selectNameSource 共享选择（kept 中首个 type=1 列，
        // 无则取首个 pending 新建文本列——id 未生成，name 从预解析值派生）——与
        // deriveRowNames 的名称源选择同源消费
        NameSource nameSource = selectNameSource(columns, referencedIds, mapping);
        Long nameColumnId = nameSource != null && nameSource.keptColumn != null
                ? nameSource.keptColumn.getId() : null;
        PendingColumn namePendingSource = null;
        if (nameColumnId == null && !pendingColumns.isEmpty())
        {
            namePendingSource = pendingColumns.get(0);
        }

        // ===== 阶段二逐列逐行分析（重新匹配的原始状态，未应用补参——供漂移比对） =====
        Map<Long, LinkTableContext> contextByTableId = new HashMap<>();
        Set<String> stage2MissingColumns = new LinkedHashSet<>();
        Map<String, Set<String>> missNamesByColumn = new LinkedHashMap<>();
        Map<String, Map<String, Long>> ambiguityByColumn = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<String>> newOptionsByColumnId = new LinkedHashMap<>();
        IdentityHashMap<NoteColumn, String[]> excelValuesByColumn = new IdentityHashMap<>();
        IdentityHashMap<NoteColumn, boolean[]> violationsByColumn = new IdentityHashMap<>();
        IdentityHashMap<NoteColumn, LinkColumnAnalysis> linkAnalysisByColumn = new IdentityHashMap<>();
        IdentityHashMap<NoteColumn, SemanticColumnAnalysis> semanticAnalysisByColumn = new IdentityHashMap<>();
        // 25 列候选笔记上下文（同一次导入共享一次全量笔记查询）+ 旧行查询缓存
        SemanticNoteContext semanticNoteContext = null;
        Map<Long, NoteNotelink> notelinkById = new HashMap<>();

        for (NoteColumn column : participants)
        {
            String columnName = column.getName() == null ? "" : column.getName().trim();
            Long type = column.getType();
            boolean link = type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK;
            boolean semantic = type == ExcelColumnMatcher.TYPE_SEMANTIC_LINK;
            // 替换式语义：participants 全部已映射（未映射列已进 deletedColumns），headerIndex 恒非空
            int headerIndex = headerIndexByColumn.get(column).intValue();
            if (semantic)
            {
                // U4：25 列行 id 优先逐行解析（与预检同款）。失败即漂移拒绝——预检已将
                // 链接失败列为阻断项，此处触发必为预检-导入间隙的数据变化或绕过预检的
                // 直调（fail-fast 于任何写入之前，整表零写入）；旧行核心字段随解析捕获，
                // 事务段 25 重建按行 id 恢复
                if (semanticNoteContext == null)
                {
                    semanticNoteContext = semanticNoteContext(userId);
                }
                int idHeaderIndex = mapping.linkIdHeaderIndex(column);
                SemanticColumnAnalysis analysis = new SemanticColumnAnalysis();
                // 同列已恢复的 notelink 行 id 集（重复 `_ID` 检出，与预检同口径）
                Set<Long> restoredLinkIds = new HashSet<>();
                for (int r = 0; r < rowCount; r++)
                {
                    String idText = idHeaderIndex >= 0 ? mapping.cellText(rows.get(r), idHeaderIndex) : "";
                    String nameText = mapping.cellText(rows.get(r), headerIndex);
                    SemanticCellResolution cell =
                            resolveSemanticLinkCell(idText, nameText, dwtableId, column.getId(),
                                    semanticNoteContext, notelinkById, restoredLinkIds);
                    analysis.cellsPerRow.add(cell);
                    if (!cell.failures.isEmpty())
                    {
                        LinkEntryFailure failure = cell.failures.get(0);
                        throw driftException("语义关联列“" + columnName + "”第 "
                                + rows.get(r).getRowNumber() + " 行的链接条目“"
                                + sanitizeInline(failure.entryText) + "”解析失败（" + failure.reason + "）");
                    }
                }
                semanticAnalysisByColumn.put(column, analysis);
            }
            else if (link)
            {
                LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
                LinkColumnAnalysis analysis = new LinkColumnAnalysis();
                analysis.context = context;
                analysis.backFieldId = parseBackFieldId(column);
                if (type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
                {
                    // U3：21 列 ID 优先逐行解析（与预检同款）。失败即漂移拒绝——预检已将
                    // 链接失败列为阻断项，此处触发必为预检-导入间隙的数据变化或绕过预检的
                    // 直调（fail-fast 于任何写入之前，整表零写入）
                    int idHeaderIndex = mapping.linkIdHeaderIndex(column);
                    List<String> candidates = candidateNameList(context);
                    for (int r = 0; r < rowCount; r++)
                    {
                        String idText = idHeaderIndex >= 0 ? mapping.cellText(rows.get(r), idHeaderIndex) : "";
                        String nameText = mapping.cellText(rows.get(r), headerIndex);
                        DoubleLinkCellResolution cell =
                                resolveDoubleLinkCell(idText, nameText, context, candidates);
                        analysis.recordsPerRow.add(cell.records());
                        if (!cell.failures.isEmpty())
                        {
                            LinkEntryFailure failure = cell.failures.get(0);
                            throw driftException("关联列“" + columnName + "”第 "
                                    + rows.get(r).getRowNumber() + " 行的链接条目“"
                                    + sanitizeInline(failure.entryText) + "”解析失败（" + failure.reason + "）");
                        }
                    }
                }
                else
                {
                    Set<String> missNames = new LinkedHashSet<>();
                    Map<String, Long> ambiguity = new LinkedHashMap<>();
                    for (int r = 0; r < rowCount; r++)
                    {
                        String text = mapping.cellText(rows.get(r), headerIndex).trim();
                        List<String> names = candidateNames(text, context.getRecordsByName());
                        analysis.namesPerRow.add(names);
                        for (String name : names)
                        {
                            List<NoteRecord> matched = context.getRecordsByName().get(name);
                            if (matched != null && !matched.isEmpty())
                            {
                                // 同名多条 → 歧义，预选 sort 靠前首条（R14）
                                if (matched.size() > 1)
                                {
                                    ambiguity.putIfAbsent(name, Long.valueOf(matched.get(0).getId()));
                                }
                            }
                            else
                            {
                                missNames.add(name);
                            }
                        }
                    }
                    if (!missNames.isEmpty())
                    {
                        missNamesByColumn.put(columnName, missNames);
                        stage2MissingColumns.add(columnName);
                    }
                    if (!ambiguity.isEmpty())
                    {
                        ambiguityByColumn.put(columnName, ambiguity);
                    }
                }
                linkAnalysisByColumn.put(column, analysis);
            }
            else
            {
                String defaultValue = ColumnDefaultValueSupport.read(column);
                Set<String> options = (type == TYPE_SINGLE_SELECT || type == TYPE_MULTI_SELECT)
                        ? ColumnDefaultValueSupport.parseSelectOptions(column) : null;
                String[] excelValues = new String[rowCount];
                boolean[] violations = new boolean[rowCount];
                boolean hasMissing = false;
                LinkedHashSet<String> newOptions = new LinkedHashSet<>();
                for (int r = 0; r < rowCount; r++)
                {
                    String text = mapping.cellText(rows.get(r), headerIndex).trim();
                    if (!text.isEmpty() && ExcelColumnMatcher.validateCellText(column, text) == null)
                    {
                        excelValues[r] = text;
                        // R19 新选项：单选整格、多选拆分逐个匹配（全角逗号先归一，与预检/选项集口径对称）
                        if (type == TYPE_SINGLE_SELECT && options != null && !options.contains(text))
                        {
                            newOptions.add(text);
                        }
                        else if (type == TYPE_MULTI_SELECT && options != null)
                        {
                            for (String part : text.replace("，", ",").split(","))
                            {
                                String candidate = part.trim();
                                if (!candidate.isEmpty() && !options.contains(candidate))
                                {
                                    newOptions.add(candidate);
                                }
                            }
                        }
                    }
                    else
                    {
                        // 空单元格（无默认值才算缺参）或类型违规（一律缺参，走补参链路，R11）
                        violations[r] = !text.isEmpty();
                        if (defaultValue == null || !text.isEmpty())
                        {
                            hasMissing = true;
                        }
                    }
                }
                excelValuesByColumn.put(column, excelValues);
                violationsByColumn.put(column, violations);
                if (hasMissing)
                {
                    stage2MissingColumns.add(columnName);
                }
                if (!newOptions.isEmpty())
                {
                    newOptionsByColumnId.put(column.getId(), newOptions);
                }
            }
        }

        // ===== 反向漂移 fail-fast（KTD5 全触发集 + 替换删除漂移） =====
        Set<String> deletedColumnNames = new LinkedHashSet<>();
        for (NoteColumn deleted : deletedColumns)
        {
            if (deleted != null && deleted.getName() != null)
            {
                deletedColumnNames.add(deleted.getName().trim());
            }
        }
        checkDrift(stage2MissingColumns, missNamesByColumn, ambiguityByColumn, deletedColumnNames, params);

        // ===== 补参服务端校验（R26）与预解析 =====
        validateColumnValues(params, columnByName);
        RelationResolution relations = resolveRelationSelections(params, columnByName,
                headerIndexByColumn, contextByTableId, noteId, userId);

        // ===== 终解析：三源合并 + 补参应用，构建逐行写入计划 =====
        // 参与列默认值预解析（循环外一次解析 property JSON，避免逐空单元格重复解析）
        Map<Long, String> defaultValuesByColumnId = new HashMap<>();
        for (NoteColumn column : participants)
        {
            defaultValuesByColumnId.put(column.getId(), ColumnDefaultValueSupport.read(column));
        }
        ImportPlan plan = new ImportPlan();
        plan.noteId = noteId;
        plan.dwtableId = dwtableId;
        plan.userId = userId;
        plan.participants.addAll(participants);
        plan.newOptionsByColumnId = newOptionsByColumnId;
        plan.pendingColumns.addAll(pendingColumns);
        plan.deletedColumns.addAll(deletedColumns);
        plan.unhideColumns.addAll(unhideColumns);
        plan.survivingColumnIds.addAll(referencedIds);
        plan.crossTableRecomputeColumns.addAll(collectCrossTableRecomputeColumns(noteId, dwtableId, mapping));
        // U4：本表归属笔记规范值（25 列重建行 linkNoteId = NoteDwtable.noteId，
        // 历史 null 的 COALESCE 兜底规则仅用于查询、不进新建/重建行）
        plan.linkNoteId = dwtable.getNoteId();
        plan.semanticAnalysisByColumn.putAll(semanticAnalysisByColumn);
        for (int r = 0; r < rowCount; r++)
        {
            RowPlan rowPlan = new RowPlan();
            for (NoteColumn column : participants)
            {
                Long type = column.getType();
                boolean link = type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK;
                boolean semantic = type == ExcelColumnMatcher.TYPE_SEMANTIC_LINK;
                NoteDwtableItem item = new NoteDwtableItem();
                item.setDwtId(dwtableId);
                item.setColumnId(column.getId());
                if (link)
                {
                    LinkColumnAnalysis analysis = linkAnalysisByColumn.get(column);
                    // U3：21 列用分析段的双轨解析产物（ID 优先 + 名称回退）；18 列走补参优先解析
                    List<NoteRecord> resolved = type == ExcelColumnMatcher.TYPE_DOUBLE_LINK
                            ? analysis.recordsPerRow.get(r)
                            : resolveLinkRecords(column, analysis.namesPerRow.get(r),
                                    relations, analysis.context.getRecordsByName());
                    if (!resolved.isEmpty())
                    {
                        // 21/18 列 link 字段经扩展批量 insert 落库（P0：不得被批量 insert 丢弃）
                        item.setLinkRecordId(joinRecordIds(resolved));
                        item.setValue(joinRecordNames(resolved));
                        item.setLinkColumnId(analysis.backFieldId);
                        if (type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
                        {
                            SymmetricLink symmetric = new SymmetricLink();
                            symmetric.sourceItem = item;
                            symmetric.backFieldId = analysis.backFieldId;
                            symmetric.relatedTableId = analysis.context.getTable().getId();
                            symmetric.records = resolved;
                            rowPlan.symmetricLinks.add(symmetric);
                        }
                    }
                    else
                    {
                        // R15：空单元格/显式留空/死链回读跳过 → 空串 value，不建立关联
                        item.setValue("");
                    }
                }
                else if (semantic)
                {
                    // U4：25 列 item 的 value=名称条目串原样（显示值，与导出文本列口径一致）；
                    // NoteNotelink 行由事务段 25 重建段按解析产物恢复/新建（item 自增 id 是行内坐标）
                    String semanticText = mapping.cellText(rows.get(r),
                            headerIndexByColumn.get(column).intValue()).trim();
                    item.setValue(semanticText);
                }
                else
                {
                    item.setValue(resolveBasicValue(column, r,
                            excelValuesByColumn.get(column), violationsByColumn.get(column),
                            params.getColumnValues(), defaultValuesByColumnId));
                }
                rowPlan.items.add(item);
            }
            // name 派生：替换后最终列集合中首个 type=1 列的本行最终值（kept 列优先，无则取首个新建文本列）
            if (nameColumnId != null)
            {
                rowPlan.name = deriveRowName(nameColumnId, rowPlan.items);
            }
            else if (namePendingSource != null)
            {
                String pendingName = r < namePendingSource.values.length ? namePendingSource.values[r] : "";
                rowPlan.name = pendingName == null ? "" : pendingName;
            }
            else
            {
                rowPlan.name = "";
            }
            plan.rows.add(rowPlan);
        }

        // ===== U5 入链重指向锚点：record_id 首列（导出固定首列）→ 行索引 =====
        // 旧记录 id 精确映射的主路径（KTD 裁决：名称回退仅是外部手写文件的兜底）；
        // 空/非法单元格值不进映射（该行视为无旧 id 的新增行）；重复 id 首行生效
        plan.recordIdColumnPresent = recordIdHeaderPresent(selection);
        if (plan.recordIdColumnPresent)
        {
            plan.rowIndexByLegacyRecordId.putAll(rowIndexByLegacyRecordId(rows, mapping));
        }

        // ===== U5 互指声明集：kept 21 列逐行解析目标（配对列旧条目存活 ⇔ 对侧行声明含该记录；
        // Excel 声明集 = 21 列单元格解析出的目标集合，事务段重指向的互指一致性判定输入） =====
        for (NoteColumn column : participants)
        {
            if (column.getType() == null || column.getType() != ExcelColumnMatcher.TYPE_DOUBLE_LINK)
            {
                continue;
            }
            LinkColumnAnalysis analysis = linkAnalysisByColumn.get(column);
            if (analysis == null)
            {
                continue;
            }
            List<Set<Long>> declaredPerRow = new ArrayList<>(rowCount);
            for (int r = 0; r < rowCount; r++)
            {
                Set<Long> targets = new LinkedHashSet<>();
                for (NoteRecord target : analysis.recordsPerRow.get(r))
                {
                    if (target != null && target.getId() != null)
                    {
                        targets.add(target.getId());
                    }
                }
                declaredPerRow.add(targets);
            }
            plan.declaredTargetsByColumnId.put(column.getId(), declaredPerRow);
        }

        // ===== 重算编排清单（KTD8，替换式扩展） =====
        buildRecomputeTargets(plan, columns, headerIndexByColumn);
        return plan;
    }

    /**
     * 反向漂移 fail-fast（KTD5 全触发集，任一命中抛"数据已变化，请重新预检"整体拒绝）：
     * <ol>
     *   <li>阶段二未命中名 ∉ baseline.missNames（被关联记录被删等）；</li>
     *   <li>歧义集合与 baseline 不一致（新增同名记录 / 预选变化）；</li>
     *   <li>缺参列 ∉ baseline.missingColumns（列配置/默认值变化等）；</li>
     *   <li>替换删除列 ∉ baseline.replaceDeletedColumns（预检后新建/改名的列将被
     *       用户未确认过的删除——防"确认的删除清单 ≠ 实际删除"）。</li>
     * </ol>
     * 补参 recordId 已不存在的校验在 {@link #resolveRelationSelections}（查库，KTD4 预解析记录集）。
     */
    private static void checkDrift(Set<String> stage2MissingColumns, Map<String, Set<String>> missNamesByColumn,
            Map<String, Map<String, Long>> ambiguityByColumn, Set<String> deletedColumnNames,
            ExcelImportParams params)
    {
        ExcelImportParams.PrecheckBaseline baseline = params.getPrecheckBaseline() == null
                ? new ExcelImportParams.PrecheckBaseline() : params.getPrecheckBaseline();

        // 1. 未命中名漂移
        Map<String, Set<String>> baselineMissByColumn = new HashMap<>();
        for (ExcelImportParams.BaselineName miss : baseline.getMissNames())
        {
            if (miss == null || miss.getColumnName() == null || miss.getName() == null)
            {
                continue;
            }
            baselineMissByColumn.computeIfAbsent(miss.getColumnName(), k -> new HashSet<>()).add(miss.getName());
        }
        for (Map.Entry<String, Set<String>> entry : missNamesByColumn.entrySet())
        {
            Set<String> baselineNames = baselineMissByColumn.get(entry.getKey());
            for (String name : entry.getValue())
            {
                if (baselineNames == null || !baselineNames.contains(name))
                {
                    throw driftException("关联列“" + entry.getKey() + "”出现预检之外的未命中名“" + name + "”");
                }
            }
        }

        // 2. 歧义集合漂移（新增同名记录 / 预选变化）
        Map<String, Map<String, Long>> baselineAmbiguityByColumn = new HashMap<>();
        for (ExcelImportParams.BaselineAmbiguity ambiguity : baseline.getAmbiguity())
        {
            if (ambiguity == null || ambiguity.getColumnName() == null || ambiguity.getName() == null)
            {
                continue;
            }
            baselineAmbiguityByColumn.computeIfAbsent(ambiguity.getColumnName(), k -> new HashMap<>())
                    .put(ambiguity.getName(), ambiguity.getPreselectedRecordId());
        }
        for (Map.Entry<String, Map<String, Long>> entry : ambiguityByColumn.entrySet())
        {
            Map<String, Long> baselineByColumn = baselineAmbiguityByColumn.get(entry.getKey());
            if (baselineByColumn == null)
            {
                baselineByColumn = Collections.emptyMap();
            }
            for (Map.Entry<String, Long> ambiguity : entry.getValue().entrySet())
            {
                Long baselinePreselect = baselineByColumn.get(ambiguity.getKey());
                if (baselinePreselect == null)
                {
                    throw driftException("关联列“" + entry.getKey() + "”的“" + ambiguity.getKey()
                            + "”在预检后出现新的同名记录");
                }
                if (!baselinePreselect.equals(ambiguity.getValue()))
                {
                    throw driftException("关联列“" + entry.getKey() + "”的“" + ambiguity.getKey()
                            + "”预选记录已变化");
                }
            }
        }

        // 3. 缺参列漂移
        Set<String> baselineMissingColumns = new HashSet<>();
        for (String columnName : baseline.getMissingColumns())
        {
            if (columnName != null)
            {
                baselineMissingColumns.add(columnName);
            }
        }
        for (String columnName : stage2MissingColumns)
        {
            if (!baselineMissingColumns.contains(columnName))
            {
                throw driftException("列“" + columnName + "”在预检后出现新的缺参");
            }
        }

        // 4. 替换删除漂移：本次将删的列 ∉ baseline.replaceDeletedColumns
        //（预检后新建的列不在 Excel 表头中 → 将被用户未确认过的删除）
        Set<String> baselineDeletedColumns = new HashSet<>();
        for (String columnName : baseline.getReplaceDeletedColumns())
        {
            if (columnName != null)
            {
                baselineDeletedColumns.add(columnName);
            }
        }
        for (String columnName : deletedColumnNames)
        {
            if (!baselineDeletedColumns.contains(columnName))
            {
                throw driftException("列“" + columnName + "”在预检后出现，本次导入将删除它");
            }
        }
    }

    private static ServiceException driftException(String detail)
    {
        return new ServiceException("数据已变化，请重新预检（" + detail + "）");
    }

    /**
     * 补参 columnValues 服务端校验（R26）：列须存在且为六类基础列，
     * 非空补值过 {@link ExcelColumnMatcher#validateCellText} 同语义类型校验（空串=显式补空）。
     */
    private static void validateColumnValues(ExcelImportParams params, Map<String, NoteColumn> columnByName)
    {
        for (Map.Entry<String, String> entry : params.getColumnValues().entrySet())
        {
            String columnName = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (value == null || value.trim().isEmpty())
            {
                continue;
            }
            NoteColumn column = columnByName.get(columnName);
            if (column == null)
            {
                throw new ServiceException("补参列“" + columnName + "”在目标数据表中不存在，请重新预检");
            }
            if (column.getType() == null || !BASIC_TYPES.contains(column.getType()))
            {
                throw new ServiceException("补参列“" + columnName + "”不是基础列，不支持文本补值，请重新预检");
            }
            String violation = ExcelColumnMatcher.validateCellText(column, value);
            if (violation != null)
            {
                throw new ServiceException("列“" + columnName + "”的补参值类型非法：" + violation);
            }
        }
    }

    /**
     * 补参 relationSelections 服务端校验（R26）与预解析：键=列名+名；
     * 列须为参与导入（表头映射）的 18/21 关联列；recordId 非空时须属于该列被关联表且存在
     * （查 KTD4 预解析记录集）——不存在即漂移拒绝。
     *
     * @return 列名 → {名 → 选择 / 名 → 选中记录} 双映射（补参优先语义的解析基础）
     */
    private RelationResolution resolveRelationSelections(ExcelImportParams params,
            Map<String, NoteColumn> columnByName, Map<NoteColumn, Integer> headerIndexByColumn,
            Map<Long, LinkTableContext> contextByTableId, Long noteId, Long userId)
    {
        RelationResolution resolution = new RelationResolution();
        for (ExcelImportParams.RelationSelection selection : params.getRelationSelections())
        {
            if (selection == null || selection.getColumnName() == null || selection.getName() == null)
            {
                throw new ServiceException("导入参数中的关联补参缺少列名或记录名，请重新预检");
            }
            String columnName = selection.getColumnName().trim();
            NoteColumn column = columnByName.get(columnName);
            if (column == null || column.getType() == null
                    || (column.getType() != ExcelColumnMatcher.TYPE_SINGLE_LINK
                            && column.getType() != ExcelColumnMatcher.TYPE_DOUBLE_LINK))
            {
                throw new ServiceException("补参选择的关联列“" + columnName + "”不存在或不是关联列，请重新预检");
            }
            if (!headerIndexByColumn.containsKey(column))
            {
                throw new ServiceException("补参选择的关联列“" + columnName + "”未参与本次导入，请重新预检");
            }
            resolution.selectionsByName
                    .computeIfAbsent(columnName, k -> new LinkedHashMap<String, ExcelImportParams.RelationSelection>())
                    .put(selection.getName(), selection);
            if (selection.getRecordId() != null)
            {
                // R26：recordId 须属于该列被关联表且存在（查库——KTD4 预解析记录集，id 索引 O(1)）
                LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
                NoteRecord record = context.getRecordsById().get(selection.getRecordId());
                if (record == null)
                {
                    throw driftException("关联列“" + columnName + "”补参选择的记录（id="
                            + selection.getRecordId() + "）不存在或已被删除");
                }
                resolution.selectedRecordsByName
                        .computeIfAbsent(columnName, k -> new LinkedHashMap<String, NoteRecord>())
                        .put(selection.getName(), record);
            }
        }
        return resolution;
    }

    /**
     * 解析一个关联单元格的最终记录序列（补参优先，KTD5；U3 起 18 列专属——21 列走
     * 双轨解析产物 {@code recordsPerRow}，无补参层）：
     * <ul>
     *   <li>补参覆盖（列名+名）以补参为准——recordId=null 显式留空不建立关联（即便重新匹配命中），
     *       recordId 非空用补选记录；</li>
     *   <li>未覆盖的键用当下匹配结果（无歧义命中或歧义预选 sort 靠前首条）；</li>
     *   <li>阶段二未命中且无补参选择：漂移检查已保证 ∈ baseline missNames，
     *       前端契约要求显式发射 null 选择——缺失即补参不完整，拒绝（禁止静默降级丢关联）。</li>
     * </ul>
     * 同单元格按记录 id 去重保序（"张三,张三" 或补选与命中共指同一记录）。
     */
    private static List<NoteRecord> resolveLinkRecords(NoteColumn column, List<String> names,
            RelationResolution relations, Map<String, List<NoteRecord>> recordsByName)
    {
        if (names == null || names.isEmpty())
        {
            return Collections.emptyList();
        }
        String columnName = column.getName() == null ? "" : column.getName().trim();
        Map<String, ExcelImportParams.RelationSelection> selectionsByName =
                relations.selectionsByName.getOrDefault(columnName, Collections.emptyMap());
        Map<String, NoteRecord> selectedRecordsByName =
                relations.selectedRecordsByName.getOrDefault(columnName, Collections.emptyMap());
        Map<Long, NoteRecord> resolved = new LinkedHashMap<>();
        for (String name : names)
        {
            ExcelImportParams.RelationSelection selection = selectionsByName.get(name);
            if (selection != null)
            {
                // 补参优先（KTD5）：覆盖的键以补参为准
                if (selection.getRecordId() != null)
                {
                    NoteRecord selected = selectedRecordsByName.get(name);
                    if (selected != null)
                    {
                        resolved.putIfAbsent(selected.getId(), selected);
                    }
                }
                continue;
            }
            List<NoteRecord> matched = recordsByName.get(name);
            if (matched != null && !matched.isEmpty())
            {
                resolved.putIfAbsent(matched.get(0).getId(), matched.get(0));
            }
            else
            {
                throw new ServiceException("关联列“" + columnName + "”的未命中名“" + name
                        + "”缺少补参选择，请重新预检并完成补参");
            }
        }
        return new ArrayList<>(resolved.values());
    }

    /**
     * 基础列单元格三源合并（R21）：Excel 值 &gt; 列默认值 &gt; 用户补参；NULL/空 → 空串。
     * 类型违规单元格不用 Excel 值也不用默认值（预检将其送入补参弹框，导入以补参为准）；
     * 复选框显示值映射 true→'0'（勾选）/false→'1'（未勾选）（U2 偏离决定，三源统一映射）。
     * 默认值经 {@code defaultValuesByColumnId} 预解析传入（避免逐单元格重复解析 property JSON）。
     */
    private static String resolveBasicValue(NoteColumn column, int rowIndex, String[] excelValues,
            boolean[] violations, Map<String, String> columnValues, Map<Long, String> defaultValuesByColumnId)
    {
        String columnName = column.getName() == null ? "" : column.getName().trim();
        if (excelValues != null && rowIndex < excelValues.length && excelValues[rowIndex] != null)
        {
            return mapCheckboxValue(column, excelValues[rowIndex]);
        }
        boolean violation = violations != null && rowIndex < violations.length && violations[rowIndex];
        if (!violation)
        {
            String defaultValue = defaultValuesByColumnId.get(column.getId());
            if (defaultValue != null)
            {
                return mapCheckboxValue(column, defaultValue);
            }
        }
        return mapCheckboxValue(column, columnValues.get(columnName));
    }

    /**
     * 复选框(7)显示值映射为 item.value 消费值：true→'0'（勾选）、false→'1'（未勾选）；
     * 其余类型原样返回；null → 空串（R21）。
     */
    private static String mapCheckboxValue(NoteColumn column, String value)
    {
        if (value == null)
        {
            return "";
        }
        if (column.getType() != null && column.getType() == TYPE_CHECKBOX)
        {
            String trimmed = value.trim();
            if ("true".equals(trimmed))
            {
                return "0";
            }
            if ("false".equals(trimmed))
            {
                return "1";
            }
        }
        return value;
    }

    /**
     * 派生行 name：首个 type=1 列在本行 items 中的最终值（无匹配/无该列返回空串），
     * 与 SQL 导入 deriveName / deriveRecordName 语义一致。
     */
    private static String deriveRowName(Long nameColumnId, List<NoteDwtableItem> items)
    {
        if (nameColumnId == null)
        {
            return "";
        }
        for (NoteDwtableItem item : items)
        {
            if (nameColumnId.equals(item.getColumnId()))
            {
                return item.getValue() == null ? "" : item.getValue();
            }
        }
        return "";
    }

    private static String joinRecordIds(List<NoteRecord> records)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < records.size(); i++)
        {
            if (i > 0)
            {
                builder.append(",");
            }
            builder.append(records.get(i).getId());
        }
        return builder.toString();
    }

    private static String joinRecordNames(List<NoteRecord> records)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < records.size(); i++)
        {
            if (i > 0)
            {
                builder.append(",");
            }
            builder.append(records.get(i).getName() == null ? "" : records.get(i).getName());
        }
        return builder.toString();
    }

    /**
     * 构建重算编排清单（KTD8，<b>替换式语义扩展</b>）：
     * <ol>
     *   <li>目标表全部 <b>保留</b>的 lookup(26) 列（两步级联，含隐藏列——隐藏只影响显示不影响
     *       存储值；<b>被删列绝不能进</b>——{@code recomputeLookupColumnValues} 对不存在 item
     *       会 upsert 重建，为已删列重建 items 即数据损坏）；</li>
     *   <li>目标表保留列中 columnA/B 直接引用本次导入（已映射）18/21 列的集合运算列；</li>
     *   <li>每个对称写入涉及的（源 21 列 C，被关联表 B，配对列 P）：
     *       B 表中锚定列（double_link_column_id）指向 C 的 lookup 列（读取被修改的配对 P items）
     *       与 columnA/B 直接引用 P 的集合运算列；</li>
     *   <li><b>替换清空/删除引发的跨表锚定重算</b>：items 被清空（kept 21 配对列、他表指向
     *       本表的 18 列）或被删（deleted 21 列及其配对列）的列，锚定/引用它们的其他表
     *       lookup 与集合运算列须重算——覆盖 rows 为空的纯清空场景（对 SymmetricLink
     *       驱动的 case-3 的补全：kept 21 列 Excel 全空单元格时无 symmetricLinks）。</li>
     * </ol>
     */
    private void buildRecomputeTargets(ImportPlan plan, List<NoteColumn> columns,
            Map<NoteColumn, Integer> headerIndexByColumn)
    {
        Set<Long> deletedColumnIds = new HashSet<>();
        for (NoteColumn deleted : plan.deletedColumns)
        {
            if (deleted != null && deleted.getId() != null)
            {
                deletedColumnIds.add(deleted.getId());
            }
        }

        Set<Long> importedLinkColumnIds = new HashSet<>();
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null || column.getId() == null
                    || deletedColumnIds.contains(column.getId()) || ExcelColumnMatcher.isHidden(column))
            {
                continue;
            }
            Long type = column.getType();
            if ((type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
                    && headerIndexByColumn.containsKey(column))
            {
                importedLinkColumnIds.add(column.getId());
            }
        }
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null || column.getId() == null
                    || deletedColumnIds.contains(column.getId()))
            {
                continue;
            }
            if (column.getType() == TYPE_LOOKUP)
            {
                plan.targetLookupColumns.add(column);
            }
            else if (column.getType() == TYPE_SET_OPERATION
                    && (referencesColumnId(column, importedLinkColumnIds)))
            {
                plan.targetSetColumns.add(column);
            }
        }

        Map<Long, List<NoteColumn>> relatedColumnsByTableId = new HashMap<>();
        Set<Long> processedSourceColumns = new HashSet<>();
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                Long sourceColumnId = link.sourceItem.getColumnId();
                if (!processedSourceColumns.add(sourceColumnId))
                {
                    continue;
                }
                List<NoteColumn> relatedColumns = relatedColumnsByTableId
                        .computeIfAbsent(link.relatedTableId, tableId -> {
                            NoteColumn query = new NoteColumn();
                            query.setDwtableId(tableId);
                            return noteColumnMapper.selectNoteColumnList(query);
                        });
                SymmetricTarget target = new SymmetricTarget();
                String sourceColumnIdStr = String.valueOf(sourceColumnId);
                for (NoteColumn related : relatedColumns)
                {
                    if (related == null || related.getType() == null)
                    {
                        continue;
                    }
                    if (related.getType() == TYPE_LOOKUP)
                    {
                        // B 表锚定列指向源列 C：该 lookup 读取被对称写入修改的配对 P items
                        String anchor = parsePropertySafe(related).getString("double_link_column_id");
                        if (anchor != null && sourceColumnIdStr.equals(anchor.trim()))
                        {
                            target.relatedLookups.add(related);
                        }
                    }
                    else if (related.getType() == TYPE_SET_OPERATION)
                    {
                        // B 表 columnA/B 直接引用配对列 P（读取配对 P items）
                        if (link.backFieldId != null && referencesColumnId(related,
                                Collections.singleton(link.backFieldId)))
                        {
                            target.relatedSetColumns.add(related);
                        }
                    }
                }
                plan.symmetricTargets.add(target);
            }
        }

        // ===== case-4：替换清空/删除引发的跨表锚定重算（rows 为空的纯清空场景也要覆盖） =====
        // affectedIds = items 被清空/被删的跨表列 id：crossTableRecomputeColumns（kept 21 配对列 + 他表 18 列）
        // + deleted 21 列及其配对列；scanTableIds = 这些列所在的表 + deleted 21 的被关联表
        //（锚定列 double_link_column_id 指向对端 21 列，读取所在表配对 items——须扫对端所在表）
        Set<Long> affectedIds = new HashSet<>();
        Set<Long> scanTableIds = new LinkedHashSet<>();
        for (NoteColumn cleared : plan.crossTableRecomputeColumns)
        {
            if (cleared == null || cleared.getId() == null)
            {
                continue;
            }
            affectedIds.add(cleared.getId());
            if (cleared.getDwtableId() != null)
            {
                scanTableIds.add(cleared.getDwtableId());
            }
        }
        for (NoteColumn deleted : plan.deletedColumns)
        {
            if (deleted == null || deleted.getId() == null || deleted.getType() == null
                    || deleted.getType() != ExcelColumnMatcher.TYPE_DOUBLE_LINK)
            {
                continue;
            }
            affectedIds.add(deleted.getId());
            Long pairId = parseBackFieldIdLenient(deleted);
            if (pairId != null)
            {
                affectedIds.add(pairId);
            }
            Long relatedTableId = parseTableIdLenient(deleted);
            if (relatedTableId != null)
            {
                scanTableIds.add(relatedTableId);
            }
        }
        for (Long tableId : scanTableIds)
        {
            if (tableId == null || tableId.equals(plan.dwtableId))
            {
                continue;   // 目标表保留列已由 case-1/2 覆盖
            }
            for (NoteColumn related : relatedColumnsByTableId.computeIfAbsent(tableId, tableId2 -> {
                NoteColumn query = new NoteColumn();
                query.setDwtableId(tableId2);
                return noteColumnMapper.selectNoteColumnList(query);
            }))
            {
                if (related == null || related.getType() == null || related.getId() == null)
                {
                    continue;
                }
                if (related.getType() == TYPE_LOOKUP)
                {
                    // 锚定列（double_link_column_id）指向受影响列：其读取的 items 已被清空/删除
                    String anchor = parsePropertySafe(related).getString("double_link_column_id");
                    if (anchor != null && containsColumnId(anchor, affectedIds))
                    {
                        plan.targetLookupColumns.add(related);
                    }
                }
                else if (related.getType() == TYPE_SET_OPERATION
                        && referencesColumnId(related, affectedIds))
                {
                    plan.targetSetColumns.add(related);
                }
            }
        }
    }

    /**
     * 集合运算列的 columnAId/columnBId 是否引用指定列 id 集合中的任一列。
     */
    private static boolean referencesColumnId(NoteColumn setColumn, Set<Long> columnIds)
    {
        JSONObject prop = parsePropertySafe(setColumn);
        return containsColumnId(prop.getString("columnAId"), columnIds)
                || containsColumnId(prop.getString("columnBId"), columnIds);
    }

    private static boolean containsColumnId(String value, Set<Long> columnIds)
    {
        if (value == null || value.trim().isEmpty())
        {
            return false;
        }
        try
        {
            return columnIds.contains(Long.valueOf(value.trim()));
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    /**
     * 事务写入段（R22，经自注入代理进入，<b>替换式语义</b>）：
     * <ol>
     *   <li>漂移复查（kept 参与列/被关联表配对列存在性 + pending 新列名冲突，fail-fast 于任何写入之前）；</li>
     *   <li>入链捕获与重指向预判（U5，<b>全删前</b>）：他表指向本表旧记录的 items 按 linkRecordId
     *       全量反查捕获 + 本表旧 items 坐标/旧记录名快照；名称回退歧义 fail-fast（零写入）；</li>
     *   <li><b>替换阶段（rows 为空也执行——只有表头的 Excel = 清表重建列）</b>：全删本表 items/records →
     *       级联删被删 21 列的配对列 → 删旧列 + 清孤儿 notelink → insert 新建列（id 回填）→ 取消隐藏
     *       （原"清空跨表关联引用"的整列清空已由 (h1) 入链重指向取代，R13）；</li>
     *   <li>写入阶段（仅 rows 非空）：逐行 NoteRecord insert（sort 从 1 递增、name 已派生、自增 id 回填）→
     *       kept items + pending 列 items 累积 ≥500 flush → 选项追加（FOR UPDATE）→
     *       items 全量最终 flush（零缓冲不变量）；</li>
     *   <li>入链重指向（U5，(h1)：建新落库回填新 id 后、21 对称写回之前——rows 为空的纯清空场景
     *       同样执行）："旧 item→新 item"映射三字段重写他表 21/18 入链
     *       （record_id 精确映射主路径 + 名称回退兜底；互指一致性/确已不存在 → 条目级清空+报告）；</li>
     *   <li>21 对称写回（(h2)，仅 rows 非空，时序：入链重指向之后）；</li>
     *   <li>25 列语义链接重建（U4，(h3)，rows 为空也执行清旧行——悬空 notelink 不残留）：每列先清旧行，
     *       `_ID` 命中按行 id upsert 恢复原行、名称回退 insert 新行；</li>
     *   <li>重算编排（<b>无条件执行</b>——清空引发的跨表重算不依赖新行）。</li>
     * </ol>
     * 任一步骤抛 {@link ServiceException} 整体回滚（含跨表重指向与选项追加）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int executeImport(ImportPlan plan)
    {
        // (a) 漂移复查：fail-fast 于任何写入之前
        verifyReplacePreconditions(plan);
        verifyBackFieldsExist(plan);

        // (a2) U5 入链重指向·捕获与预判（<b>全删前</b>）：他表指向本表旧记录的 items 按
        //      linkRecordId 全量反查捕获（不复用列扫描——属性残缺的入链同样按数据面捕获，
        //      防空配漏捕），本表旧 items 坐标与旧记录名一并快照；名称回退歧义在此
        //      fail-fast（预检清单的事务段兜底，先于任何写入，整表零写入）
        RepointContext repointContext = captureInboundLinks(plan.noteId, plan.dwtableId);
        verifyInboundRepointNoAmbiguity(plan, repointContext);

        // ===== (b) 替换阶段：全删本表行与单元格（rows 为空也执行） =====
        noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(plan.dwtableId);
        noteRecordMapper.deleteNoteRecordByDwtableId(plan.dwtableId);

        // (c) U5/R13：原"清空跨表关联引用（kept 21 配对列 + 他表 18 列）"的整列清空已由
        //     (h1) 入链重指向取代——旧引用按"旧 item→新 item"映射三字段精准重写
        //     （映射未覆盖者名称回退/条目清空+报告），他表 value 显示值不再被连带抹除

        // (d) 级联删被删 21 列的配对列及其 items（guard 防误删本表存活列；R13：语义保持不变）
        cascadeDeletePairedColumns(plan);

        // (e) 删旧列 + 清孤儿语义链接（本表 deleted 列的 items 已随 (b) 删）
        for (NoteColumn deleted : plan.deletedColumns)
        {
            if (deleted == null || deleted.getId() == null)
            {
                continue;
            }
            noteNotelinkMapper.deleteNoteNotelinkByColumnId(deleted.getId());
            noteColumnMapper.deleteNoteColumnById(deleted.getId());
        }

        // (f) 落库新建列（type=1，sort 递增，id 回填供 items 组装）
        createPendingColumns(plan);

        // (g) 取消隐藏（Excel 出现同名列即取消隐藏导数据）
        unhideColumns(plan);

        // ===== (h) 写入阶段（仅 rows 非空） =====
        int recordCount = 0;
        if (!plan.rows.isEmpty())
        {
            // 记录归属视图：网格数据接口按视图 viewId 过滤记录（无 viewId 的记录不显示），
            // UI 建行携带当前视图 id，导入分配目标表首视图（dataList 为全视图并集，任一视图即可显示）
            Long targetViewId = resolveDefaultViewId(plan.dwtableId);
            Long maxSort = noteRecordMapper.selectMaxSortByDwtableId(plan.dwtableId);
            long sort = (maxSort == null ? 0L : maxSort.longValue()) + 1L;
            List<NoteDwtableItem> batch = new ArrayList<>(BATCH_LIMIT);
            for (RowPlan row : plan.rows)
            {
                NoteRecord record = new NoteRecord();
                record.setDwtableId(plan.dwtableId);
                record.setViewId(targetViewId);
                record.setSort(Long.valueOf(sort++));
                record.setName(row.name);
                // property/linkRecordId/linkName 保持 null（对齐 SQL 导入）
                noteRecordMapper.insertNoteRecord(record);
                row.record = record;
                for (NoteDwtableItem item : row.items)
                {
                    item.setRecordId(record.getId());
                }
                batch.addAll(row.items);
                // pending 新建列的 items（列 id 已在 (f) 回填；空单元格为空串，不产生缺参）
                for (PendingColumn pending : plan.pendingColumns)
                {
                    NoteDwtableItem item = new NoteDwtableItem();
                    item.setDwtId(plan.dwtableId);
                    item.setColumnId(pending.column.getId());
                    item.setRecordId(record.getId());
                    String value = recordCount < pending.values.length ? pending.values[recordCount] : "";
                    item.setValue(value == null ? "" : value);
                    batch.add(item);
                }
                recordCount++;
                if (batch.size() >= BATCH_LIMIT)
                {
                    noteDwtableItemMapper.insertNoteDwtableItems(batch);
                    // 重新分配而非 clear：已传递给 mapper 的列表不再原地变更
                    batch = new ArrayList<>(BATCH_LIMIT);
                }
            }
            // 单选/多选未知选项追加（R19，FOR UPDATE 锁定读改写，每列上限 100）
            appendNewOptions(plan);
            // 零缓冲不变量：全部本表 item 落库有自增 id（对称写入的 linkItemId 须引用之）
            if (!batch.isEmpty())
            {
                noteDwtableItemMapper.insertNoteDwtableItems(batch);
            }
        }

        // (h1) 入链重指向（U5，R11-R13，取代原 (c) 整列清空）：<b>建新落库（回填新 id）后、
        //      21 对称写回 (h2) 之前</b>执行——借对称写回既有 FOR UPDATE 读取与 CSV 去重
        //      合并旧 id→新 id，避免配对列重复条目；rows 为空的纯清空场景同样执行
        //     （捕获条目全部走单元格级清空+报告，全删后他表旧引用不残留）
        executeInboundRepoint(plan, repointContext);

        // (h2) 21 对称写回（仅 rows 非空；原 (h) 尾段拆出——时序：入链重指向之后）
        if (!plan.rows.isEmpty())
        {
            executeSymmetricWrite(plan);
        }

        // (h3) 25 列语义链接重建（U4，21 对称写回之后、跨表重算之前；rows 为空也执行——
        // 只有表头的清空场景同样要清旧行，指向已删 items 的悬空 notelink 不残留）
        rebuildSemanticLinks(plan);

        // (i) 重算编排（无条件执行：清空/删除引发的跨表重算不依赖新行）
        executeRecompute(plan);

        log.info("[EXCEL-IMPORT] 替换式导入完成 noteId={}, dwtableId={}, userId={}, recordCount={}, "
                        + "deletedColumns={}, createdColumns={}, unhiddenColumns={}, crossTableRecomputeColumns={}, "
                        + "newOptionColumns={}, symmetricTargets={}",
                plan.noteId, plan.dwtableId, plan.userId, recordCount,
                plan.deletedColumns.size(), plan.pendingColumns.size(), plan.unhideColumns.size(),
                plan.crossTableRecomputeColumns.size(), plan.newOptionsByColumnId.size(), plan.symmetricTargets.size());
        return recordCount;
    }

    /**
     * 替换式漂移复查（事务段入口，扩展 #13 两方向）：
     * <ol>
     *   <li>kept 参与列仍存在（防前置段列快照与事务段之间参与列被并发删除，
     *       导致批量 insert 写入指向已删列的孤儿 item）；</li>
     *   <li>pending 新建列名不得与现存列冲突（TOCTOU 窗口内用户新建同名列 → 拒绝重新预检；
     *       计划期同名列恒不存在，冲突必为窗口内新增）。</li>
     * </ol>
     * deleted 列已不存在 → 后续 delete 影响 0 行，无害不校验。
     */
    private void verifyReplacePreconditions(ImportPlan plan)
    {
        NoteColumn query = new NoteColumn();
        query.setDwtableId(plan.dwtableId);
        Set<Long> currentColumnIds = new HashSet<>();
        Set<String> currentNames = new HashSet<>();
        for (NoteColumn current : noteColumnMapper.selectNoteColumnList(query))
        {
            if (current == null)
            {
                continue;
            }
            if (current.getId() != null)
            {
                currentColumnIds.add(current.getId());
            }
            if (current.getName() != null && !current.getName().trim().isEmpty())
            {
                currentNames.add(current.getName().trim());
            }
        }
        for (NoteColumn participant : plan.participants)
        {
            if (participant == null || !currentColumnIds.contains(participant.getId()))
            {
                throw driftException("列“" + (participant == null || participant.getName() == null
                        ? "" : participant.getName()) + "”已被删除");
            }
        }
        for (PendingColumn pending : plan.pendingColumns)
        {
            String name = pending.column.getName() == null ? "" : pending.column.getName().trim();
            if (currentNames.contains(name))
            {
                throw driftException("列“" + name + "”已存在，无法新建同名列");
            }
        }
    }

    /**
     * 级联删除被删 21 列的配对列及其 items（照 {@code NoteDwtableServiceImpl.deleteNoteDwtableById}
     * 先例，不复制其疑似传参 bug）。Guard：配对列 id 属于本表存活列（kept/skip-referenced——
     * 自引用表且配对列被 Excel 保留）或已在本表删除清单（重复删）时跳过；跨表配对列无条件级联。
     */
    private void cascadeDeletePairedColumns(ImportPlan plan)
    {
        Set<Long> deletedIds = new HashSet<>();
        for (NoteColumn deleted : plan.deletedColumns)
        {
            if (deleted != null && deleted.getId() != null)
            {
                deletedIds.add(deleted.getId());
            }
        }
        Set<Long> pairIdsToDelete = new LinkedHashSet<>();
        for (NoteColumn deleted : plan.deletedColumns)
        {
            if (deleted == null || deleted.getId() == null || deleted.getType() == null
                    || deleted.getType() != ExcelColumnMatcher.TYPE_DOUBLE_LINK)
            {
                continue;
            }
            Long pairId = parseBackFieldIdLenient(deleted);
            if (pairId == null || pairId.equals(deleted.getId()))
            {
                // 配置缺失/自指异常：跳过级联（不阻断导入，残留由既有删表先例容忍）
                log.warn("[EXCEL-IMPORT] 被删 21 列（id={}）的配对列配置缺失或异常，跳过级联删除",
                        deleted.getId());
                continue;
            }
            if (plan.survivingColumnIds.contains(pairId) || deletedIds.contains(pairId))
            {
                // 自引用且配对列被 Excel 保留 / 已在本表删除清单：跳过（防误删 kept 列/重复删）
                continue;
            }
            pairIdsToDelete.add(pairId);
        }
        for (Long pairId : pairIdsToDelete)
        {
            noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(pairId);
            noteNotelinkMapper.deleteNoteNotelinkByColumnId(pairId);
            noteColumnMapper.deleteNoteColumnById(pairId);
        }
    }

    /**
     * 落库待新建列（替换式语义）：纯文本列（type=1、property=null、isShow=0，
     * 对齐 {@code NoteViewServiceImpl} 默认列形态），sort 从事务段现存列最大值递增，
     * useGeneratedKeys 回填 id 供 items 组装。
     */
    private void createPendingColumns(ImportPlan plan)
    {
        if (plan.pendingColumns.isEmpty())
        {
            return;
        }
        NoteColumn query = new NoteColumn();
        query.setDwtableId(plan.dwtableId);
        long maxSort = 0L;
        for (NoteColumn current : noteColumnMapper.selectNoteColumnList(query))
        {
            if (current != null && current.getSort() != null)
            {
                maxSort = Math.max(maxSort, current.getSort().longValue());
            }
        }
        for (PendingColumn pending : plan.pendingColumns)
        {
            pending.column.setSort(Long.valueOf(++maxSort));
            noteColumnMapper.insertNoteColumn(pending.column);
        }
    }

    /**
     * 取消隐藏（替换式语义：Excel 出现同名列即取消隐藏导数据）：重查后仅改 isShow=0，
     * {@code updateNoteColumn} 动态 SET（null 字段不覆盖）。列已被并发删除则跳过
     * （漂移复查后的窄窗口，删除级联已处理）。
     */
    private void unhideColumns(ImportPlan plan)
    {
        for (NoteColumn column : plan.unhideColumns)
        {
            NoteColumn fresh = column.getId() == null ? null
                    : noteColumnMapper.selectNoteColumnById(column.getId());
            if (fresh == null)
            {
                continue;
            }
            fresh.setIsShow(0L);
            noteColumnMapper.updateNoteColumn(fresh);
        }
    }

    /**
     * 解析导入记录的目标视图 id：网格数据接口（{@code selectDataListByDwtableId}）按
     * 视图 viewId 过滤记录——viewId 为空的记录不显示；UI 建行携带当前视图 id，
     * 导入分配目标表首视图（dataList 为全视图并集，挂任一视图即可显示）。
     * 表无视图（异常形态，dataList 本就返回空）返回 null，退化为不携带。
     */
    private Long resolveDefaultViewId(Long dwtableId)
    {
        List<NoteView> views = noteViewMapper.selectNoteViewListByDwtableId(dwtableId);
        if (views == null || views.isEmpty() || views.get(0) == null)
        {
            return null;
        }
        return views.get(0).getId();
    }

    /**
     * #13 漂移校验（被关联表配对列方向）：双链对称写入前按 (relatedTableId, backFieldId)
     * 全集（直接遍历 plan.rows 的 symmetricLinks，不随 buildRecomputeTargets 的
     * processedSourceColumns 去重漏配对）校验每个配对列仍存在于被关联表——
     * 列删除不清理源列 property 的 back_field_id 残留引用（parseBackFieldId 读到旧值不报错），
     * 必须显式校验配对列存在性，否则 upsert 会写入指向已删列的孤儿配对 item。
     */
    private void verifyBackFieldsExist(ImportPlan plan)
    {
        Map<Long, String> sourceNamesByColumnId = new HashMap<>();
        for (NoteColumn participant : plan.participants)
        {
            sourceNamesByColumnId.put(participant.getId(),
                    participant.getName() == null ? "" : participant.getName());
        }
        Map<Long, Set<Long>> backFieldIdsByTableId = new LinkedHashMap<>();
        Map<Long, String> sourceNameByBackFieldId = new HashMap<>();
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                if (link.backFieldId != null)
                {
                    backFieldIdsByTableId
                            .computeIfAbsent(link.relatedTableId, k -> new LinkedHashSet<>())
                            .add(link.backFieldId);
                    sourceNameByBackFieldId.putIfAbsent(link.backFieldId,
                            sourceNamesByColumnId.get(link.sourceItem.getColumnId()));
                }
            }
        }
        for (Map.Entry<Long, Set<Long>> entry : backFieldIdsByTableId.entrySet())
        {
            NoteColumn query = new NoteColumn();
            query.setDwtableId(entry.getKey());
            Set<Long> relatedColumnIds = new HashSet<>();
            for (NoteColumn related : noteColumnMapper.selectNoteColumnList(query))
            {
                if (related != null && related.getId() != null)
                {
                    relatedColumnIds.add(related.getId());
                }
            }
            for (Long backFieldId : entry.getValue())
            {
                if (!relatedColumnIds.contains(backFieldId))
                {
                    throw driftException("关联列“" + sourceNameByBackFieldId.get(backFieldId)
                            + "”的配对列（id=" + backFieldId + "）已被删除");
                }
            }
        }
    }

    /**
     * 单选/多选未知选项追加（R19）：读列 property（FOR UPDATE 锁定）→ select 逗号字符串
     * 去重追加新选项 → 直更 property（保留 select 之外的其他键，KTD6 保留不变量）；
     * 每列每次导入新选项上限 {@value #NEW_OPTION_LIMIT}，超限抛 {@link ServiceException} 中止回滚。
     */
    private void appendNewOptions(ImportPlan plan)
    {
        for (Map.Entry<Long, LinkedHashSet<String>> entry : plan.newOptionsByColumnId.entrySet())
        {
            LinkedHashSet<String> candidates = entry.getValue();
            if (candidates.isEmpty())
            {
                continue;
            }
            NoteColumn locked = noteColumnMapper.selectNoteColumnByIdForUpdate(entry.getKey());
            if (locked == null)
            {
                throw driftException("选项列（id=" + entry.getKey() + "）已被删除");
            }
            JSONObject prop = parsePropertySafe(locked);
            String select = prop.getString(ColumnDefaultValueSupport.SELECT_KEY);
            Set<String> existing = ColumnDefaultValueSupport.parseSelectOptions(locked);
            List<String> toAppend = new ArrayList<>();
            for (String candidate : candidates)
            {
                if (!existing.contains(candidate) && !toAppend.contains(candidate))
                {
                    toAppend.add(candidate);
                }
            }
            if (toAppend.isEmpty())
            {
                continue;
            }
            if (toAppend.size() > NEW_OPTION_LIMIT)
            {
                throw new ServiceException("列“" + (locked.getName() == null ? "" : locked.getName())
                        + "”本次导入将新增选项 " + toAppend.size() + " 个，超过上限 " + NEW_OPTION_LIMIT
                        + "，请先整理选项后重试");
            }
            String merged = (select == null || select.trim().isEmpty())
                    ? String.join(",", toAppend) : select + "," + String.join(",", toAppend);
            prop.put(ColumnDefaultValueSupport.SELECT_KEY, merged);
            locked.setProperty(prop.toJSONString());
            noteColumnMapper.updateNoteColumn(locked);
        }
    }

    // ==================== 入链重指向（U5，R11-R13，取代整列清空） ====================

    /**
     * 入链重指向的预检分析（U5，R12 裁决：预检阶段即完成解析判定；U6：影响面呈现）——
     * 歧义预判 + 重指向/清空影响面分类。
     * <p>
     * 行定位主路径为 record_id+回填新 id 的 ID 精确映射；<b>record_id 精确映射未覆盖的
     * 旧记录走名称回退</b>（对齐 21 列单元格内 ID 未命中→名称回退语义）——外部手写文件
     * （无 record_id 首列）全量回退；陈旧导出文件（record_id 为导出时旧 id、与当前旧记录
     * id 失配）同样回退，回退也失败才视为"目标确已不存在"清空+报告（理由注明旧版导出
     * 提示）。候选名为导入后新记录名的近似派生（名称列单元格文本 ∪ 默认值；补参值待
     * 阶段二按 {@link RowPlan#name} 权威判定——此处未命中仅降级为清空报告，不误放行歧义）。
     * <ul>
     *   <li>名称回退歧义 → 链接失败清单（与 U3/U4 同机制并列阻断，确认前零写入）；</li>
     *   <li>影响面分类（对齐 {@link #executeInboundRepoint} 的执行口径，U6 语义迁移）：
     *       行定位命中且互指声明覆盖 → {@link RepointedLinkColumn}（将重指向的列/单元格数/
     *       条目数）；目标确已不存在（精确映射与名称回退均未命中）或互指声明消失 →
     *       {@link WillClearLinkEntry}（将单元格级清空并报告的条目，不阻断，R12 裁决）。</li>
     * </ul>
     */
    private void analyzeInboundRepoint(ExcelImportPrecheckResult result, Long noteId, Long dwtableId,
            SheetSelection selection, List<NoteColumn> columns, ColumnMapping mapping,
            Map<Long, List<Set<Long>>> declaredTargetsByColumnId)
    {
        RepointContext context = captureInboundLinks(noteId, dwtableId);
        if (context.involvedLegacyRecordIds.isEmpty())
        {
            return;
        }
        List<RowData> rows = selection.getRows();
        boolean recordIdPresent = recordIdHeaderPresent(selection);
        // 逐行新记录名近似派生 + 名称→行索引（对齐执行段 rowNameCandidates/rowIndexByName：
        // 全行名含空串、同名首行生效；空名不进索引——空旧记录名按"确已不存在"处理）
        List<String> rowNames = deriveRowNames(selection, columns, mapping);
        Map<String, Integer> rowIndexByName = new HashMap<>();
        for (int r = 0; r < rowNames.size(); r++)
        {
            String name = rowNames.get(r);
            if (name != null && !name.isEmpty())
            {
                rowIndexByName.putIfAbsent(name, Integer.valueOf(r));
            }
        }
        // record_id 精确映射（主路径；record_id 列缺失时为空表，全部走名称回退）
        Map<Long, Integer> exactRowIndex = recordIdPresent
                ? rowIndexByLegacyRecordId(rows, mapping) : Collections.emptyMap();

        // ===== 名称回退歧义预判 + 名称回退定位缓存（record_id 精确映射未覆盖的旧记录——
        // record_id 列缺失的外部手写文件全量走回退；陈旧导出文件（record_id 为导出时旧 id、
        // 与当前旧记录 id 全部/部分失配）同样回退；三态：歧义/命中/未命中）=====
        Set<Long> ambiguousLegacyIds = new HashSet<>();
        Map<Long, Integer> nameFallbackRowIndex = new HashMap<>();
        for (Long legacyId : context.involvedLegacyRecordIds)
        {
            if (recordIdPresent && exactRowIndex.containsKey(legacyId))
            {
                // record_id 精确映射命中：ID 主路径，无名称回退无歧义
                continue;
            }
            String legacyName = context.legacyRecordNameById.get(legacyId);
            if (legacyName == null || legacyName.trim().isEmpty())
            {
                // 空名记录无法按名称回退 → 按"确已不存在"清空+报告（不阻断）
                continue;
            }
            // 旧记录名为单名（非 CSV 单元格）——单条目解析，不拆 CSV
            LinkNameResolver.EntryResult entryResult = linkNameResolver.resolveEntry(legacyName, rowNames);
            if (entryResult == null)
            {
                continue;
            }
            if (entryResult.isAmbiguous())
            {
                ambiguousLegacyIds.add(legacyId);
                result.getLinkFailures().add(
                        repointAmbiguityFailure(context, legacyId, legacyName, entryResult));
                continue;
            }
            // 定位口径对齐 resolveRepointRowByName：唯一命中才定位；
            // 未命中（=回退也失败，目标确已不存在，清空+报告）
            if (entryResult.isMatched())
            {
                Integer located = rowIndexByName.get(entryResult.getMatchedName());
                if (located != null)
                {
                    nameFallbackRowIndex.put(legacyId, located);
                }
            }
        }

        // ===== 影响面分类（预检呈现口径；被级联删除的配对列条目随列消亡，不进影响面） =====
        Set<Long> cascadeDeletedSourceIds = new HashSet<>();
        for (DeletedColumnInfo deleted : result.getReplaceImpact().getDeletedColumns())
        {
            if (deleted != null && deleted.isCascadeDeletesPairedColumn() && deleted.getColumnId() != null)
            {
                cascadeDeletedSourceIds.add(deleted.getColumnId());
            }
        }
        Map<Long, RepointedLinkColumn> repointedByColumnId = new LinkedHashMap<>();
        for (NoteDwtableItem inbound : context.inboundItems)
        {
            if (inbound.getLinkColumnId() != null && cascadeDeletedSourceIds.contains(inbound.getLinkColumnId()))
            {
                // 配对列将随被删 21 列级联删除（条目随列消亡）——重指向 update 为 no-op，不呈现
                continue;
            }
            NoteColumn inboundColumn = context.inboundColumnById.get(inbound.getColumnId());
            // 互指判定前提：捕获条目所在列为 21 且 linkColumnId 指向本表 kept 21 参与列
            //（18 列单向无条件重指向；列属性/linkColumnId 残缺按 18 口径，不扩大破坏半径）
            List<Set<Long>> declaredPerRow = declaredTargetsForInbound(inboundColumn, inbound,
                    declaredTargetsByColumnId);
            boolean cellRepointed = false;
            for (String part : splitCsvToList(inbound.getLinkRecordId()))
            {
                Long legacyId = parseLongSafe(part.trim());
                // 异常成员（非本表旧记录的脏数据）原样保留、歧义成员已进失败清单——均不进影响面
                if (legacyId == null || !context.legacyRecordNameById.containsKey(legacyId)
                        || ambiguousLegacyIds.contains(legacyId))
                {
                    continue;
                }
                String legacyName = context.legacyRecordNameById.get(legacyId);
                Integer rowIndex = exactRowIndex.get(legacyId);
                if (rowIndex == null)
                {
                    rowIndex = nameFallbackRowIndex.get(legacyId);
                }
                if (rowIndex == null)
                {
                    // record_id 精确映射与名称回退均未命中（旧记录未出现在 Excel 中——
                    // record_id 列存在但 id 失配且名称回退也失败，文件可能为旧版导出）
                    // → 单元格级清空并报告
                    addWillClearLinkEntry(result.getReplaceImpact(), context, inbound, inboundColumn,
                            legacyName, "目标记录未出现在导入文件中（文件可能为旧版导出，建议重新导出）");
                    continue;
                }
                if (declaredPerRow != null && !declaredPerRow.get(rowIndex.intValue()).contains(inbound.getRecordId()))
                {
                    // 21 互指一致性：对侧行的声明集不含本条目所在记录 → 条目级清空并报告
                    addWillClearLinkEntry(result.getReplaceImpact(), context, inbound, inboundColumn,
                            legacyName, "导入计划已无对侧互指声明（互指一致性）");
                    continue;
                }
                RepointedLinkColumn repointed = repointedByColumnId.computeIfAbsent(inbound.getColumnId(), k ->
                        newRepointedLinkColumn(context, inbound, inboundColumn));
                repointed.setRepointedEntryCount(repointed.getRepointedEntryCount() + 1);
                cellRepointed = true;
            }
            if (cellRepointed)
            {
                RepointedLinkColumn repointed = repointedByColumnId.get(inbound.getColumnId());
                repointed.setAffectedCellCount(repointed.getAffectedCellCount() + 1);
            }
        }
        result.getReplaceImpact().getRepointedLinkColumns().addAll(repointedByColumnId.values());
    }

    /** 新建"将重指向的关联列"条目（表名/列名定位与 repointReport 同口径） */
    private static RepointedLinkColumn newRepointedLinkColumn(RepointContext context, NoteDwtableItem inbound,
            NoteColumn inboundColumn)
    {
        RepointedLinkColumn item = new RepointedLinkColumn();
        item.setColumnId(inbound.getColumnId());
        item.setTableName(inboundTableName(context, inbound));
        item.setColumnName(inboundColumnName(inbound, inboundColumn));
        return item;
    }

    /** 追加"将单元格级清空并报告"条目（表名/列名定位与 repointReport 同口径） */
    private static void addWillClearLinkEntry(ReplaceImpact impact, RepointContext context,
            NoteDwtableItem inbound, NoteColumn inboundColumn, String legacyName, String reason)
    {
        WillClearLinkEntry entry = new WillClearLinkEntry();
        entry.setTableName(inboundTableName(context, inbound));
        entry.setColumnName(inboundColumnName(inbound, inboundColumn));
        entry.setRecordName(legacyName == null || legacyName.isEmpty() ? "?" : legacyName);
        entry.setReason(reason);
        impact.getWillClearLinkEntries().add(entry);
    }

    /** 入链条目所在表名（查不到表定义时退化为表 id 字符串，与 repointReport 同口径） */
    private static String inboundTableName(RepointContext context, NoteDwtableItem inbound)
    {
        return context.tableNameByDwtId.containsKey(inbound.getDwtId())
                ? context.tableNameByDwtId.get(inbound.getDwtId()) : String.valueOf(inbound.getDwtId());
    }

    /** 入链条目所在列名（查不到列定义时退化为列 id 字符串，与 repointReport 同口径） */
    private static String inboundColumnName(NoteDwtableItem inbound, NoteColumn inboundColumn)
    {
        return inboundColumn == null || inboundColumn.getName() == null
                ? String.valueOf(inbound.getColumnId()) : inboundColumn.getName();
    }

    /**
     * record_id 首列的"旧记录 id → 行索引"精确映射（重复 id 首行生效；
     * 空/非法单元格值不进映射，该行视为无旧 id 的新增行——预检与执行段共用口径）。
     */
    private static Map<Long, Integer> rowIndexByLegacyRecordId(List<RowData> rows, ColumnMapping mapping)
    {
        Map<Long, Integer> index = new LinkedHashMap<>();
        for (int r = 0; r < rows.size(); r++)
        {
            Long legacyRecordId = parseLongSafe(mapping.cellText(rows.get(r), 0).trim());
            if (legacyRecordId != null)
            {
                index.putIfAbsent(legacyRecordId, Integer.valueOf(r));
            }
        }
        return index;
    }

    /**
     * 重指向名称歧义的失败清单条目（复用 {@link LinkFailure} 载体）：定位取首个指向该旧记录
     * 的捕获条目（他表表名.列名——重指向条目不在本表 Excel 中，rowNumber 无行号语义置 0，
     * 前端呈现按"表名.列名"定位文本）；原因说明 record_id 精确映射未覆盖（列缺失的
     * 外部手写文件全量回退 / 陈旧导出文件 id 失配）的回退场景与修正建议。
     */
    private static LinkFailure repointAmbiguityFailure(RepointContext context, Long legacyId, String legacyName,
            LinkNameResolver.EntryResult entryResult)
    {
        LinkFailure failure = new LinkFailure();
        for (NoteDwtableItem inbound : context.inboundItems)
        {
            for (String part : splitCsvToList(inbound.getLinkRecordId()))
            {
                if (legacyId.equals(parseLongSafe(part.trim())))
                {
                    NoteColumn column = context.inboundColumnById.get(inbound.getColumnId());
                    failure.setColumnId(inbound.getColumnId());
                    failure.setColumnName((context.tableNameByDwtId.get(inbound.getDwtId()) == null
                            ? "" : context.tableNameByDwtId.get(inbound.getDwtId()))
                            + "." + (column == null || column.getName() == null
                            ? String.valueOf(inbound.getColumnId()) : column.getName()));
                    break;
                }
            }
            if (failure.getColumnId() != null)
            {
                break;
            }
        }
        failure.setRowNumber(0);
        failure.setEntryText(legacyName);
        failure.setReason("入链重指向名称歧义（record_id 精确映射未覆盖该记录——文件缺少 record_id 列"
                + "或为旧版导出文件 id 失配，按名称回退时新记录候选同名多条，无法唯一确定重指向目标；"
                + "可在导出产物基础上修改以保留/修正 record_id 首列，或消除重名后重试）");
        failure.setExplanation(entryResult.getExplanation());
        return failure;
    }

    /**
     * 导入后逐行新记录名（预检近似派生，入链重指向名称回退用）：名称派生源经
     * {@link #selectNameSource} 共享选择（与 {@code buildImportPlan} 同源）——kept
     * （被表头引用）中首个 type=1 列，无则首个未匹配表头（新建文本列）；值为单元格
     * 文本 trim，空单元格取列默认值，仍空保留空串占位（行位与 selection.getRows()
     * 一一对应，供名称→行索引定位；对齐执行段 rowNameCandidates 含空串的口径。
     * 三源合并的补参终值由阶段二按 {@link RowPlan#name} 权威判定）。
     */
    private static List<String> deriveRowNames(SheetSelection selection, List<NoteColumn> columns,
            ColumnMapping mapping)
    {
        List<RowData> rows = selection.getRows();
        if (rows == null || rows.isEmpty())
        {
            return Collections.emptyList();
        }
        Set<Long> referencedIds = new HashSet<>();
        for (HeaderEntry entry : mapping.getEntries())
        {
            if (entry.getColumn() != null && entry.getColumn().getId() != null)
            {
                referencedIds.add(entry.getColumn().getId());
            }
            if (entry.getSkippedColumn() != null && entry.getSkippedColumn().getId() != null)
            {
                referencedIds.add(entry.getSkippedColumn().getId());
            }
        }
        NameSource source = selectNameSource(columns, referencedIds, mapping);
        if (source == null || (source.keptColumn != null && source.headerIndex < 0))
        {
            // 无名称派生源（无 kept 文本列且无未匹配表头）/ kept 列被引用未映射的防御形态：
            // 全部行名为空（名称回退必然未命中 → 清空+报告口径）
            return Collections.nCopies(rows.size(), "");
        }
        List<String> names = new ArrayList<>(rows.size());
        for (RowData row : rows)
        {
            String name = mapping.cellText(row, source.headerIndex).trim();
            if (name.isEmpty() && source.defaultValue != null)
            {
                name = source.defaultValue;
            }
            names.add(name);
        }
        return names;
    }

    /**
     * 名称派生源选择结果：kept type=1 列（或首个未匹配表头）的表头索引与默认值。
     */
    private static final class NameSource
    {
        /** kept type=1 名称列（null=无 kept 文本列，派生走首个未匹配表头的新建文本列） */
        final NoteColumn keptColumn;

        /** 名称列的表头索引（kept 列为其映射表头；kept 列为 null 时为首个未匹配表头索引） */
        final int headerIndex;

        /** kept 列默认值（仅 kept 列已映射时读取；派生近似名称的空单元格回退用） */
        final String defaultValue;

        NameSource(NoteColumn keptColumn, int headerIndex, String defaultValue)
        {
            this.keptColumn = keptColumn;
            this.headerIndex = headerIndex;
            this.defaultValue = defaultValue;
        }
    }

    /**
     * 名称派生源选择（替换后最终列集合的名称列，{@code buildImportPlan} 与
     * {@code deriveRowNames} 两处同源消费）：kept（被表头引用，含取消隐藏者）中首个
     * type=1 列（按列表序），无则首个未匹配表头（新建文本列）；两者皆无返回 null
     * （名称全部为空串派生）。kept 列被引用但未映射的防御形态（理论不可达——排除类型
     * 不含 1）headerIndex=-1、defaultValue=null：buildImportPlan 仍取 keptColumn.id 作
     * nameColumnId（与历史行为一致），deriveRowNames 按"无名称"处理。
     */
    private static NameSource selectNameSource(List<NoteColumn> columns, Set<Long> referencedIds,
            ColumnMapping mapping)
    {
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null || column.getId() == null)
            {
                continue;
            }
            if (referencedIds.contains(column.getId()) && column.getType() == 1L)
            {
                int headerIndex = -1;
                for (HeaderEntry entry : mapping.getMappedEntries())
                {
                    if (entry.getColumn() == column)
                    {
                        headerIndex = entry.getHeaderIndex();
                        break;
                    }
                }
                if (headerIndex < 0)
                {
                    // 防御：被引用的 type=1 列未映射（形态不可达——排除类型不含 1）
                    return new NameSource(column, -1, null);
                }
                return new NameSource(column, headerIndex, ColumnDefaultValueSupport.read(column));
            }
        }
        List<HeaderEntry> unmatched = mapping.getUnmatchedEntries();
        if (unmatched.isEmpty())
        {
            return null;
        }
        return new NameSource(null, unmatched.get(0).getHeaderIndex(), null);
    }

    /**
     * 捕获他表指向本表旧记录的入链 items 及配套快照（U5，R11；预检歧义预判与事务段
     * 重指向共用，<b>全删前调用</b>）。
     * <p>
     * 全量反查口径（不复用 collectCrossTableRecomputeColumns 列扫描——18 列 property.table_id
     * 残缺、21 配对列 back_field_id 残缺等属性缺失的入链同样按 linkRecordId 数据面
     * 捕获，防空配漏捕）：同 note 其他表 linkRecordId 非空的 items 全量读出，
     * Java 侧按 CSV 成员精确过滤（成员 ∈ 本表旧记录 id 集；查询侧与写入侧同源禁
     * LIKE 子串匹配——id 3 会误中 13）。一并快照：本表旧记录 id→名（名称回退输入）、
     * 本表旧 items id→坐标（linkItemId 逐位映射的坐标链：旧记录 id+列 id→旧 item id、
     * 新记录 id+同列 id→新 item id）。本表自引用的 items 不捕获（随全删清理，
     * 重指向无意义）。
     */
    private RepointContext captureInboundLinks(Long noteId, Long dwtableId)
    {
        RepointContext context = new RepointContext();
        // 本表旧记录快照（id→名；名称回退解析输入与"本表旧记录"成员判定基准）
        NoteRecordVo recordQuery = new NoteRecordVo();
        recordQuery.setDwtableId(dwtableId);
        for (NoteRecord legacy : noteRecordMapper.selectNoteRecordList(recordQuery))
        {
            if (legacy != null && legacy.getId() != null)
            {
                context.legacyRecordNameById.put(legacy.getId(),
                        legacy.getName() == null ? "" : legacy.getName());
            }
        }
        // 本表旧 items 坐标快照（id→(recordId, columnId)，linkItemId 重写的坐标链）
        NoteDwtableItem itemQuery = new NoteDwtableItem();
        itemQuery.setDwtId(dwtableId);
        for (NoteDwtableItem legacyItem : noteDwtableItemMapper.selectNoteDwtableItemList(itemQuery))
        {
            if (legacyItem != null && legacyItem.getId() != null)
            {
                context.legacyItemById.put(legacyItem.getId(), legacyItem);
            }
        }
        // 他表清单（同 note、排除本表）
        List<NoteDwtable> otherTables = new ArrayList<>();
        for (NoteDwtable table : noteDwtableMapper.selectDWTableList(noteId))
        {
            if (table != null && table.getId() != null && !table.getId().equals(dwtableId))
            {
                otherTables.add(table);
            }
        }
        if (otherTables.isEmpty() || context.legacyRecordNameById.isEmpty())
        {
            return context;
        }
        for (NoteDwtable table : otherTables)
        {
            context.tableNameByDwtId.put(table.getId(), table.getName() == null ? "" : table.getName());
        }
        List<Long> otherTableIds = new ArrayList<>(otherTables.size());
        for (NoteDwtable table : otherTables)
        {
            otherTableIds.add(table.getId());
        }
        // 全量反查 → Java 侧 CSV 成员精确过滤（成员 ∈ 本表旧记录 id 集）
        List<NoteDwtableItem> candidates = noteDwtableItemMapper.selectLinkItemsByDwtIds(otherTableIds);
        Set<Long> inboundColumnIds = new LinkedHashSet<>();
        for (NoteDwtableItem candidate : candidates == null
                ? Collections.<NoteDwtableItem>emptyList() : candidates)
        {
            if (candidate == null || candidate.getId() == null)
            {
                continue;
            }
            boolean involved = false;
            for (String part : splitCsvToList(candidate.getLinkRecordId()))
            {
                Long legacyId = parseLongSafe(part.trim());
                if (legacyId != null && context.legacyRecordNameById.containsKey(legacyId))
                {
                    context.involvedLegacyRecordIds.add(legacyId);
                    involved = true;
                }
            }
            if (involved)
            {
                context.inboundItems.add(candidate);
                if (candidate.getColumnId() != null)
                {
                    inboundColumnIds.add(candidate.getColumnId());
                }
            }
        }
        if (context.inboundItems.isEmpty())
        {
            return context;
        }
        // 捕获条目列定义（互指判定：21 配对列才检查对侧声明；查不到按 18 口径无条件重指向）
        for (NoteDwtable table : otherTables)
        {
            NoteColumn query = new NoteColumn();
            query.setDwtableId(table.getId());
            for (NoteColumn column : noteColumnMapper.selectNoteColumnList(query))
            {
                if (column != null && column.getId() != null && inboundColumnIds.contains(column.getId()))
                {
                    context.inboundColumnById.put(column.getId(), column);
                }
            }
        }
        return context;
    }

    /**
     * 事务段名称回退歧义 fail-fast（U5，R12：预检清单的事务段兜底——预检-导入间隙的
     * 他表数据变化或绕过预检直调时在此整体拒绝，<b>先于任何写入</b>，整表零写入）。
     * 候选名为 {@link RowPlan#name} 权威派生（三源合并终值，含补参/默认值）。
     * <p>
     * 覆盖 record_id 精确映射未覆盖的旧记录（列缺失的外部手写文件全量回退 / 陈旧导出
     * 文件 id 失配回退——与 {@code executeInboundRepoint} 的行定位口径一致）；精确映射
     * 命中者走 ID 主路径，无名称回退无歧义，跳过。
     */
    private void verifyInboundRepointNoAmbiguity(ImportPlan plan, RepointContext context)
    {
        if (context.involvedLegacyRecordIds.isEmpty())
        {
            return;
        }
        List<String> candidateNames = new ArrayList<>(plan.rows.size());
        for (RowPlan row : plan.rows)
        {
            candidateNames.add(row.name == null ? "" : row.name);
        }
        for (Long legacyId : context.involvedLegacyRecordIds)
        {
            if (plan.recordIdColumnPresent && plan.rowIndexByLegacyRecordId.containsKey(legacyId))
            {
                // record_id 精确映射命中：ID 主路径，无名称回退无歧义
                continue;
            }
            String legacyName = context.legacyRecordNameById.get(legacyId);
            if (legacyName == null || legacyName.trim().isEmpty())
            {
                continue;
            }
            // 旧记录名为单名（非 CSV 单元格）——单条目解析，不拆 CSV
            LinkNameResolver.EntryResult entryResult = linkNameResolver.resolveEntry(legacyName, candidateNames);
            if (entryResult != null && entryResult.isAmbiguous())
            {
                throw new ServiceException("导入未执行（零写入）：他表指向本表记录“" + sanitizeInline(legacyName)
                        + "”的入链在按名称回退重指向时产生名称歧义（匹配到多条同名新记录，"
                        + "无法唯一确定目标）；请在导出产物基础上修改以保留/修正 record_id 首列"
                        + "（ID 精确映射），或消除重名后重试（"
                        + entryResult.getExplanation() + "）");
            }
        }
    }

    /**
     * 执行入链重指向（U5，R11-R13；事务段：建新落库（回填新 id）后、21 对称写回之前——
     * 借对称写回既有 FOR UPDATE 读取与 CSV 去重合并旧 id→新 id，避免配对列重复条目）。
     * <p>
     * 对每个捕获的他表条目做 Java 侧读-改-写：<b>FOR UPDATE 锁读现值</b>（防"捕获-落库"
     * 间隙的并发 UI 编辑丢失更新，对齐 executeSymmetricWrite 的锁读先例；行已被级联删除
     * 时锁读 null，自然 no-op）→ 拆 linkRecordId/linkItemId/value 三串 →
     * 逐条目分类 → 去重合并（recordId/Set 去重、value 按记录追加保三串等长）→ join →
     * 按主键单行 update（{@code updateLinkFieldsById}，禁集合式 SQL REPLACE——逗号串子串
     * 误替换，如旧 id 3 被 REPLACE 误中 13）。
     * 分类（R12 裁决 + 陈旧导出文件幂等，四情形）：
     * <ul>
     *   <li>精确映射命中（record_id 首列声明旧记录 id）→ linkRecordId 换新记录 id、
     *       linkItemId 经坐标链换新 item id（旧记录 id+列 id→旧 item id、
     *       新记录 id+同列 id→新 item id）、value 刷新为新记录名
     *       （21 配对列与 18 入链同口径）；</li>
     *   <li>21 配对列的互指一致性：对侧行（新行）的源列声明集不含该条目所在记录 →
     *       条目级清空并报告（Excel 声明集 = 21 列单元格解析出的目标集合；
     *       18 列单向、无条件重指向，不受此约束）；</li>
     *   <li>record_id 精确映射未覆盖（列缺失的外部手写文件全量 / 陈旧导出文件 id 失配）
     *       → 名称回退（预检/事务段预判已保证无歧义；对齐 21 列单元格内 ID 未命中→
     *       名称回退语义），回退也失败 = 目标确已不存在；</li>
     *   <li>目标确已不存在（旧记录未出现在 Excel 中——精确映射与名称回退均未命中）→
     *       该条目单元格级全清（三字段清空、保留 linkColumnId——复刻
     *       clearLinkFieldsByColumnId 字段语义）并在导入结果报告（理由注明旧版导出提示）。</li>
     * </ul>
     * 被本表将级联删除的 21 列对应的配对列条目在循环开头跳过（对齐预检影响面口径——
     * 条目随列消亡，重指向 update 为 no-op，跳过防幽灵审计残留）。异常成员（id 非数字或
     * 不在本表旧记录全集——CSV 混入的脏数据）原样保留，不扩大破坏半径。
     * rows 为空的纯清空场景同样执行（捕获条目全部走单元格级清空+报告，全删后他表
     * 旧引用不残留）。清空与重指向计数随导入结果日志报告（用户可见呈现由预检
     * ReplaceImpact.repointedLinkColumns/willClearLinkEntries 承载，U6；本日志为执行实绩审计）。
     */
    private void executeInboundRepoint(ImportPlan plan, RepointContext context)
    {
        if (context.inboundItems.isEmpty())
        {
            return;
        }
        // 将级联删除的被删 21 列 id 集（其配对列条目随列消亡——对齐预检口径，跳过重指向）
        Set<Long> cascadeDeletedSourceIds = new HashSet<>();
        for (NoteColumn deleted : plan.deletedColumns)
        {
            if (deleted != null && deleted.getId() != null && deleted.getType() != null
                    && deleted.getType() == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
            {
                cascadeDeletedSourceIds.add(deleted.getId());
            }
        }
        // 新坐标索引：(新记录 id, 列 id) → 新 item（零缓冲不变量：批量 insert 已回填自增 id）
        Map<Long, Map<Long, NoteDwtableItem>> newItemsByRecordAndColumn = new HashMap<>();
        for (RowPlan row : plan.rows)
        {
            for (NoteDwtableItem item : row.items)
            {
                if (item.getRecordId() != null && item.getColumnId() != null)
                {
                    newItemsByRecordAndColumn
                            .computeIfAbsent(item.getRecordId(), k -> new HashMap<Long, NoteDwtableItem>())
                            .put(item.getColumnId(), item);
                }
            }
        }
        // 名称回退候选（record_id 精确映射未覆盖的旧记录）：行名列表 + 名→行（同名首行生效）
        List<String> rowNameCandidates = new ArrayList<>(plan.rows.size());
        Map<String, Integer> rowIndexByName = new HashMap<>();
        for (int r = 0; r < plan.rows.size(); r++)
        {
            String name = plan.rows.get(r).name == null ? "" : plan.rows.get(r).name;
            rowNameCandidates.add(name);
            rowIndexByName.putIfAbsent(name, Integer.valueOf(r));
        }
        int repointedEntries = 0;
        List<String> clearedReports = new ArrayList<>();
        for (NoteDwtableItem inbound : context.inboundItems)
        {
            if (inbound.getLinkColumnId() != null
                    && cascadeDeletedSourceIds.contains(inbound.getLinkColumnId()))
            {
                // 配对列将随被删 21 列级联删除（条目随列消亡）——重指向 update 为 no-op，
                // 跳过（对齐预检影响面口径，防清空/重指向计数与报告的幽灵残留）
                continue;
            }
            // FOR UPDATE 锁读现值（对齐 executeSymmetricWrite 的 selectNoteDwtableItemByRecordAndColumnForUpdate
            // 锁读先例）：捕获（全删前快照）与落库间隙他表行可能被并发 UI 编辑，锁读取最新
            // 已提交值合并防丢失更新；行已被级联删除/并发删除 → null，自然 no-op
            NoteDwtableItem current = inbound.getId() == null ? null
                    : noteDwtableItemMapper.selectNoteDwtableItemByIdForUpdate(inbound.getId());
            if (current == null)
            {
                continue;
            }
            List<String> oldRecordIds = splitCsvToList(current.getLinkRecordId());
            List<String> oldItemIds = splitCsvToList(current.getLinkItemId());
            List<String> oldValues = splitCsvToList(current.getValue());
            NoteColumn inboundColumn = context.inboundColumnById.get(current.getColumnId());
            // 互指判定前提：捕获条目所在列为 21 且 linkColumnId 指向本表 kept 21 参与列
            //（18 列单向无条件重指向；列属性/linkColumnId 残缺按 18 口径，不扩大破坏半径）
            List<Set<Long>> declaredPerRow = declaredTargetsForInbound(inboundColumn, current,
                    plan.declaredTargetsByColumnId);
            // 去重保序合并（recordId/itemId 用 Set；value 为 List 且仅 recordId 去重成立时追加——
            // 与 executeSymmetricWrite 同构，同名新记录各自追加 name，保三串等长）
            Set<String> mergedRecordIds = new LinkedHashSet<>();
            Set<String> mergedItemIds = new LinkedHashSet<>();
            List<String> mergedValues = new ArrayList<>();
            for (int j = 0; j < oldRecordIds.size(); j++)
            {
                Long legacyId = parseLongSafe(oldRecordIds.get(j).trim());
                if (legacyId == null || !context.legacyRecordNameById.containsKey(legacyId))
                {
                    // 异常成员（非本表旧记录的脏数据）：原样保留不扩大破坏半径
                    keepInboundEntry(j, oldRecordIds, oldItemIds, oldValues,
                            mergedRecordIds, mergedItemIds, mergedValues);
                    continue;
                }
                String legacyName = context.legacyRecordNameById.get(legacyId);
                // 行定位：record_id 精确映射优先；未覆盖者名称回退（预判已 fail-fast 歧义——
                // 外部手写文件全量回退 / 陈旧导出文件 id 失配回退）
                Integer rowIndex = plan.rowIndexByLegacyRecordId.get(legacyId);
                if (rowIndex == null)
                {
                    rowIndex = resolveRepointRowByName(legacyName, rowNameCandidates, rowIndexByName);
                }
                if (rowIndex == null)
                {
                    // 目标确已不存在（旧记录未出现在 Excel 中——精确映射与名称回退均未命中）
                    // → 条目单元格级全清 + 报告
                    clearedReports.add(repointReport(context, current, legacyName,
                            "目标记录未出现在导入文件中（文件可能为旧版导出，建议重新导出），已清空该入链条目"));
                    continue;
                }
                if (declaredPerRow != null && !declaredPerRow.get(rowIndex.intValue())
                        .contains(current.getRecordId()))
                {
                    // 21 互指一致性：对侧行的声明集不含本条目所在记录 → 条目级清空 + 报告
                    clearedReports.add(repointReport(context, current, legacyName,
                            "导入计划已无对侧互指声明，已清空该入链条目（互指一致性）"));
                    continue;
                }
                RowPlan row = plan.rows.get(rowIndex.intValue());
                String newRecordId = String.valueOf(row.record.getId());
                if (mergedRecordIds.add(newRecordId))
                {
                    mergedValues.add(row.record.getName() == null ? "" : row.record.getName());
                }
                // linkItemId 坐标链：旧 item id → 旧 (记录, 列) → 新 (新记录, 同列) → 新 item id
                //（映射不到的锚点丢弃——linkRecordId 是主键，21 场景理论恒命中：源列 kept 每行必有 item）
                Long legacyItemId = j < oldItemIds.size() ? parseLongSafe(oldItemIds.get(j).trim()) : null;
                NoteDwtableItem legacyItem = legacyItemId == null ? null
                        : context.legacyItemById.get(legacyItemId);
                NoteDwtableItem newItem = legacyItem == null ? null
                        : newItemsByRecordAndColumn
                                .getOrDefault(row.record.getId(), Collections.<Long, NoteDwtableItem>emptyMap())
                                .get(legacyItem.getColumnId());
                if (newItem != null && newItem.getId() != null)
                {
                    mergedItemIds.add(String.valueOf(newItem.getId()));
                }
                repointedEntries++;
            }
            // join 后与原值比对，变化才按主键单行 update（三字段；全清=空串，字段语义对齐列级清空）
            String mergedRecordIdText = String.join(",", mergedRecordIds);
            String mergedItemIdText = String.join(",", mergedItemIds);
            String mergedValueText = String.join(",", mergedValues);
            if (!mergedRecordIdText.equals(nullToEmpty(current.getLinkRecordId()))
                    || !mergedItemIdText.equals(nullToEmpty(current.getLinkItemId()))
                    || !mergedValueText.equals(nullToEmpty(current.getValue())))
            {
                noteDwtableItemMapper.updateLinkFieldsById(current.getId(),
                        mergedRecordIdText, mergedItemIdText, mergedValueText);
            }
        }
        // 导入结果报告（U5 最小实现：结构化日志；界面弹框/agent 结果呈现归 U6）——
        // 用户可控文本（表名/列名/旧记录名）单行化截断后进日志
        if (!clearedReports.isEmpty() || repointedEntries > 0)
        {
            List<String> sanitizedReports = new ArrayList<>(clearedReports.size());
            for (String report : clearedReports)
            {
                sanitizedReports.add(sanitizeInline(report));
            }
            log.info("[EXCEL-IMPORT] 入链重指向完成 noteId={}, dwtableId={}, repointedEntries={}, "
                            + "clearedEntries={}, details={}",
                    plan.noteId, plan.dwtableId, Integer.valueOf(repointedEntries),
                    Integer.valueOf(clearedReports.size()), sanitizedReports);
        }
    }

    /**
     * 名称回退定位新行（record_id 精确映射未覆盖的旧记录）：{@link LinkNameResolver}
     * 单条目管线容错（精确→归一化→唯一前缀，R7 口径）。旧记录名为单名（非 CSV 单元格），
     * 按单条目解析、不拆 CSV；歧义在预检/事务段预判已 fail-fast，此处为防御
     * （宁可中止不可写坏指向）。
     *
     * @return 命中的行索引；未命中/空名返回 null（=目标确已不存在）
     */
    private Integer resolveRepointRowByName(String legacyName, List<String> candidateNames,
            Map<String, Integer> rowIndexByName)
    {
        if (legacyName == null || legacyName.trim().isEmpty() || candidateNames == null)
        {
            return null;
        }
        LinkNameResolver.EntryResult result = linkNameResolver.resolveEntry(legacyName, candidateNames);
        if (result == null)
        {
            return null;
        }
        if (result.isAmbiguous())
        {
            throw new ServiceException("导入失败：他表指向本表记录“" + sanitizeInline(legacyName)
                    + "”的入链重指向名称歧义（" + result.getExplanation() + "）");
        }
        if (!result.isMatched())
        {
            return null;
        }
        return rowIndexByName.get(result.getMatchedName());
    }

    /**
     * 异常成员原样保留（id 非数字或不在本表旧记录全集——不进重指向分类，不扩大破坏半径）。
     * 三串追加均以 recordId 去重成立为前提（保 linkRecordId/linkItemId/value 等长对位）。
     */
    private static void keepInboundEntry(int index, List<String> oldRecordIds, List<String> oldItemIds,
            List<String> oldValues, Set<String> mergedRecordIds, Set<String> mergedItemIds,
            List<String> mergedValues)
    {
        if (mergedRecordIds.add(oldRecordIds.get(index)))
        {
            if (index < oldItemIds.size())
            {
                mergedItemIds.add(oldItemIds.get(index));
            }
            if (index < oldValues.size())
            {
                mergedValues.add(oldValues.get(index));
            }
        }
    }

    /**
     * 互指判定前提（U5，预检与执行两处共用）：捕获条目所在列为 21 且 linkColumnId 指向
     * 本表 kept 21 参与列时返回其逐行声明集；否则返回 null（18 列单向无条件重指向；
     * 列属性/linkColumnId 残缺按 18 口径，不扩大破坏半径）。
     */
    private static List<Set<Long>> declaredTargetsForInbound(NoteColumn inboundColumn,
            NoteDwtableItem inbound, Map<Long, List<Set<Long>>> declaredTargetsByColumnId)
    {
        if (inboundColumn != null && inboundColumn.getType() != null
                && inboundColumn.getType() == ExcelColumnMatcher.TYPE_DOUBLE_LINK
                && inbound.getLinkColumnId() != null
                && declaredTargetsByColumnId.containsKey(inbound.getLinkColumnId()))
        {
            return declaredTargetsByColumnId.get(inbound.getLinkColumnId());
        }
        return null;
    }

    /** 清空条目的导入结果报告文本（他表定位 + 旧记录名 + 原因，供日志与 U6 呈现；
     *  旧记录名为用户可控文本，进日志前经 {@link #sanitizeInline} 单行化截断） */
    private static String repointReport(RepointContext context, NoteDwtableItem inbound,
            String legacyName, String reason)
    {
        String tableName = inboundTableName(context, inbound);
        NoteColumn column = context.inboundColumnById.get(inbound.getColumnId());
        String columnName = inboundColumnName(inbound, column);
        return "他表“" + tableName + "”列“" + columnName + "”指向本表记录“"
                + (legacyName == null || legacyName.isEmpty() ? "?" : legacyName) + "”的条目：" + reason;
    }

    /** Excel 首列是否为导出固定的 record_id 源列（{@link ExcelColumnMatcher#RECORD_ID_HEADER}，
     * 与 matcher 的"首列且同名"识别口径一致；无该列 = 外部手写文件，重指向走名称回退） */
    private static boolean recordIdHeaderPresent(SheetSelection selection)
    {
        List<String> headers = selection.getHeaders();
        return headers != null && !headers.isEmpty() && ExcelColumnMatcher.RECORD_ID_HEADER
                .equals(headers.get(0) == null ? "" : headers.get(0).trim());
    }

    /** null 归一为空串（三字段 join 前后的原值比对用） */
    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    /** 用户可控文本进日志/异常的单行化上限（剥换行后截断，防日志注入与超长攻击面） */
    private static final int INLINE_TEXT_LIMIT = 100;

    /**
     * 用户可控文本（链接条目文本/旧记录名/清空报告等）进日志与异常前的单行化：
     * 剥 \r\n（防单元格内换行伪造日志行）并截断至 {@link #INLINE_TEXT_LIMIT} 字符
     * （超长以 … 结尾）。DTO 字段不受此处理（结构化呈现保留原文）。
     */
    private static String sanitizeInline(String text)
    {
        if (text == null)
        {
            return "";
        }
        String flattened = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (flattened.length() > INLINE_TEXT_LIMIT)
        {
            return flattened.substring(0, INLINE_TEXT_LIMIT) + "…";
        }
        return flattened;
    }

    /** 宽松解析十进制长整数（空白/非法返回 null，不抛异常） */
    private static Long parseLongSafe(String text)
    {
        if (text == null || text.isEmpty())
        {
            return null;
        }
        try
        {
            return Long.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * 双链(21)对称写入（KTD3，独立实现不复用 updateNoteRecord）：
     * <ul>
     *   <li>按配对 item（recordId+columnId）聚合——同配对 item 的全部追加在内存合并后单次落库；</li>
     *   <li>落库前 SELECT ... FOR UPDATE 锁定涉及行；锁内复核行存在性，不存在则 upsert 创建
     *       （KTD3 修正②：UI 路径 null-NPE 缺陷不复刻）；</li>
     *   <li>配对 item 四字段：linkRecordId 追加新记录 id、linkItemId 追加本表新 item id、
     *       value 追加新记录 name（去重键=recordId，KTD3 修正①：同名新记录都追加各自 name，
     *       保持 value 与 linkRecordId 等长）、linkColumnId=源列 id；</li>
     *   <li>本表源 item 回写 linkRecordId（命中记录 id 列表，批量 insert 已带）/
     *       linkItemId（配对 item id 列表，与 linkRecordId 顺序对齐）/linkColumnId（back_field_id）/
     *       value（命中记录 name 列表，批量 insert 已带）；</li>
     *   <li>序列化一律 String.join——绝不用 List.toString().replace。</li>
     * </ul>
     */
    private void executeSymmetricWrite(ImportPlan plan)
    {
        if (plan.rows.isEmpty())
        {
            return;
        }
        // 聚合：配对 item 键（recordId + columnId）→ 追加明细（保持行/列/记录顺序，确定性加锁顺序）
        Map<Long, Map<Long, PairedGroup>> groupsByRecordId = new LinkedHashMap<>();
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                for (NoteRecord target : link.records)
                {
                    groupsByRecordId
                            .computeIfAbsent(target.getId(), k -> new LinkedHashMap<Long, PairedGroup>())
                            .computeIfAbsent(link.backFieldId, k -> new PairedGroup(link.relatedTableId))
                            .appends.add(new SymmetricAppend(row.record, link.sourceItem));
                }
            }
        }
        // 配对 item 结果：(recordId, columnId) → 落库后的配对 item（含自增 id），供源 item 回写 linkItemId
        Map<Long, Map<Long, NoteDwtableItem>> pairedByRecordAndColumn = new HashMap<>();
        for (Map.Entry<Long, Map<Long, PairedGroup>> byRecord : groupsByRecordId.entrySet())
        {
            for (Map.Entry<Long, PairedGroup> byColumn : byRecord.getValue().entrySet())
            {
                Long targetRecordId = byRecord.getKey();
                Long pairedColumnId = byColumn.getKey();
                PairedGroup group = byColumn.getValue();
                for (SymmetricAppend append : group.appends)
                {
                    if (append.sourceItem.getId() == null)
                    {
                        // 零缓冲不变量防御：批量 insert 未回填自增 id 时宁可中止不可写坏数据
                        throw new ServiceException("导入失败：单元格数据落库后未取得自增 id，无法完成关联写入");
                    }
                }
                // KTD3：update 前 SELECT ... FOR UPDATE 锁定；锁内复核存在性，不存在则 upsert 创建
                NoteDwtableItem query = new NoteDwtableItem();
                query.setRecordId(targetRecordId);
                query.setColumnId(pairedColumnId);
                NoteDwtableItem paired = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(query);
                boolean created = paired == null;
                if (created)
                {
                    paired = new NoteDwtableItem();
                    paired.setDwtId(group.relatedTableId);
                    paired.setRecordId(targetRecordId);
                    paired.setColumnId(pairedColumnId);
                }
                // 内存合并全部追加（同配对 item 单次落库，无覆盖丢失）
                Set<String> linkRecordIds = splitCsvToSet(paired.getLinkRecordId());
                List<String> linkItemIds = splitCsvToList(paired.getLinkItemId());
                List<String> values = splitCsvToList(paired.getValue());
                for (SymmetricAppend append : group.appends)
                {
                    String sourceRecordId = String.valueOf(append.sourceRecord.getId());
                    // value 追加去重键=recordId（KTD3 修正①）：r 追加时才追加其 name
                    if (linkRecordIds.add(sourceRecordId))
                    {
                        values.add(append.sourceRecord.getName() == null ? "" : append.sourceRecord.getName());
                    }
                    String sourceItemId = String.valueOf(append.sourceItem.getId());
                    if (!linkItemIds.contains(sourceItemId))
                    {
                        linkItemIds.add(sourceItemId);
                    }
                    paired.setLinkColumnId(append.sourceItem.getColumnId());
                }
                paired.setLinkRecordId(String.join(",", linkRecordIds));
                paired.setLinkItemId(String.join(",", linkItemIds));
                paired.setValue(String.join(",", values));
                if (created)
                {
                    noteDwtableItemMapper.insertNoteDwtableItem(paired);
                }
                else
                {
                    noteDwtableItemMapper.updateNoteDwtableItem(paired);
                }
                pairedByRecordAndColumn
                        .computeIfAbsent(targetRecordId, k -> new HashMap<Long, NoteDwtableItem>())
                        .put(pairedColumnId, paired);
            }
        }
        // 本表源 item 回写 linkItemId（与 linkRecordId 顺序对齐，单次 update）
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                List<String> pairedItemIds = new ArrayList<>(link.records.size());
                for (NoteRecord target : link.records)
                {
                    NoteDwtableItem paired = pairedByRecordAndColumn.get(target.getId()).get(link.backFieldId);
                    pairedItemIds.add(String.valueOf(paired.getId()));
                }
                link.sourceItem.setLinkItemId(String.join(",", pairedItemIds));
                noteDwtableItemMapper.updateNoteDwtableItem(link.sourceItem);
            }
        }
    }

    /**
     * 25 列（语义关联）重建（U4，R2/R6；事务段 (h3)，21 对称写回之后、跨表重算之前）。
     * <p>
     * 每个被导入的 25 列：重建前按列调用既有 {@code deleteNoteNotelinkByColumnId} 清旧行
     * （旧行核心字段已在计划段按 `_ID` 捕获），随后逐条目重建：
     * <ul>
     *   <li>`_ID` 命中 → 按行 id upsert 恢复原行（{@code updateNoteNotelinkCoordinates}：
     *       保留行 id 与 blockId——源笔记正文锚点 data-link-id 不断链——恢复
     *       noteId/contextText/itemValue，刷新 linkRecordId/linkItemId 为本表新坐标、
     *       linkDwTableId=本表、linkNoteId=本表归属笔记规范值）；</li>
     *   <li>名称回退 → insert 新行（linkDwTableId=本表、linkColumnId/linkItemId=本表新坐标、
     *       linkNoteId 规范值、noteId=解析命中的引用来源笔记、blockId=null 接受锚点降级、
     *       contextText=锚词）。</li>
     * </ul>
     * rows 为空的纯清空场景同样执行清旧行（本表 items 已全删，悬空 notelink 不残留）。
     */
    private void rebuildSemanticLinks(ImportPlan plan)
    {
        for (Map.Entry<NoteColumn, SemanticColumnAnalysis> entry
                : plan.semanticAnalysisByColumn.entrySet())
        {
            NoteColumn column = entry.getKey();
            if (column == null || column.getId() == null)
            {
                continue;
            }
            SemanticColumnAnalysis analysis = entry.getValue();
            // 清旧行：本列全部旧 notelink（含 `_ID` 未引用的悬空行；命中行的核心字段已捕获，
            // 随后按行 id upsert 以原主键恢复——行 id 保留，data-link-id 不断链）
            noteNotelinkMapper.deleteNoteNotelinkByColumnId(column.getId());
            for (int r = 0; r < plan.rows.size(); r++)
            {
                RowPlan row = plan.rows.get(r);
                SemanticCellResolution cell = analysis.cellsPerRow.get(r);
                if (cell == null || (cell.restored.isEmpty() && cell.fallbacks.isEmpty()))
                {
                    continue;
                }
                NoteDwtableItem semanticItem = semanticItemOf(row, column.getId());
                if (semanticItem == null || semanticItem.getId() == null)
                {
                    // 防御：25 列 item 未落库或未回填自增 id（零缓冲不变量破坏），宁可中止不可写坏坐标
                    throw new ServiceException("导入失败：语义关联列“"
                            + (column.getName() == null ? "" : column.getName())
                            + "”的单元格数据落库后未取得自增 id，无法完成语义链接重建");
                }
                for (NoteNotelink restored : cell.restored)
                {
                    // 行 id 命中：恢复原行（核心字段按捕获值恢复，坐标刷新为本表新记录/新单元格）
                    NoteNotelink link = new NoteNotelink();
                    link.setId(restored.getId());
                    link.setNoteId(restored.getNoteId());
                    link.setBlockId(restored.getBlockId());
                    link.setContextText(restored.getContextText());
                    link.setItemValue(restored.getItemValue());
                    link.setLinkNoteId(plan.linkNoteId);
                    link.setLinkDwTableId(plan.dwtableId);
                    link.setLinkRecordId(row.record.getId());
                    link.setLinkColumnId(column.getId());
                    link.setLinkItemId(semanticItem.getId());
                    noteNotelinkMapper.updateNoteNotelinkCoordinates(link);
                }
                for (SemanticFallback fallback : cell.fallbacks)
                {
                    // 名称回退：新建行（blockId=null 锚点降级、contextText=锚词，
                    // itemValue 对齐前端创建路径取锚词）
                    NoteNotelink link = new NoteNotelink();
                    link.setNoteId(fallback.noteId);
                    link.setBlockId(null);
                    link.setContextText(fallback.anchor);
                    link.setItemValue(fallback.anchor);
                    link.setLinkNoteId(plan.linkNoteId);
                    link.setLinkDwTableId(plan.dwtableId);
                    link.setLinkRecordId(row.record.getId());
                    link.setLinkColumnId(column.getId());
                    link.setLinkItemId(semanticItem.getId());
                    noteNotelinkMapper.insertNoteNotelink(link);
                }
            }
        }
    }

    /**
     * 取行内指定 25 列的 item（批量 insert 落库后含自增 id，notelink 行内坐标引用之）。
     */
    private static NoteDwtableItem semanticItemOf(RowPlan row, Long columnId)
    {
        for (NoteDwtableItem item : row.items)
        {
            if (columnId.equals(item.getColumnId()))
            {
                return item;
            }
        }
        return null;
    }

    /**
     * 重算编排（KTD8，先 flush 后调用，经 {@link INoteRecordService} 接口——Spring 代理
     * 加入导入事务）：目标表全部 lookup 列两步级联 → 目标表直接引用导入 18/21 列的集合运算列 →
     * 对称写入涉及的被关联表以配对列为源的 lookup 与集合运算列；按列 id 去重避免重复重算。
     */
    private void executeRecompute(ImportPlan plan)
    {
        Set<Long> recomputedLookups = new HashSet<>();
        Set<Long> recomputedSetColumns = new HashSet<>();
        for (NoteColumn lookup : plan.targetLookupColumns)
        {
            if (!recomputedLookups.add(lookup.getId()))
            {
                continue;
            }
            noteRecordService.recomputeLookupColumnValues(lookup);
            noteRecordService.recomputeSetOperationsForLookup(lookup);
        }
        for (NoteColumn setColumn : plan.targetSetColumns)
        {
            if (!recomputedSetColumns.add(setColumn.getId()))
            {
                continue;
            }
            noteRecordService.recomputeSetOperationColumn(setColumn);
        }
        for (SymmetricTarget target : plan.symmetricTargets)
        {
            for (NoteColumn lookup : target.relatedLookups)
            {
                if (!recomputedLookups.add(lookup.getId()))
                {
                    continue;
                }
                noteRecordService.recomputeLookupColumnValues(lookup);
                noteRecordService.recomputeSetOperationsForLookup(lookup);
            }
            for (NoteColumn setColumn : target.relatedSetColumns)
            {
                if (!recomputedSetColumns.add(setColumn.getId()))
                {
                    continue;
                }
                noteRecordService.recomputeSetOperationColumn(setColumn);
            }
        }
    }

    // ==================== U5 内部结构 ====================

    /** 导入计划（非事务前置段产物，写入段消费；替换式语义扩展） */
    static final class ImportPlan
    {
        Long noteId;

        Long dwtableId;

        Long userId;

        /** 逐行写入计划（与数据行等长同序） */
        final List<RowPlan> rows = new ArrayList<>();

        /** 全部参与列（kept：事务段入口列存在性校验用，含错误消息所需列名） */
        final List<NoteColumn> participants = new ArrayList<>();

        /** 列 id → 有序去重新选项（R19，事务段 FOR UPDATE 追加） */
        Map<Long, LinkedHashSet<String>> newOptionsByColumnId = Collections.emptyMap();

        /** 目标表保留的 lookup(26) 列 + 跨表受影响 lookup 列（KTD8 两步级联；被删列绝不能进） */
        final List<NoteColumn> targetLookupColumns = new ArrayList<>();

        /** 目标表保留列中 columnA/B 直接引用导入 18/21 列的集合运算列 + 跨表受影响集合运算列（KTD8） */
        final List<NoteColumn> targetSetColumns = new ArrayList<>();

        /** 对称写入涉及的（源 21 列 → 被关联表派生列）编排清单（KTD8） */
        final List<SymmetricTarget> symmetricTargets = new ArrayList<>();

        /** 待新建列（未匹配表头 → 纯文本列，未落库无 id；事务段 insert 回填 id 后组装 items） */
        final List<PendingColumn> pendingColumns = new ArrayList<>();

        /** 将删除的现有列（不被任何表头引用的非系统列；21 列级联删配对列） */
        final List<NoteColumn> deletedColumns = new ArrayList<>();

        /** 匹配到隐藏列将取消隐藏（isShow 1→0）的列 */
        final List<NoteColumn> unhideColumns = new ArrayList<>();

        /** 本表存活列 id 集（mapped ∪ 排除类型同名列；级联删除 guard 用） */
        final Set<Long> survivingColumnIds = new HashSet<>();

        /** 其他表中涉及跨表重算的关联列清单（原名 clearedLinkColumns；导入路径已由入链重指向
         *  取代整列清空，事务段不再按此清空——纯作 buildRecomputeTargets 重算编排扫描输入，
         *  预检呈现走 repointedLinkColumns/willClearLinkEntries 数据面口径，U6 语义迁移完成） */
        final List<NoteColumn> crossTableRecomputeColumns = new ArrayList<>();

        /** U5 入链重指向：record_id 首列存在（true=旧记录 id 精确映射主路径；
         *  false=外部手写文件，名称回退兜底） */
        boolean recordIdColumnPresent;

        /** U5 入链重指向：Excel record_id 旧记录 id → 行索引（旧记录→新行的精确映射，重复 id 首行生效） */
        final Map<Long, Integer> rowIndexByLegacyRecordId = new LinkedHashMap<>();

        /** U5 互指声明集：本表 kept 21 列 id → 逐行声明的目标记录 id 集合
         *  （配对列旧条目存活 ⇔ 对侧行声明含该条目所在记录；18 列单向不受约束） */
        final Map<Long, List<Set<Long>>> declaredTargetsByColumnId = new LinkedHashMap<>();

        /** 本表归属笔记 id（25 列重建行 linkNoteId 的规范值 = NoteDwtable.noteId，U4） */
        Long linkNoteId;

        /** 25 列分析产物（U4：列 → 逐行解析结果，事务段 25 重建段消费） */
        final Map<NoteColumn, SemanticColumnAnalysis> semanticAnalysisByColumn =
                new IdentityHashMap<>();
    }

    /** 25 列（语义关联）的阶段二分析产物（U4） */
    private static final class SemanticColumnAnalysis
    {
        /** 逐行解析产物（与数据行等长同序：`_ID` 命中的待恢复旧行 + 名称回退的新建行预案） */
        final List<SemanticCellResolution> cellsPerRow = new ArrayList<>();
    }

    /** 入链重指向捕获上下文（U5，R11：全删前快照，预检歧义预判与事务段重指向共用） */
    private static final class RepointContext
    {
        /** 捕获的他表入链条目（linkRecordId CSV 含本表旧记录 id；含属性残缺入链） */
        final List<NoteDwtableItem> inboundItems = new ArrayList<>();

        /** 捕获条目列 id → 列定义（互指判定：21 配对列才检查对侧声明；查不到按 18 口径） */
        final Map<Long, NoteColumn> inboundColumnById = new HashMap<>();

        /** 捕获条目所在表 id → 表名（失败清单定位与结果报告用） */
        final Map<Long, String> tableNameByDwtId = new HashMap<>();

        /** 本表旧记录 id → 旧记录名（名称回退解析输入与"本表旧记录"成员判定基准） */
        final Map<Long, String> legacyRecordNameById = new LinkedHashMap<>();

        /** 本表旧 item id → 旧 item（linkItemId 坐标链：旧 (记录, 列) → 新 (新记录, 同列)） */
        final Map<Long, NoteDwtableItem> legacyItemById = new HashMap<>();

        /** 捕获条目涉及的本表旧记录 id 集（名称回退预判范围） */
        final Set<Long> involvedLegacyRecordIds = new LinkedHashSet<>();
    }

    /** 待新建列：未落库 NoteColumn（无 id，事务段 insert 后回填）+ 逐行预解析值（trim 文本） */
    private static final class PendingColumn
    {
        NoteColumn column;

        int headerIndex;

        String[] values;
    }

    /** 单行写入计划 */
    private static final class RowPlan
    {
        /** 本行 NoteRecord（写入段 insert 后回填，含自增 id 与 name） */
        NoteRecord record;

        /** 派生 name（首个 type=1 列最终值） */
        String name;

        /** 全部参与列的 item（批量 insert 用，写入段回填 recordId） */
        final List<NoteDwtableItem> items = new ArrayList<>();

        /** 21 列的对称写入明细 */
        final List<SymmetricLink> symmetricLinks = new ArrayList<>();
    }

    /** 单个 21 列在一行上的对称写入明细 */
    private static final class SymmetricLink
    {
        /** 源 item（(本行记录, 源 21 列)，批量 insert 后有自增 id；columnId=源列 C） */
        NoteDwtableItem sourceItem;

        /** 配对列 P（源列 back_field_id，被关联表 B 上） */
        Long backFieldId;

        /** 被关联表 B id（配对 item 不存在时 upsert 的 dwtId） */
        Long relatedTableId;

        /** 命中/补选的被关联记录（有序去重） */
        List<NoteRecord> records;
    }

    /** 一次对配对 item 的追加（源记录 + 源 item） */
    private static final class SymmetricAppend
    {
        final NoteRecord sourceRecord;

        final NoteDwtableItem sourceItem;

        SymmetricAppend(NoteRecord sourceRecord, NoteDwtableItem sourceItem)
        {
            this.sourceRecord = sourceRecord;
            this.sourceItem = sourceItem;
        }
    }

    /** 配对 item 聚合组（同一 (recordId, columnId) 的全部追加） */
    private static final class PairedGroup
    {
        final Long relatedTableId;

        final List<SymmetricAppend> appends = new ArrayList<>();

        PairedGroup(Long relatedTableId)
        {
            this.relatedTableId = relatedTableId;
        }
    }

    /** 对称写入涉及的被关联表派生列清单（KTD8 编排条目） */
    private static final class SymmetricTarget
    {
        /** B 表中锚定列（double_link_column_id）指向源 21 列 C 的 lookup 列 */
        final List<NoteColumn> relatedLookups = new ArrayList<>();

        /** B 表中 columnA/B 直接引用配对列 P 的集合运算列 */
        final List<NoteColumn> relatedSetColumns = new ArrayList<>();
    }

    /** 关联列的阶段二分析产物 */
    private static final class LinkColumnAnalysis
    {
        /** 被关联表上下文（R23 已校验 + KTD4 预解析） */
        LinkTableContext context;

        /** 配对列（21 必填 / 18 可空） */
        Long backFieldId;

        /** 逐行名字序列（KTD4 整串优先拆分；18 列用，21 列走 recordsPerRow） */
        final List<List<String>> namesPerRow = new ArrayList<>();

        /** 21 列逐行双轨解析产物（U3：ID 优先 + 名称回退；与数据行等长同序） */
        final List<List<NoteRecord>> recordsPerRow = new ArrayList<>();
    }

    /** 21 列单元格解析产物（U3）：命中记录（按记录 id 去重保序）+ 失败条目 */
    private static final class DoubleLinkCellResolution
    {
        private final Map<Long, NoteRecord> recordById = new LinkedHashMap<>();

        final List<LinkEntryFailure> failures = new ArrayList<>();

        void addRecord(NoteRecord record)
        {
            recordById.putIfAbsent(record.getId(), record);
        }

        List<NoteRecord> records()
        {
            return new ArrayList<>(recordById.values());
        }
    }

    /** 25 列候选笔记上下文（U4/R6）：候选标题列表 + 标题→笔记 id 映射（一次全量查询的产物） */
    private static final class SemanticNoteContext
    {
        /** 候选名列表（{@link LinkNameResolver} 口径：同名重复保留，重复名在组件内判歧义） */
        final List<String> titles;

        /** 标题 → 笔记 id（回退命中后取引用来源笔记 id；同名重复后写覆盖无碍——同名必判歧义） */
        final Map<String, Long> noteIdByTitle;

        SemanticNoteContext(List<String> titles, Map<String, Long> noteIdByTitle)
        {
            this.titles = titles;
            this.noteIdByTitle = noteIdByTitle;
        }
    }

    /** 25 列单元格解析产物（U4）：`_ID` 命中的待恢复旧行 + 名称回退命中的新建行预案 + 失败条目 */
    private static final class SemanticCellResolution
    {
        /** `_ID` 命中的旧行（含行 id 与 noteId/blockId/contextText/itemValue 核心字段，事务段恢复用） */
        final List<NoteNotelink> restored = new ArrayList<>();

        /** 名称回退命中的新建行预案（noteId=引用来源笔记 id、anchor=锚词 → contextText） */
        final List<SemanticFallback> fallbacks = new ArrayList<>();

        final List<LinkEntryFailure> failures = new ArrayList<>();
    }

    /** 25 列名称回退的单条目新建预案（U4/R6） */
    private static final class SemanticFallback
    {
        /** 引用来源笔记 id（NoteNotelink.noteId） */
        final Long noteId;

        /** 锚词（null=条目无锚词语法；写入 contextText/itemValue，blockId 降级为 null） */
        final String anchor;

        SemanticFallback(Long noteId, String anchor)
        {
            this.noteId = noteId;
            this.anchor = anchor;
        }
    }

    /** 21 列单条目解析失败载体（R8 清单元素与阶段二拒绝消息的原始材料） */
    private static final class LinkEntryFailure
    {
        /** 失败条目文本（CSV 转义还原并 trim 后） */
        final String entryText;

        /** 失败原因（中文，ID 回退来源带前缀） */
        final String reason;

        /** 容错命中说明（resolver 各级尝试的命中数与来源） */
        final String explanation;

        LinkEntryFailure(String entryText, String reason, String explanation)
        {
            this.entryText = entryText;
            this.reason = reason;
            this.explanation = explanation;
        }
    }

    /** 补参 relationSelections 的解析产物（键=列名 trim） */
    private static final class RelationResolution
    {
        /** 列名 → 名 → 用户选择（含显式 recordId=null） */
        final Map<String, Map<String, ExcelImportParams.RelationSelection>> selectionsByName = new LinkedHashMap<>();

        /** 列名 → 名 → 选中记录（仅 recordId 非空且查库存在） */
        final Map<String, Map<String, NoteRecord>> selectedRecordsByName = new LinkedHashMap<>();
    }

    /**
     * 逗号串拆为去重保序集合（linkRecordId 追加语义：contains 去重）。
     */
    private static Set<String> splitCsvToSet(String value)
    {
        Set<String> result = new LinkedHashSet<>();
        if (value != null && !value.isEmpty())
        {
            for (String part : value.split(","))
            {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * 逗号串拆为有序列表（value/linkItemId 保序追加语义；空串 → 空列表）。
     */
    private static List<String> splitCsvToList(String value)
    {
        List<String> result = new ArrayList<>();
        if (value != null && !value.isEmpty())
        {
            result.addAll(Arrays.asList(value.split(",")));
        }
        return result;
    }
}
