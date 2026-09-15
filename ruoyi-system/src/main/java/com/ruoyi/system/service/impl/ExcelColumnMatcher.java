package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.ParsedSheet;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.RowData;

/**
 * Excel 导入的 sheet 选定与表头→目标列映射工具（U3，R2/R5/R6/R11）。
 * <p>
 * 三个职责（全部静态、无状态，预检与导入两阶段共用）：
 * <ul>
 *   <li>{@link #selectSheet(List, String)}——sheet 选定（R2）：单 sheet 直用；多 sheet 先按导出端
 *       同名净化规则（{@code []:*?/\}→{@code _}、31 字符截断）匹配，无同名时识别
 *       {@code 表名_p<序号>} 分片家族按序号升序合并为逻辑表（表头取首分片、行数据按序拼接），
 *       两者皆无抛 {@link ServiceException}（含表名与已有 sheet 清单）；</li>
 *   <li>{@link #mapColumns(List, List)}——表头→目标列映射（R5/R6）：精确匹配（区分大小写）优先，
 *       round-trip 双列后缀（{@code 列名_文本}/{@code 列名_ID}）与 record_id 源列仅识别未精确命中的表头；
 *       隐藏列（isShow=1）与排除类型列（公式 20/23、集合运算 24、语义关联 25、lookup 26、系统列
 *       1001-1005）不参与映射，同名表头记录跳过原因；18/21 关联列参与导入（值来源为文本列，
 *       匹配逻辑归 U4）；</li>
 *   <li>{@link #validateCellText(NoteColumn, String)}——单元格显示文本的类型校验（R11/KTD9）：
 *       数字(2)/日期(5)/复选框(7) 三类，空缺值不算违规，违规返回中文描述供预检聚合为缺参。</li>
 * </ul>
 * 消费纪律（双列错位警戒，导出侧 ae70682d 修复的同族问题）：遍历列定义消费行数据时，
 * 一律经 {@link ColumnMapping#cellText(List, int)} / {@link ColumnMapping#cellText(RowData, int)}
 * 按 headerIndex 取数，不按列序号。
 *
 * @author ruoyi
 */
public final class ExcelColumnMatcher
{
    /** 导出产物固定首列表头：记录 id 源列（{@code NoteDwtableExportPivotServiceImpl} 合成列） */
    public static final String RECORD_ID_HEADER = "record_id";

    /** 关联/派生类列双列表头的文本后缀（导出端 {@code NoteDwtableExcelRenderer}） */
    public static final String TEXT_SUFFIX = "_文本";

    /** 关联/派生类列双列表头的源 id 后缀 */
    public static final String ID_SUFFIX = "_ID";

    /** Excel sheet 名长度上限（与导出端截断规则一致） */
    static final int SHEET_NAME_MAX_LENGTH = 31;

    /** 表名净化后达到该长度时，"_p<序号>" 分片后缀必被 31 字符截断破坏（导出端已知限制，识别失效阈值） */
    static final int SHARD_SUFFIX_BREAK_LENGTH = 29;

    /** 隐藏列标记：isShow=1 表示隐藏（baseTable/related/index.vue filter(item -> !item.isShow)） */
    public static final long IS_SHOW_HIDDEN = 1L;

    /** 单向关联 */
    public static final long TYPE_SINGLE_LINK = 18L;

    /** 双向关联 */
    public static final long TYPE_DOUBLE_LINK = 21L;

    /**
     * 不导入值的列类型（R17）：20 公式 / 23 数学公式 / 24 集合运算 / 25 语义关联 / 26 lookup。
     * 18 单向关联 / 21 双向关联参与导入（值来源为文本匹配，U4 处理）。
     */
    private static final Set<Long> EXCLUDED_TYPES = new HashSet<>(Arrays.asList(20L, 23L, 24L, 25L, 26L));

    /** 日期显示文本的两种合法格式（KTD9：严格校验，其他格式算违规） */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private ExcelColumnMatcher()
    {
    }

    // ==================== sheet 选定（R2） ====================

    /**
     * 从解析出的全部 sheet 中选定目标表的逻辑数据（R2）。
     * <p>
     * 选定策略：
     * <ol>
     *   <li>单 sheet 直用（不校验名字）；</li>
     *   <li>多 sheet 先匹配与 {@code sanitizeSheetName(表名)} 相同的 sheet（导出端同名变换规则）；
     *       命中即选定，其余 sheet 进忽略清单；</li>
     *   <li>无同名 → 识别分片家族：sheet 名匹配 {@code sanitize(表名 + "_p" + 序号)}（序号 ≥1，
     *       连续与否均收集）的分片按序号升序合并为一张逻辑表——表头取序号最小的分片，
     *       行数据按序号升序拼接；合并前后置校验：各分片表头须与首分片完全一致（含列数与顺序，
     *       不一致抛 {@link ServiceException} 含分片 sheet 名）、合并后总行数不得超
     *       {@code ExcelWorkbookReader.MAX_ROWS}（超出抛 {@link ServiceException} 提示分批导入）；
     *       期望名生成时发生 31 字符截断（序号后缀被破坏）的序号不参与识别
     *       （导出端该形态产物本身不可达，避免不同序号截断同名导致误识别）；</li>
     *   <li>两者皆无 → 抛 {@link ServiceException}（消息含目标表名与已有 sheet 名清单；
     *       表名净化后 ≥{@value #SHARD_SUFFIX_BREAK_LENGTH} 字符时提示缩短表名）。</li>
     * </ol>
     *
     * @param sheets {@link ExcelWorkbookReader#parse} 返回的全部 sheet
     * @param tableName 目标数据表名
     * @return 选定的（或分片合并的）sheet 数据 + 使用/忽略的 sheet 名清单
     * @throws ServiceException 无工作表、表名为空或两者皆无匹配（中文消息）
     */
    public static SheetSelection selectSheet(List<ParsedSheet> sheets, String tableName)
    {
        if (sheets == null || sheets.isEmpty())
        {
            throw new ServiceException("Excel 文件中没有任何工作表，无法导入");
        }
        if (tableName == null || tableName.trim().isEmpty())
        {
            throw new ServiceException("目标数据表名为空，无法匹配 Excel 工作表");
        }
        String trimmedTableName = tableName.trim();

        // R2：单 sheet 直用（不校验名字）
        if (sheets.size() == 1)
        {
            ParsedSheet only = sheets.get(0);
            return new SheetSelection(only.getHeaders(), only.getRows(),
                    Collections.singletonList(only.getSheetName()), Collections.<String>emptyList());
        }

        // R2：多 sheet 先按净化名匹配（与导出端 sanitizeSheetName 同一变换）
        String sanitizedTableName = sanitizeSheetName(trimmedTableName);
        ParsedSheet matched = null;
        for (ParsedSheet sheet : sheets)
        {
            if (sanitizedTableName.equals(sheet.getSheetName()))
            {
                matched = sheet;
                break;
            }
        }
        if (matched != null)
        {
            List<String> ignored = new ArrayList<>();
            for (ParsedSheet sheet : sheets)
            {
                if (sheet != matched)
                {
                    ignored.add(sheet.getSheetName());
                }
            }
            return new SheetSelection(matched.getHeaders(), matched.getRows(),
                    Collections.singletonList(matched.getSheetName()), ignored);
        }

        // R2：无同名 → 识别“表名_p<序号>”分片家族（反向解析：sheet 名 = 分片前缀 + 纯数字序号）
        // 前缀 = replaceInvalidChars(表名 + "_p")：逐字符 1:1 替换使
        // replace(表名 + "_p" + 序号) == replace(表名 + "_p") + 序号，与导出端无截断时的
        // 分片名恒等；前缀达 31 字符时序号后缀必被截断破坏（导出端该形态产物不可达），
        // 不识别任何分片——反向解析下序号由 sheet 名唯一确定，无截断同名歧义
        String shardPrefix = replaceInvalidChars(trimmedTableName + "_p");
        TreeMap<Integer, ParsedSheet> shards = new TreeMap<>();
        if (shardPrefix.length() < SHEET_NAME_MAX_LENGTH)
        {
            for (ParsedSheet sheet : sheets)
            {
                String name = sheet.getSheetName();
                if (name != null && name.startsWith(shardPrefix) && name.length() > shardPrefix.length())
                {
                    String digits = name.substring(shardPrefix.length());
                    if (digits.matches("[0-9]{1,9}"))
                    {
                        Integer index = Integer.valueOf(digits);
                        if (!shards.containsKey(index))
                        {
                            shards.put(index, sheet);
                        }
                    }
                }
            }
        }
        if (!shards.isEmpty())
        {
            // 按序号升序合并：表头取首分片，行数据按序拼接（序号不连续时按存在的序号升序）
            List<String> headers = null;
            String firstShardName = null;
            List<RowData> mergedRows = new ArrayList<>();
            List<String> used = new ArrayList<>(shards.size());
            for (ParsedSheet shard : shards.values())
            {
                if (headers == null)
                {
                    headers = shard.getHeaders();
                    firstShardName = shard.getSheetName();
                }
                else if (!headers.equals(shard.getHeaders()))
                {
                    // 各分片表头须与首分片完全一致（含列数与顺序），否则行数据按 headerIndex 取数会错位
                    throw new ServiceException("分片工作表[" + shard.getSheetName() + "]的表头与首分片["
                            + firstShardName + "]不一致（列数或列序不同），无法合并导入，请检查文件是否被修改");
                }
                mergedRows.addAll(shard.getRows());
                used.add(shard.getSheetName());
            }
            // 分片合并总行数上限：per-sheet 50000 检查不覆盖合并后的乘积放大
            if (mergedRows.size() > ExcelWorkbookReader.MAX_ROWS)
            {
                throw new ServiceException("分片工作表合并后共 " + mergedRows.size() + " 行数据，超过单表行数上限 "
                        + ExcelWorkbookReader.MAX_ROWS + " 行，请分批导入");
            }
            Set<ParsedSheet> usedSheets = Collections.newSetFromMap(new IdentityHashMap<ParsedSheet, Boolean>());
            usedSheets.addAll(shards.values());
            List<String> ignored = new ArrayList<>();
            for (ParsedSheet sheet : sheets)
            {
                if (!usedSheets.contains(sheet))
                {
                    ignored.add(sheet.getSheetName());
                }
            }
            return new SheetSelection(headers, mergedRows, used, ignored);
        }

        // R2：两者皆无 → 报错（含表名与 sheet 清单；长表名提示缩短）
        StringBuilder message = new StringBuilder("未在 Excel 文件中找到与数据表“")
                .append(trimmedTableName).append("”匹配的工作表，文件中的工作表：");
        for (int i = 0; i < sheets.size(); i++)
        {
            if (i > 0)
            {
                message.append("、");
            }
            message.append(sheets.get(i).getSheetName());
        }
        if (sanitizedTableName.length() >= SHARD_SUFFIX_BREAK_LENGTH)
        {
            message.append("；数据表名过长（净化后 ").append(sanitizedTableName.length())
                    .append(" 字符）会破坏分片工作表的序号后缀识别，请缩短表名后重新导出");
        }
        throw new ServiceException(message.toString());
    }

    // ==================== 列映射（R5/R6） ====================

    /**
     * 把选定 sheet 的表头映射到目标表列（R5/R6）。
     * <p>
     * 匹配优先级（KTD/R5）：
     * <ol>
     *   <li>精确匹配（表头 == NoteColumn.name，区分大小写，前后空白宽容）——真实列名优先消费表头，
     *       包括名为 {@code record_id} 或恰与后缀约定形式相同的真实用户列（非首列 record_id 表头
     *       映射到真实列是合法的显式意图）；</li>
     *   <li>未命中的表头才做 round-trip 识别：首列且恰为 {@code record_id} 的表头是导出固定的
     *       记录 id 源列 → 忽略；{@code 列名_文本} 剥离后缀命中 type 18/21 关联列 → 该表头为其
     *       文本值来源；{@code 列名_ID} 剥离后缀命中 18/21 列 → 忽略（源 id 不映射）；</li>
     *   <li>隐藏列（isShow=1）与排除类型列（公式/集合运算/语义关联/lookup/系统列）不参与映射，
     *       其同名表头（含后缀形式）记录明确跳过原因。</li>
     * </ol>
     * 冲突检测（R5）：首列表头恰为 {@code record_id} 且目标表存在同名的真实参与导入列时，
     * 同名列歧义（源 id 应忽略 vs 真实列应导入）→ 抛 {@link ServiceException}，不静默丢弃。
     *
     * @param headers 选定 sheet 的表头（首行显示文本）
     * @param targetColumns 目标表全部列定义（含隐藏/排除类型列，由本方法分类）
     * @return 每个表头的映射条目（按下标对应，含跳过原因）
     * @throws ServiceException record_id 表头冲突（消息含列名）
     */
    public static ColumnMapping mapColumns(List<String> headers, List<NoteColumn> targetColumns)
    {
        List<String> safeHeaders = headers == null ? Collections.<String>emptyList() : headers;

        // 目标列分类索引（键与表头一致做 trim，区分大小写；同名列取遍历序首个）
        Map<String, NoteColumn> importableByName = new HashMap<>();
        Map<String, NoteColumn> hiddenByName = new HashMap<>();
        Map<String, NoteColumn> excludedByName = new HashMap<>();
        if (targetColumns != null)
        {
            for (NoteColumn column : targetColumns)
            {
                if (column == null || column.getName() == null || column.getName().trim().isEmpty())
                {
                    continue;
                }
                String key = column.getName().trim();
                if (isHidden(column))
                {
                    hiddenByName.putIfAbsent(key, column);
                }
                else if (!isImportable(column))
                {
                    excludedByName.putIfAbsent(key, column);
                }
                else
                {
                    importableByName.putIfAbsent(key, column);
                }
            }
        }

        List<HeaderEntry> entries = new ArrayList<>(safeHeaders.size());
        for (int i = 0; i < safeHeaders.size(); i++)
        {
            entries.add(null);
        }
        Set<NoteColumn> mappedColumns = Collections.newSetFromMap(new IdentityHashMap<NoteColumn, Boolean>());

        // 第一遍：精确匹配（区分大小写）——真实列名优先消费表头
        for (int i = 0; i < safeHeaders.size(); i++)
        {
            String header = normalize(safeHeaders.get(i));
            if (header.isEmpty())
            {
                entries.set(i, new HeaderEntry(i, header, null, "空表头"));
                continue;
            }
            NoteColumn exact = importableByName.get(header);
            if (exact == null)
            {
                continue;
            }
            if (i == 0 && RECORD_ID_HEADER.equals(header))
            {
                // R5 冲突：首列表头恰为 record_id 是导出固定的记录 id 源列（应忽略），
                // 但精确命中目标表的真实参与导入列——同名列歧义，不静默丢弃
                throw new ServiceException("表头冲突：Excel 首列表头“record_id”既是导出产物的记录 id 源列"
                        + "（应忽略不导入），又与目标数据表中的真实列“record_id”同名，无法判断该列数据的导入意图；"
                        + "请重命名目标数据表中的该列，或调整 Excel 表头后重试");
            }
            if (mappedColumns.contains(exact))
            {
                entries.set(i, new HeaderEntry(i, header, null, "列已被同名的其他表头映射，重复表头跳过"));
                continue;
            }
            entries.set(i, new HeaderEntry(i, header, exact, null));
            mappedColumns.add(exact);
        }

        // 第二遍：未命中表头——record_id 源列 / 隐藏列 / 排除类型 / round-trip 双列后缀
        for (int i = 0; i < safeHeaders.size(); i++)
        {
            if (entries.get(i) != null)
            {
                continue;
            }
            String header = normalize(safeHeaders.get(i));

            // 记录 id 源列：每表首列且表头恰为 record_id（导出端固定首列）
            if (i == 0 && RECORD_ID_HEADER.equals(header))
            {
                entries.set(i, new HeaderEntry(i, header, null, "记录 id 源列（导出固定首列），不导入值"));
                continue;
            }

            // 隐藏列同名（R6：不参与映射）
            NoteColumn hidden = hiddenByName.get(header);
            if (hidden != null)
            {
                entries.set(i, new HeaderEntry(i, header, null, "隐藏列不导入"));
                continue;
            }

            // 排除类型/系统列同名
            NoteColumn excluded = excludedByName.get(header);
            if (excluded != null)
            {
                entries.set(i, new HeaderEntry(i, header, null, excludedReason(excluded)));
                continue;
            }

            // round-trip 双列后缀（R5：仅识别未精确命中的表头）
            if (header.endsWith(TEXT_SUFFIX) && header.length() > TEXT_SUFFIX.length())
            {
                String bare = header.substring(0, header.length() - TEXT_SUFFIX.length());
                NoteColumn link = importableByName.get(bare);
                if (link != null && isLinkType(link))
                {
                    if (mappedColumns.contains(link))
                    {
                        entries.set(i, new HeaderEntry(i, header, null, "列“" + bare + "”已被其他表头映射"));
                    }
                    else
                    {
                        entries.set(i, new HeaderEntry(i, header, link, null));
                        mappedColumns.add(link);
                    }
                    continue;
                }
                String suffixReason = suffixTargetReason(bare, excludedByName, hiddenByName);
                if (suffixReason != null)
                {
                    entries.set(i, new HeaderEntry(i, header, null, suffixReason));
                    continue;
                }
                // 剥离后缀未命中 18/21 关联列 → 落入未匹配
            }
            else if (header.endsWith(ID_SUFFIX) && header.length() > ID_SUFFIX.length())
            {
                String bare = header.substring(0, header.length() - ID_SUFFIX.length());
                NoteColumn link = importableByName.get(bare);
                if (link != null && isLinkType(link))
                {
                    entries.set(i, new HeaderEntry(i, header, null,
                            "关联列源 id 列（" + bare + "_ID），不导入值"));
                    continue;
                }
                String suffixReason = suffixTargetReason(bare, excludedByName, hiddenByName);
                if (suffixReason != null)
                {
                    entries.set(i, new HeaderEntry(i, header, null, suffixReason));
                    continue;
                }
            }

            // 未匹配
            entries.set(i, new HeaderEntry(i, header, null, "目标数据表中不存在同名参与导入的列"));
        }
        return new ColumnMapping(entries);
    }

    // ==================== 单元格显示值类型校验（R11/KTD9） ====================

    /**
     * 校验单元格显示文本与列类型匹配（R11/KTD9）。
     * <p>
     * 校验规则（与导出显示值、{@code ColumnDefaultValueSupport} 的默认值校验口径一致）：
     * <ul>
     *   <li>数字(2)：trim 后可 {@link BigDecimal} 解析（空串/null 视为缺值不算违规）；</li>
     *   <li>日期(5)：严格 {@code yyyy-MM-dd HH:mm:ss} 或 {@code yyyy-MM-dd} 两种格式，其他算违规；</li>
     *   <li>复选框(7)：{@code "true"} / {@code "false"} 或 {@code "0"} / {@code "1"}
     *       （后者为导出实际写出的存储值形态，round-trip 兼容）；</li>
     *   <li>其余类型不校验（单选/多选选项匹配归 U4 预检）。</li>
     * </ul>
     *
     * @param column 目标列
     * @param text 单元格显示文本（{@code DataFormatter} 输出）
     * @return null=合法或空缺值；非 null=违规中文描述（供预检聚合为缺参，可含单元格位置）
     */
    public static String validateCellText(NoteColumn column, String text)
    {
        if (column == null || text == null || text.trim().isEmpty())
        {
            // 空缺值不算类型违规（缺值走 U4 的缺参链路：默认值 > 弹框补值）
            return null;
        }
        Long type = column.getType();
        if (type == null)
        {
            return null;
        }
        String value = text.trim();
        switch (type.intValue())
        {
            case 2:
                try
                {
                    new BigDecimal(value);
                    return null;
                }
                catch (NumberFormatException e)
                {
                    return "数字格式非法";
                }
            case 5:
                if (isStrictDate(value))
                {
                    return null;
                }
                return "日期格式须为 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd";
            case 7:
                // round-trip：同时接受 true/false 与导出实际写出的 '0'/'1' 存储值形态
                if ("true".equals(value) || "false".equals(value) || "0".equals(value) || "1".equals(value))
                {
                    return null;
                }
                return "复选框值须为 true/false 或 0/1";
            default:
                return null;
        }
    }

    // ==================== 私有辅助 ====================

    /**
     * 净化 sheet 名（与导出端 {@code NoteDwtableExcelRenderer#sanitizeSheetName} 同一变换规则，
     * 导入侧逆向复用）：{@code []:*?/\} 替换为 {@code _}，截断到 31 字符。
     */
    private static String sanitizeSheetName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "Sheet";
        }
        String sanitized = replaceInvalidChars(name);
        if (sanitized.length() > SHEET_NAME_MAX_LENGTH)
        {
            sanitized = sanitized.substring(0, SHEET_NAME_MAX_LENGTH);
        }
        return sanitized.isEmpty() ? "Sheet" : sanitized;
    }

    /**
     * 仅做非法字符替换（长度不变），供分片期望名的截断判定。
     */
    private static String replaceInvalidChars(String name)
    {
        return name.replaceAll("[\\[\\]:*?/\\\\]", "_");
    }

    /**
     * 表头规整：null → 空串，去前后空白（与 SQL 导入 buildContext 的 trim 键口径一致）。
     */
    private static String normalize(String header)
    {
        return header == null ? "" : header.trim();
    }

    /**
     * 隐藏列判定：isShow=1 表示隐藏（导入链路统一判定，供 {@code NoteDwtableExcelImportServiceImpl} 复用）。
     */
    public static boolean isHidden(NoteColumn column)
    {
        return column.getIsShow() != null && column.getIsShow() == IS_SHOW_HIDDEN;
    }

    /**
     * 参与导入的列类型：非排除类型（公式/集合运算/语义关联/lookup）且非系统列（18/21 参与）。
     */
    private static boolean isImportable(NoteColumn column)
    {
        Long type = column.getType();
        return type != null && !EXCLUDED_TYPES.contains(type) && !isSystemType(type);
    }

    /**
     * 关联列类型：18 单向关联 / 21 双向关联（round-trip 双列后缀的合法剥离目标）。
     */
    private static boolean isLinkType(NoteColumn column)
    {
        Long type = column.getType();
        return type != null && (type == TYPE_SINGLE_LINK || type == TYPE_DOUBLE_LINK);
    }

    /**
     * 系统列类型：1001-1005（R18：按记录元数据生成，不导 item）。
     */
    private static boolean isSystemType(Long type)
    {
        return type != null && type >= 1001L && type <= 1005L;
    }

    /**
     * 排除类型/系统列的跳过原因（供预检提示）。
     */
    private static String excludedReason(NoteColumn column)
    {
        Long type = column.getType();
        if (type == null)
        {
            return "该列缺少类型定义，不参与导入";
        }
        if (isSystemType(type))
        {
            return "系统列（值按记录元数据自动生成），不导入";
        }
        long t = type;
        if (t == 20L || t == 23L)
        {
            return "公式列的值由系统运行时计算，不导入";
        }
        if (t == 24L)
        {
            return "集合运算列的值由系统重算，不导入";
        }
        if (t == 25L)
        {
            return "语义关联列不参与导入";
        }
        if (t == 26L)
        {
            return "查找引用列的值由系统重算，不导入";
        }
        return "该列类型不参与导入";
    }

    /**
     * round-trip 后缀剥离后目标列的跳过原因（排除/隐藏列的后缀表头给明确原因，避免笼统“未匹配”）。
     *
     * @return null=剥离目标既非排除列也非隐藏列（由调用方落入未匹配）
     */
    private static String suffixTargetReason(String bare, Map<String, NoteColumn> excludedByName,
            Map<String, NoteColumn> hiddenByName)
    {
        NoteColumn excluded = excludedByName.get(bare);
        if (excluded != null)
        {
            return excludedReason(excluded);
        }
        NoteColumn hidden = hiddenByName.get(bare);
        if (hidden != null)
        {
            return "隐藏列不导入";
        }
        return null;
    }

    /**
     * 严格日期格式判定：yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd（STRICT 解析，2026/1/1 等变体违规）。
     * 纯日期格式用 {@link LocalDate} 解析——{@code LocalDateTime.parse} 对缺时间字段的模式必抛异常。
     */
    private static boolean isStrictDate(String value)
    {
        try
        {
            LocalDateTime.parse(value, DATE_TIME_FORMATTER);
            return true;
        }
        catch (Exception ignored)
        {
            // 尝试纯日期格式
        }
        try
        {
            LocalDate.parse(value, DATE_FORMATTER);
            return true;
        }
        catch (Exception ignored)
        {
            // 两种格式均不匹配
        }
        return false;
    }

    // ==================== 结果类型 ====================

    /**
     * sheet 选定结果：选定的（或分片合并的）逻辑表数据 + 使用/忽略的 sheet 名清单（供预检提示）。
     */
    public static final class SheetSelection
    {
        /** 逻辑表表头（分片合并时取序号最小的分片） */
        private final List<String> headers;

        /** 逻辑表数据行（分片合并时按序号升序拼接，各行保留所属 sheet 自身物理行号） */
        private final List<RowData> rows;

        /** 实际使用的 sheet 名（分片合并时为多个，按合并顺序） */
        private final List<String> selectedSheetNames;

        /** 未使用的 sheet 名（按工作簿内顺序，供预检忽略提示） */
        private final List<String> ignoredSheetNames;

        private SheetSelection(List<String> headers, List<RowData> rows,
                List<String> selectedSheetNames, List<String> ignoredSheetNames)
        {
            this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.selectedSheetNames = Collections.unmodifiableList(new ArrayList<>(selectedSheetNames));
            this.ignoredSheetNames = Collections.unmodifiableList(new ArrayList<>(ignoredSheetNames));
        }

        public List<String> getHeaders()
        {
            return headers;
        }

        public List<RowData> getRows()
        {
            return rows;
        }

        public List<String> getSelectedSheetNames()
        {
            return selectedSheetNames;
        }

        public List<String> getIgnoredSheetNames()
        {
            return ignoredSheetNames;
        }
    }

    /**
     * 列映射结果：每个表头的映射条目（与输入表头等长同序）。
     * <p>
     * 消费纪律（双列错位警戒）：取行数据一律用 {@link #cellText(List, int)} 按 headerIndex 索引，
     * 不按目标列序号——首个双列表头之后 headerIndex 与列序号即错位。
     */
    public static final class ColumnMapping
    {
        private final List<HeaderEntry> entries;

        private ColumnMapping(List<HeaderEntry> entries)
        {
            this.entries = Collections.unmodifiableList(entries);
        }

        /** 全部表头条目（含已映射与跳过，与输入表头等长同序） */
        public List<HeaderEntry> getEntries()
        {
            return entries;
        }

        /** headerIndex 对应的目标列；未映射/越界返回 null */
        public NoteColumn mappedColumn(int headerIndex)
        {
            if (headerIndex < 0 || headerIndex >= entries.size())
            {
                return null;
            }
            return entries.get(headerIndex).getColumn();
        }

        /** 已映射的表头条目 */
        public List<HeaderEntry> getMappedEntries()
        {
            List<HeaderEntry> mapped = new ArrayList<>();
            for (HeaderEntry entry : entries)
            {
                if (entry.getColumn() != null)
                {
                    mapped.add(entry);
                }
            }
            return mapped;
        }

        /** 未映射的表头条目（含跳过原因，供预检忽略提示） */
        public List<HeaderEntry> getSkippedEntries()
        {
            List<HeaderEntry> skipped = new ArrayList<>();
            for (HeaderEntry entry : entries)
            {
                if (entry.getColumn() == null)
                {
                    skipped.add(entry);
                }
            }
            return skipped;
        }

        /**
         * 按 headerIndex 从数据行取显示文本（双列错位警戒：只认 headerIndex，绝不按列序号）。
         * 行短于表头数（尾列空单元格被 Excel 裁剪）时返回空串。
         */
        public String cellText(List<String> row, int headerIndex)
        {
            if (row == null || headerIndex < 0 || headerIndex >= row.size())
            {
                return "";
            }
            String value = row.get(headerIndex);
            return value == null ? "" : value;
        }

        /**
         * {@link RowData} 形态取数（委托 {@link #cellText(List, int)}，双列错位警戒同上）。
         */
        public String cellText(RowData row, int headerIndex)
        {
            return row == null ? "" : cellText(row.getCells(), headerIndex);
        }
    }

    /**
     * 单个表头的映射条目：column 非 null 即映射成功；否则 reason 为跳过原因（中文，供预检提示）。
     */
    public static final class HeaderEntry
    {
        /** 表头下标（0-based，与数据行单元格下标对齐） */
        private final int headerIndex;

        /** 表头名（规整后显示文本） */
        private final String headerName;

        /** 映射到的目标列；null=未映射 */
        private final NoteColumn column;

        /** 跳过原因；column 非 null 时为 null */
        private final String reason;

        private HeaderEntry(int headerIndex, String headerName, NoteColumn column, String reason)
        {
            this.headerIndex = headerIndex;
            this.headerName = headerName;
            this.column = column;
            this.reason = reason;
        }

        public int getHeaderIndex()
        {
            return headerIndex;
        }

        public String getHeaderName()
        {
            return headerName;
        }

        public NoteColumn getColumn()
        {
            return column;
        }

        public String getReason()
        {
            return reason;
        }
    }
}
