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
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.ClearedLinkColumn;
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
 * 被删 21 双向关联列在被关联表的配对列级联删除，其他表指向本表的关联列引用被清空。
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
 *   <li>替换影响面（{@link ReplaceImpact}）：将删除的行/列、将新建的列、跨表引用清空与
 *       取消隐藏清单——删除类字段非空/非零即前端必须弹框确认；</li>
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
 * 命中即按行 id 恢复原行；`_ID` 缺失/未命中按同位名称条目（"引用笔记名（锚词）"语法）经
 * {@link LinkNameResolver} 对全部笔记标题回退（空名称条目=悬空导出回读的死链，跳过不失败）；
 * 歧义/未命中进同一链接失败清单（多值任一失败整表零写入，R9）。执行段 (h2)（21 对称写回之后、
 * 跨表重算之前）逐列重建：先按列 {@code deleteNoteNotelinkByColumnId} 清旧行，`_ID` 命中按行 id
 * upsert 恢复原行（保留行 id 与 blockId——源笔记锚点 data-link-id 不断链）、名称回退 insert 新行
 * （blockId=null 锚点降级、contextText=锚词、linkNoteId=本表归属笔记规范值）。
 * <p>
 * 预检不触碰任何写路径（零 insert/update mapper 调用）；
 * 日志只记录列名/行号/跳过原因等元数据，不含单元格值（R25）。
 * <p>
 * 导入流程（U5，R16-R26，KTD3/KTD5/KTD8）：非事务前置段（指纹校验 → 同一私有方法链
 * 重新解析/映射/匹配 → params 解析 → 补参校验 R26 → 反向漂移 fail-fast）→
 * {@code @Transactional} 写入段（替换阶段：全删本表行与 items → 清空跨表关联引用 →
 * 级联删被删 21 列的配对列 → 删旧列+清孤儿 notelink → 建新列 → 取消隐藏；
 * 写入阶段：逐行 NoteRecord + items 批量 ≥500 flush + 选项追加 + 全量最终 flush +
 * 双链对称写入 + 重算编排——重算无条件执行，仅表头无数据行的清空场景也要跑）→
 * 返回 recordCount；失败整体回滚（R22）。
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
     *       零写入返回结构化失败清单（与界面共用 DTO），修正文件后重导；</li>
     *   <li>歧义（同名多条，18 列）：按 sort 靠前预选建立关联（与界面预检默认一致），不阻断，
     *       成功结果中报告歧义处理清单；</li>
     *   <li>列默认值/新选项/对称写入等静默变更与<b>替换影响</b>（删除行/删除列/新建列/
     *       跨表引用清空）在成功结果中报告（与界面预检清单对齐）。</li>
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
                    + "被删双向关联列的配对列一并删除），返回成功/失败双态结果。"
                    + "文件须先经 /common/upload 上传，filePath 传其返回的 fileName（/profile/upload/... 路径）。"
                    + "有基础列缺参（缺值/类型违规）时仅当 columnValues 全部覆盖才执行导入，否则零写入并返回缺参清单"
                    + "（将清单告知用户，用户补充补值后带 columnValues 再次调用）。"
                    + "关联列未命中无法文本补值，须改走界面导入。"
                    + "双向关联(21)/语义关联(25)列链接解析失败（_ID 未命中且名称歧义/未命中）同样零写入，"
                    + "返回结构化失败清单（单元格定位/原因），修正 Excel 文件中的链接名称或 _ID 后重新导入。")
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
        // 清单与界面预检共用 DTO（单元格定位/原因/容错命中说明），修正文件后重导
        if (!precheck.getLinkFailures().isEmpty())
        {
            ExcelImportAgentResult result = new ExcelImportAgentResult();
            result.setSuccess(false);
            result.setLinkFailures(precheck.getLinkFailures());
            result.setMessage("导入未执行（零写入）：有 " + precheck.getLinkFailures().size()
                    + " 项链接解析失败（双向关联的目标记录/语义链接的引用笔记无法唯一确定），"
                    + "请按清单修正 Excel 文件中的链接名称或 _ID 后重新导入");
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
     * agent 成功消息的替换摘要（为 0/空的分句省略）。
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
        analyzeColumns(result, noteId, dwtableId, userId, selection.getRows(), columns, mapping);
        buildReplaceImpact(result, noteId, dwtableId, columns, mapping);
        // U3（R8）：链接失败清单与缺参清单并列阻断（21 列强失败，确认前零写入）
        result.setHasBlockingIssues(!result.getMissingParams().isEmpty()
                || !result.getAmbiguityItems().isEmpty()
                || !result.getLinkFailures().isEmpty());

        log.info("[EXCEL-PRECHECK] 预检完成 noteId={}, dwtableId={}, userId={}, totalRows={}, missingParams={}, "
                        + "ambiguityItems={}, linkFailures={}, replaceImpact{{}行/{}删列/{}新列/{}清空/{}取消隐藏}, defaultValueFills={}, "
                        + "newOptions={}, symmetricWriteImpact={}, ignoredSheets={}, skippedHeaders={}",
                noteId, dwtableId, userId, selection.getRows().size(),
                result.getMissingParams().size(), result.getAmbiguityItems().size(),
                result.getLinkFailures().size(),
                result.getReplaceImpact().getDeletedRowCount(), result.getReplaceImpact().getDeletedColumns().size(),
                result.getReplaceImpact().getNewColumnNames().size(),
                result.getReplaceImpact().getClearedLinkColumns().size(),
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
     */
    private void analyzeColumns(ExcelImportPrecheckResult result, Long noteId, Long dwtableId, Long userId,
            List<RowData> rows, List<NoteColumn> columns, ColumnMapping mapping)
    {
        // 已映射列实例 → headerIndex（matcher 保证一个列至多被一个表头映射）
        Map<NoteColumn, Integer> headerIndexByColumn = new IdentityHashMap<>();
        for (HeaderEntry entry : mapping.getMappedEntries())
        {
            headerIndexByColumn.put(entry.getColumn(), Integer.valueOf(entry.getHeaderIndex()));
        }

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
                            mapping, rows, contextByTableId);
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
                    semanticNoteContext = semanticNoteContext();
                }
                analyzeSemanticLinkColumn(result, column, headerIndex.intValue(), mapping, rows,
                        semanticNoteContext);
            }
            else
            {
                analyzeBasicColumn(result, column, type, headerIndex.intValue(), mapping, rows);
            }
        }
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
        // 命中的 distinct 记录 id（含歧义预选与整串/拆分命中的并集，R11 影响面）
        Set<Long> matchedRecordIds = new LinkedHashSet<>();

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
                    collectMatch(name, matched, matchedRecordIds, ambiguityByName);
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
        // 对称写入影响：仅 21 双向关联列（16/R11）
        if (type == ExcelColumnMatcher.TYPE_DOUBLE_LINK && !matchedRecordIds.isEmpty())
        {
            SymmetricWriteImpact impact = new SymmetricWriteImpact();
            impact.setTableName(context.getTable().getName());
            impact.setAffectedRecordCount(matchedRecordIds.size());
            result.getSymmetricWriteImpact().add(impact);
        }
        // 选择器候选（U6 内嵌数据源）：按 sort 升序前 CANDIDATE_LIMIT 条
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
     * 收集一次命中：预选记录（sort 靠前首条）计入影响面；同名多条登记歧义（R14）。
     */
    private static void collectMatch(String name, List<NoteRecord> matched, Set<Long> matchedRecordIds,
            Map<String, List<NoteRecord>> ambiguityByName)
    {
        matchedRecordIds.add(matched.get(0).getId());
        if (matched.size() > 1)
        {
            ambiguityByName.putIfAbsent(name, matched);
        }
    }

    // ==================== 21 列双轨解析（U3：ID 优先、名称回退） ====================

    /**
     * 双向关联列（21）逐行预检分析（U3，R5/R7-R9）：单元格先按 {@code _ID} 逐 id 校验
     * 目标记录存在性——全命中即通过（名称不参与，ID 为准）；{@code _ID} 缺失/未命中位置
     * 按同位名称条目经 {@link LinkNameResolver} 回退，歧义/未命中进<b>链接失败清单</b>
     * （R8 新分组，与缺参清单并列阻断，确认前零写入；多值任一条目失败整表零写入，R9）。
     * 对称写入影响与选择器候选的发射口径与 18 列现状一致（候选仅供前端兼容，21 列已无补选入口）。
     */
    private void analyzeDoubleLinkColumn(ExcelImportPrecheckResult result, Long noteId, Long userId,
            NoteColumn column, int headerIndex, ColumnMapping mapping, List<RowData> rows,
            Map<Long, LinkTableContext> contextByTableId)
    {
        LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
        int idHeaderIndex = mapping.linkIdHeaderIndex(column);
        List<String> candidateNames = candidateNameList(context);

        // 命中的 distinct 记录 id（R11 影响面；ID 命中与名称回退命中并集）
        Set<Long> matchedRecordIds = new LinkedHashSet<>();
        for (RowData row : rows)
        {
            String idText = idHeaderIndex >= 0 ? mapping.cellText(row, idHeaderIndex) : "";
            String nameText = mapping.cellText(row, headerIndex);
            DoubleLinkCellResolution resolution = resolveDoubleLinkCell(idText, nameText, context, candidateNames);
            for (NoteRecord record : resolution.records())
            {
                matchedRecordIds.add(record.getId());
            }
            for (LinkEntryFailure failure : resolution.failures)
            {
                LinkFailure item = new LinkFailure();
                item.setColumnId(column.getId());
                item.setColumnName(dtoColumnName(column));
                item.setRowNumber(row.getRowNumber());
                item.setEntryText(failure.entryText);
                item.setReason(failure.reason);
                item.setExplanation(failure.explanation);
                result.getLinkFailures().add(item);
            }
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
        List<Long> ids = new ArrayList<>();
        if (idText != null && !idText.trim().isEmpty())
        {
            for (String part : idText.split(","))
            {
                String token = part.trim();
                if (token.isEmpty())
                {
                    continue;
                }
                Long id;
                try
                {
                    id = Long.valueOf(token);
                }
                catch (NumberFormatException e)
                {
                    id = null;
                }
                ids.add(id);
            }
        }

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
                // 单条目解析（条目本身含逗号时 resolver 的整串优先恰为整条目匹配）
                for (LinkNameResolver.EntryResult entryResult : linkNameResolver.resolve(entry, candidateNames))
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
     * NoteNotelink 行存在性——命中即按行 id 恢复该行（名称不参与，ID 为准）；{@code _ID}
     * 缺失/未命中位置按同位名称条目经 {@link LinkNameResolver} 对<b>全部笔记标题</b>回退，
     * 歧义/未命中进<b>链接失败清单</b>（与 21 列同组、同缺参清单并列阻断，确认前零写入；
     * 多值任一条目失败整表零写入，R9）。空名称条目 = U1 悬空导出回读的死链，跳过不失败。
     */
    private void analyzeSemanticLinkColumn(ExcelImportPrecheckResult result, NoteColumn column,
            int headerIndex, ColumnMapping mapping, List<RowData> rows, SemanticNoteContext noteContext)
    {
        int idHeaderIndex = mapping.linkIdHeaderIndex(column);
        // notelink 旧行查询缓存（同列共享，null 值占位防重复查库）
        Map<Long, NoteNotelink> notelinkById = new HashMap<>();

        for (RowData row : rows)
        {
            String idText = idHeaderIndex >= 0 ? mapping.cellText(row, idHeaderIndex) : "";
            String nameText = mapping.cellText(row, headerIndex);
            SemanticCellResolution resolution =
                    resolveSemanticLinkCell(idText, nameText, noteContext, notelinkById);
            for (LinkEntryFailure failure : resolution.failures)
            {
                LinkFailure item = new LinkFailure();
                item.setColumnId(column.getId());
                item.setColumnName(dtoColumnName(column));
                item.setRowNumber(row.getRowNumber());
                item.setEntryText(failure.entryText);
                item.setReason(failure.reason);
                item.setExplanation(failure.explanation);
                result.getLinkFailures().add(item);
            }
        }
    }

    /**
     * 25 列候选笔记上下文（U4/R6）：一次查库内全部笔记（delFlag=0）构建
     * 候选标题列表（同名保留——重复名在 resolver 内判歧义）与 标题→笔记 id 映射
     * （回退命中后取引用来源笔记 id；同名重复时后写覆盖无碍——同名必判歧义不会 MATCHED）。
     */
    private SemanticNoteContext semanticNoteContext()
    {
        List<NoteNote> notes = noteNoteMapper.selectAllNoteList();
        List<String> titles = new ArrayList<>(notes.size());
        Map<String, Long> noteIdByTitle = new HashMap<>();
        for (NoteNote note : notes)
        {
            if (note == null)
            {
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
     *   <li>{@code _ID} 命中（行存在）→ 按行 id 恢复原行（捕获旧行核心字段备用，
     *       名称被外部改写不参与、不产生失败——ID 为准）；</li>
     *   <li>{@code _ID} 缺失/未命中 → 同位名称条目经 {@link LinkNameResolver} 回退
     *       （候选=全部笔记标题，条目语法"笔记名（锚词）"的锚词由 resolver 剥离），
     *       命中 → 以笔记 id + 锚词新建行（blockId 降级为 null）；空名称条目 =
     *       U1 悬空导出回读的死链，按“无链接”跳过不计失败；</li>
     *   <li>任一名称条目歧义/未命中 → 失败条目（多值任一失败由调用方整表阻断，R9）。</li>
     * </ul>
     */
    private SemanticCellResolution resolveSemanticLinkCell(String idText, String nameText,
            SemanticNoteContext noteContext, Map<Long, NoteNotelink> notelinkById)
    {
        SemanticCellResolution resolution = new SemanticCellResolution();

        // `_ID` 逐行 id 解析（逗号拆分、trim、去空 token；非法 token 记 null 占位、按未命中走名称回退）
        List<Long> ids = new ArrayList<>();
        if (idText != null && !idText.trim().isEmpty())
        {
            for (String part : idText.split(","))
            {
                String token = part.trim();
                if (token.isEmpty())
                {
                    continue;
                }
                Long id;
                try
                {
                    id = Long.valueOf(token);
                }
                catch (NumberFormatException e)
                {
                    id = null;
                }
                ids.add(id);
            }
        }

        if (!ids.isEmpty())
        {
            // 含空条目的 CSV 拆分保持与 `_ID` 的位置一一对应（resolve 会跳过空条目丢失位置）
            List<String> nameEntries = LinkNameResolver.splitCsvEntries(
                    nameText == null ? "" : nameText.trim());
            for (int i = 0; i < ids.size(); i++)
            {
                Long id = ids.get(i);
                NoteNotelink existing = id == null ? null : lookupNotelink(id, notelinkById);
                if (existing != null)
                {
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
     * 单条目名称解析（`_ID` 回退路径）：条目本身含逗号时 resolver 的整串优先恰为整条目匹配。
     */
    private void resolveSemanticNameEntry(String entry, SemanticNoteContext noteContext,
            boolean idFallback, SemanticCellResolution resolution)
    {
        for (LinkNameResolver.EntryResult entryResult : linkNameResolver.resolve(entry, noteContext.titles))
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
     *   <li>clearedLinkColumns——kept 21 列的配对列 + 同 note 他表指向本表的 18 列
     *       （本表行全删后其 items 旧引用悬空，link 字段将被清空）；</li>
     *   <li>unhiddenColumnNames——映射到的隐藏列（导入时取消隐藏并导数据）。</li>
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

        // 跨表引用清空列（kept 21 配对列 + 他表指向本表的 18 列）
        for (NoteColumn cleared : collectClearedLinkColumns(noteId, dwtableId, mapping))
        {
            ClearedLinkColumn item = new ClearedLinkColumn();
            item.setColumnId(cleared.getId());
            item.setColumnName(cleared.getName() == null ? "" : cleared.getName().trim());
            NoteDwtable owner = cleared.getDwtableId() == null ? null
                    : noteDwtableMapper.selectNoteDwtableById(cleared.getDwtableId());
            item.setTableName(owner == null || owner.getName() == null ? "" : owner.getName());
            impact.getClearedLinkColumns().add(item);
        }

        result.setReplaceImpact(impact);
    }

    /**
     * 收集其他表中引用将被清空的关联列：kept（映射到的）21 列在被关联表的配对列
     * + 同 note 其他表 type=18 且 property.table_id 指向本表的列。
     * 自引用表的配对列也在收集范围（其 items 已随本表全删，清空为 no-op，报告口径统一）。
     */
    private List<NoteColumn> collectClearedLinkColumns(Long noteId, Long dwtableId, ColumnMapping mapping)
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

        // name 派生源列（替换后最终列集合）：kept 中首个 type=1 列（按列表序，含取消隐藏者），
        // 无则取首个 pending 新建文本列（id 未生成，name 从预解析值派生）
        Long nameColumnId = null;
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null || column.getId() == null)
            {
                continue;
            }
            if (referencedIds.contains(column.getId()) && column.getType() == 1L)
            {
                nameColumnId = column.getId();
                break;
            }
        }
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
                    semanticNoteContext = semanticNoteContext();
                }
                int idHeaderIndex = mapping.linkIdHeaderIndex(column);
                SemanticColumnAnalysis analysis = new SemanticColumnAnalysis();
                for (int r = 0; r < rowCount; r++)
                {
                    String idText = idHeaderIndex >= 0 ? mapping.cellText(rows.get(r), idHeaderIndex) : "";
                    String nameText = mapping.cellText(rows.get(r), headerIndex);
                    SemanticCellResolution cell =
                            resolveSemanticLinkCell(idText, nameText, semanticNoteContext, notelinkById);
                    analysis.cellsPerRow.add(cell);
                    if (!cell.failures.isEmpty())
                    {
                        LinkEntryFailure failure = cell.failures.get(0);
                        throw driftException("语义关联列“" + columnName + "”第 "
                                + rows.get(r).getRowNumber() + " 行的链接条目“"
                                + failure.entryText + "”解析失败（" + failure.reason + "）");
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
                                    + failure.entryText + "”解析失败（" + failure.reason + "）");
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
        plan.clearedLinkColumns.addAll(collectClearedLinkColumns(noteId, dwtableId, mapping));
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
        // affectedIds = items 被清空/被删的跨表列 id：clearedLinkColumns（kept 21 配对列 + 他表 18 列）
        // + deleted 21 列及其配对列；scanTableIds = 这些列所在的表 + deleted 21 的被关联表
        //（锚定列 double_link_column_id 指向对端 21 列，读取所在表配对 items——须扫对端所在表）
        Set<Long> affectedIds = new HashSet<>();
        Set<Long> scanTableIds = new LinkedHashSet<>();
        for (NoteColumn cleared : plan.clearedLinkColumns)
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
     *   <li><b>替换阶段（rows 为空也执行——只有表头的 Excel = 清表重建列）</b>：全删本表 items/records →
     *       清空跨表关联引用（kept 21 配对列 + 他表指向本表的 18 列）→ 级联删被删 21 列的配对列 →
     *       删旧列 + 清孤儿 notelink → insert 新建列（id 回填）→ 取消隐藏；</li>
     *   <li>写入阶段（仅 rows 非空）：逐行 NoteRecord insert（sort 从 1 递增、name 已派生、自增 id 回填）→
     *       kept items + pending 列 items 累积 ≥500 flush → 选项追加（FOR UPDATE）→
     *       items 全量最终 flush（零缓冲不变量）→ 双链对称写入；</li>
     *   <li>25 列语义链接重建（U4，rows 为空也执行清旧行——悬空 notelink 不残留）：每列先清旧行，
     *       `_ID` 命中按行 id upsert 恢复原行、名称回退 insert 新行；</li>
     *   <li>重算编排（<b>无条件执行</b>——清空引发的跨表重算不依赖新行）。</li>
     * </ol>
     * 任一步骤抛 {@link ServiceException} 整体回滚（含跨表清理与选项追加）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int executeImport(ImportPlan plan)
    {
        // (a) 漂移复查：fail-fast 于任何写入之前
        verifyReplacePreconditions(plan);
        verifyBackFieldsExist(plan);

        // ===== (b) 替换阶段：全删本表行与单元格（rows 为空也执行） =====
        noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(plan.dwtableId);
        noteRecordMapper.deleteNoteRecordByDwtableId(plan.dwtableId);

        // (c) 清空跨表关联引用（kept 21 配对列 + 他表指向本表的 18 列；自引用配对列已被 (b) 清，no-op 无害）
        for (NoteColumn cleared : plan.clearedLinkColumns)
        {
            if (cleared != null && cleared.getId() != null)
            {
                noteDwtableItemMapper.clearLinkFieldsByColumnId(cleared.getId());
            }
        }

        // (d) 级联删被删 21 列的配对列及其 items（guard 防误删本表存活列）
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
            executeSymmetricWrite(plan);
        }

        // (h2) 25 列语义链接重建（U4，21 对称写回之后、跨表重算之前；rows 为空也执行——
        // 只有表头的清空场景同样要清旧行，指向已删 items 的悬空 notelink 不残留）
        rebuildSemanticLinks(plan);

        // (i) 重算编排（无条件执行：清空/删除引发的跨表重算不依赖新行）
        executeRecompute(plan);

        log.info("[EXCEL-IMPORT] 替换式导入完成 noteId={}, dwtableId={}, userId={}, recordCount={}, "
                        + "deletedColumns={}, createdColumns={}, unhiddenColumns={}, clearedLinkColumns={}, "
                        + "newOptionColumns={}, symmetricTargets={}",
                plan.noteId, plan.dwtableId, plan.userId, recordCount,
                plan.deletedColumns.size(), plan.pendingColumns.size(), plan.unhideColumns.size(),
                plan.clearedLinkColumns.size(), plan.newOptionsByColumnId.size(), plan.symmetricTargets.size());
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
     * 25 列（语义关联）重建（U4，R2/R6；事务段 (h2)，21 对称写回之后、跨表重算之前）。
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

        /** 其他表中引用将被清空的关联列（kept 21 配对列 + 他表指向本表的 18 列） */
        final List<NoteColumn> clearedLinkColumns = new ArrayList<>();

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
