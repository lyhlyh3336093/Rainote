package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 链接列名称解析组件（U2，R7）——21 列（双向链接）/ 25 列（语义链接）/ 入链重指向
 * 三处共用的"单元格文本 → 候选名"解析器，纯组件无 DB 依赖（候选名由调用方传入）。
 * <p>
 * 解析管线（按序）：
 * <ol>
 *   <li><b>整串优先</b>（承接 {@code NoteDwtableExcelImportServiceImpl#candidateNames} 既有语义）：
 *       仅当文本含英文逗号（分隔符歧义）时，整条文本先对候选名<b>整体</b>匹配——命中即返回
 *       整串单条目，不拆逗号（处理记录名含逗号的<b>未转义</b>既有数据）；整体匹配含精确与
 *       归一化两级，不做前缀。歧义（同名多条/归一化多命中）同样在整串级判出，不再拆分；
 *       无逗号的单值文本不经过整串级，直接走条目解析（避免抢占 25 列锚词剥离）；</li>
 *   <li><b>CSV 转义还原</b>：按导出侧 {@code escapeCsvValue} 的逆操作拆分——双引号包裹段
 *       整体读入（段内逗号不拆分）、去包裹、内层双引号翻倍还原（{@code ""} → {@code "}），
 *       非包裹段按英文逗号拆分；</li>
 *   <li><b>单条目解析</b>（{@link #resolveEntry}，resolve 逐条目委托——亦供 `_ID` 回退路径
 *       直取单条目，不二次拆 CSV）：trim 后空串跳过不计失败（对应"无链接"，R15 同口径）；
 *       条目含锚词语法（以全角"）"结尾且含全角"（"）时的匹配顺序——<b>整条目</b>精确/归一化
 *       强匹配<b>优先</b>于剥离锚词名匹配（名称本身含全角括号后缀的形态，如 21 列记录名
 *       "甲（乙）"——防被剥离名"甲"抢占错链），剥离名强匹配次之，其后整条目前缀容错、
 *       剥离名前缀容错兜底（弱信号最后采信）；</li>
 *   <li><b>名称匹配三级容错</b>：精确（原名全等）→ 归一化（trim / 全角半角 / 大小写不敏感）→
 *       唯一前缀（归一化形态、前缀长度 ≥2 才触发；多命中即歧义）。任一级命中 1 个即定；
 *       多命中即歧义（R7：歧义即失败，18 列"同名取 sort 最小"为 18 专属，不进本组件）。
 *       候选池在解析入口<b>一次性预归一化</b>，各级比对直接取用；歧义的容错命中说明
 *       <b>只报命中数</b>、不回显候选名原文（候选名属他表数据面，防经失败清单泄露）。</li>
 * </ol>
 * 输出为逐条目解析结果（{@link EntryResult}：命中候选 / 歧义（多命中）/ 未命中），
 * 每条附容错命中说明（各级尝试的命中数与来源），供失败清单呈现（R8）。
 *
 * @author ruoyi
 */
@Component
public class LinkNameResolver
{
    /**
     * 单元格文本逐条目解析（预检与执行两阶段共用）。候选池在入口一次性预归一化
     * （整串级与全部条目共享），各级比对直接取用。
     *
     * @param cellText       单元格文本（null / 纯空白 → 无条目，对应"无链接"）
     * @param candidateNames 候选名列表（调用方传入，可含重复——重复名视为同名多条、判歧义）
     * @return 逐条目解析结果（空串条目被跳过，不产生条目、不计失败）
     */
    public List<EntryResult> resolve(String cellText, List<String> candidateNames)
    {
        if (cellText == null || cellText.trim().isEmpty())
        {
            return Collections.emptyList();
        }
        CandidatePool pool = buildPool(candidateNames);
        String trimmed = cellText.trim();

        // 第一级：整串优先——仅当文本含英文逗号（分隔符歧义）时，整条文本先对候选名
        // 整体匹配（未转义含逗号名称的既有数据兜底）；无逗号的单值文本直接走条目级，
        // 避免抢占 25 列锚词剥离（条目"笔记名（锚词）"不应被同名的整名候选抢先命中）
        if (trimmed.contains(","))
        {
            MatchOutcome whole = matchWhole(trimmed, pool);
            if (whole.status != Status.NOT_FOUND)
            {
                return Collections.singletonList(new EntryResult(trimmed, trimmed, null, whole));
            }
        }

        // 第二级：CSV 转义还原 + 按英文逗号拆分，逐条目解析（委托单条目管线）
        List<EntryResult> results = new ArrayList<>();
        for (String rawEntry : splitCsvEntries(trimmed))
        {
            EntryResult result = resolveEntry(rawEntry, pool);
            if (result != null)
            {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 单条目解析入口（<b>不拆 CSV</b>）——`_ID` 回退路径的名称条目已是 CSV 拆分后的
     * 单条产物，条目本身含逗号时不得被二次拆散（如 21 列记录名/25 列笔记标题含逗号：
     * 拆散会把一个名称错配成多条链接或误报未命中）。管线与 {@link #resolve} 的条目级
     * 一致：trim → 空串返回 null（对应"无链接"，调用方跳过不计失败）→ 锚词剥离与
     * 四级匹配（整条目强匹配优先）。候选池在本方法入口一次性预归一化。
     *
     * @param entry          单条目文本（null / 纯空白 → null，对应"无链接"）
     * @param candidateNames 候选名列表（可含重复——重复名视为同名多条、判歧义）
     * @return 单条目解析结果；空串条目返回 null（不计失败）
     */
    public EntryResult resolveEntry(String entry, List<String> candidateNames)
    {
        return resolveEntry(entry, buildPool(candidateNames));
    }

    /**
     * 单条目解析（共享预归一化候选池）：含锚词语法的条目按"整条目强匹配 → 剥离名强匹配 →
     * 整条目前缀 → 剥离名前缀"顺序匹配（整条目强匹配优先——防剥离名抢占错链）。
     */
    private EntryResult resolveEntry(String rawEntry, CandidatePool pool)
    {
        String entry = rawEntry == null ? "" : rawEntry.trim();
        if (entry.isEmpty())
        {
            // 空串条目跳过不计失败（对应"无链接"）
            return null;
        }
        AnchorStrip strip = stripAnchor(entry);
        if (strip.anchor == null)
        {
            return new EntryResult(entry, entry, null, matchName(entry, pool, true));
        }
        // 整条目精确/归一化强匹配优先：名称本身含全角括号后缀的形态（如 21 列记录名"甲（乙）"），
        // 与剥离名"甲"并存为候选时命中整条目——剥离名强匹配不得抢占错链
        MatchOutcome wholeStrong = matchName(entry, pool, false);
        if (wholeStrong.status != Status.NOT_FOUND)
        {
            return new EntryResult(entry, entry, null, wholeStrong);
        }
        // 剥离锚词名强匹配次之（25 列"笔记名（锚词）"语法明确时的可信命中）
        MatchOutcome strippedStrong = matchName(strip.name, pool, false);
        if (strippedStrong.status != Status.NOT_FOUND)
        {
            return new EntryResult(entry, strip.name, strip.anchor, strippedStrong);
        }
        // 两级强匹配均未命中 → 整条目前缀容错（信号强于剥离名前缀容错）
        MatchOutcome wholePrefix = matchName(entry, pool, true);
        if (wholePrefix.status != Status.NOT_FOUND)
        {
            wholePrefix.explanation = "锚词剥离后未命中，整条目回退：" + wholePrefix.explanation;
            return new EntryResult(entry, entry, null, wholePrefix);
        }
        // 剥离名前缀容错兜底（弱信号，最后采信）
        MatchOutcome strippedPrefix = matchName(strip.name, pool, true);
        return new EntryResult(entry, strip.name, strip.anchor, strippedPrefix);
    }

    // ==================== 名称匹配（三级容错 + 预归一化候选池） ====================

    /**
     * 候选池（预归一化产物）：原名与归一化形态按下标对齐——resolve/resolveEntry 入口
     * 一次性构建，精确/归一化/前缀各级比对直接取用（消除每层×每次的重复归一化）。
     */
    private static final class CandidatePool
    {
        /** 候选名原样列表（含 null/空白项，与归一化形态按位对齐） */
        final List<String> names;

        /** 与 {@link #names} 按位对齐的归一化形态（null 候选占位 null，比对时跳过） */
        final List<String> normalizedNames;

        CandidatePool(List<String> names, List<String> normalizedNames)
        {
            this.names = names;
            this.normalizedNames = normalizedNames;
        }
    }

    /** 构建预归一化候选池（null 候选的归一化形态占位 null，与原 null 守卫口径一致） */
    private static CandidatePool buildPool(List<String> candidateNames)
    {
        List<String> candidates = candidateNames == null ? Collections.emptyList() : candidateNames;
        List<String> normalized = new ArrayList<>(candidates.size());
        for (String candidate : candidates)
        {
            normalized.add(candidate == null ? null : normalize(candidate));
        }
        return new CandidatePool(candidates, normalized);
    }

    /**
     * 整串优先匹配：仅含英文逗号的文本启用——整条文本对候选名整体匹配
     * （精确 → 归一化，不做前缀——拆分前的前缀匹配会把合法多值误合为单条目）。
     * 命中/歧义的级别统一标记为整串优先。
     */
    private MatchOutcome matchWhole(String wholeText, CandidatePool pool)
    {
        MatchOutcome outcome = matchName(wholeText, pool, false);
        if (outcome.status != Status.NOT_FOUND)
        {
            if (outcome.status == Status.MATCHED)
            {
                outcome.level = MatchLevel.WHOLE;
            }
            outcome.explanation = "整串优先·" + outcome.explanation;
        }
        return outcome;
    }

    /**
     * 名称匹配（R7 容错链）：精确（原名全等）→ 归一化（trim / 全角半角 / 大小写不敏感）→
     * 唯一前缀（归一化形态、长度 ≥2 才触发；多命中即歧义）。归一化/前缀级直接取候选池
     * 预归一化形态。歧义说明只报命中数、不回显候选名原文（防他表数据面经失败清单泄露）。
     *
     * @param allowPrefix 是否启用唯一前缀级（整串优先不启用，条目级启用）
     */
    private MatchOutcome matchName(String name, CandidatePool pool, boolean allowPrefix)
    {
        // 级别一：精确匹配（原名全等；候选名重复出现=同名多条 → 歧义）
        int exactHits = 0;
        for (String candidate : pool.names)
        {
            if (name.equals(candidate))
            {
                exactHits++;
            }
        }
        if (exactHits == 1)
        {
            return matched(MatchLevel.EXACT, name, "精确匹配命中");
        }
        if (exactHits > 1)
        {
            return ambiguous(Collections.singletonList(name),
                    "精确 " + exactHits + " 命中（候选名同名多条）");
        }

        // 级别二：归一化匹配（trim / 全角半角 / 大小写归一后比较；同名多条或多名归一后相同 → 歧义）
        String normalizedName = normalize(name);
        Map<String, Integer> normalizedHits = new LinkedHashMap<>();
        for (int i = 0; i < pool.names.size(); i++)
        {
            String normalized = pool.normalizedNames.get(i);
            if (normalized != null && normalizedName.equals(normalized))
            {
                normalizedHits.merge(pool.names.get(i), 1, Integer::sum);
            }
        }
        int normalizedTotal = 0;
        for (int count : normalizedHits.values())
        {
            normalizedTotal += count;
        }
        if (normalizedTotal == 1)
        {
            String matchedName = normalizedHits.keySet().iterator().next();
            return matched(MatchLevel.NORMALIZED, matchedName,
                    "归一化匹配命中（trim/全角半角/大小写归一）");
        }
        if (normalizedTotal > 1)
        {
            return ambiguous(new ArrayList<>(normalizedHits.keySet()),
                    "归一化 " + normalizedTotal + " 命中");
        }

        // 级别三：唯一前缀（≥2 字符才触发；多命中即歧义；1 字符不触发、按未命中）
        String prefixNote;
        if (allowPrefix && normalizedName.length() >= 2)
        {
            Map<String, Integer> prefixHits = new LinkedHashMap<>();
            for (int i = 0; i < pool.names.size(); i++)
            {
                String normalized = pool.normalizedNames.get(i);
                if (normalized != null && normalized.startsWith(normalizedName))
                {
                    prefixHits.merge(pool.names.get(i), 1, Integer::sum);
                }
            }
            int prefixTotal = 0;
            for (int count : prefixHits.values())
            {
                prefixTotal += count;
            }
            if (prefixTotal == 1)
            {
                return matched(MatchLevel.PREFIX, prefixHits.keySet().iterator().next(),
                        "唯一前缀容错命中（前缀长度≥2）");
            }
            if (prefixTotal > 1)
            {
                return ambiguous(new ArrayList<>(prefixHits.keySet()),
                        "唯一前缀 " + prefixTotal + " 命中");
            }
            prefixNote = "唯一前缀 0 命中";
        }
        else if (allowPrefix)
        {
            prefixNote = "唯一前缀未触发（长度<2）";
        }
        else
        {
            prefixNote = "";
        }
        return notFound("精确 0 命中、归一化 0 命中" + (prefixNote.isEmpty() ? "" : "、" + prefixNote));
    }

    /**
     * 归一化（R7）：去首尾空白（含全角空格）、全角 ASCII（U+FF01–U+FF5E）转半角、
     * 大小写不敏感。逐字符小写化避免 locale 差异；中间的全角空格转半角保留参与比较。
     */
    private static String normalize(String text)
    {
        if (text == null)
        {
            return "";
        }
        String trimmed = text.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++)
        {
            char c = trimmed.charAt(i);
            if (c == '\u3000')
            {
                // 全角空格 → 半角空格
                c = ' ';
            }
            else if (c >= '\uFF01' && c <= '\uFF5E')
            {
                // 全角 ASCII（！～）→ 对应半角
                c = (char) (c - 0xFEE0);
            }
            // 全半角归一后再统一小写化（避免全角大写转半角后残留大写）
            sb.append(Character.toLowerCase(c));
        }
        // 归一化产生的首尾空格（全角空格转来）再去除
        return sb.toString().trim();
    }

    // ==================== 条目拆分与锚词剥离 ====================

    /**
     * CSV 风格拆分（导出侧 {@code escapeCsvValue} 的逆操作；<b>保留空条目</b>）：
     * 双引号包裹段整体读入——段内英文逗号不拆分、包裹引号去除、内层双引号翻倍还原
     * （{@code ""} → {@code "}）；非包裹段按英文逗号拆分。未闭合引号按宽松规则读到结尾。
     * <p>
     * U3 消费点：导入侧 21 列 `_ID` 与名称条目按导出顺序一一配对（{@code resolve} 会跳过
     * 空条目丢失位置信息，位置配对须经本方法取得含空条目的原序清单）。
     *
     * @param text 单元格文本（null / 空串 → 空列表）
     */
    public static List<String> splitCsvEntries(String text)
    {
        if (text == null)
        {
            return Collections.emptyList();
        }
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (inQuotes)
            {
                if (c == '"')
                {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"')
                    {
                        // 内层双引号还原（"" → "）
                        current.append('"');
                        i++;
                    }
                    else
                    {
                        // 闭合引号：去包裹
                        inQuotes = false;
                    }
                }
                else
                {
                    // 引号内字符原样保留（含逗号）
                    current.append(c);
                }
            }
            else if (c == '"')
            {
                inQuotes = true;
            }
            else if (c == ',')
            {
                entries.add(current.toString());
                current.setLength(0);
            }
            else
            {
                current.append(c);
            }
        }
        entries.add(current.toString());
        return entries;
    }

    /**
     * 25 列条目语法锚词剥离（R2）：条目以全角"）"结尾且含全角"（"（不在首位、名称非空）时，
     * 首个"（"之前为名称、首个"（"至末位"）"之间为锚词（锚词可含全角括号，取末位"）"配对）；
     * 不满足则整条目为名称（anchor 置 null 表示未剥离）。
     */
    private static AnchorStrip stripAnchor(String entry)
    {
        if (entry.endsWith("）"))
        {
            int left = entry.indexOf("（");
            if (left > 0)
            {
                return new AnchorStrip(entry.substring(0, left),
                        entry.substring(left + 1, entry.length() - 1));
            }
        }
        return new AnchorStrip(entry, null);
    }

    // ==================== 内部结果与条目模型 ====================

    /** 锚词剥离结果：name=参与匹配的名称；anchor=锚词（null 表示未发生剥离） */
    private static class AnchorStrip
    {
        final String name;
        final String anchor;

        AnchorStrip(String name, String anchor)
        {
            this.name = name;
            this.anchor = anchor;
        }
    }

    /** 内部匹配结果（组装 {@link EntryResult} 前的载体） */
    private static class MatchOutcome
    {
        Status status;
        MatchLevel level;
        String matchedName;
        List<String> ambiguousNames;
        String explanation;
    }

    /** 组装命中结果 */
    private static MatchOutcome matched(MatchLevel level, String matchedName, String explanation)
    {
        MatchOutcome outcome = new MatchOutcome();
        outcome.status = Status.MATCHED;
        outcome.level = level;
        outcome.matchedName = matchedName;
        outcome.explanation = explanation;
        return outcome;
    }

    /** 组装歧义结果（候选按出现顺序去重） */
    private static MatchOutcome ambiguous(List<String> ambiguousNames, String explanation)
    {
        MatchOutcome outcome = new MatchOutcome();
        outcome.status = Status.AMBIGUOUS;
        outcome.ambiguousNames = ambiguousNames;
        outcome.explanation = explanation;
        return outcome;
    }

    /** 组装未命中结果（说明携带各级尝试命中数） */
    private static MatchOutcome notFound(String explanation)
    {
        MatchOutcome outcome = new MatchOutcome();
        outcome.status = Status.NOT_FOUND;
        outcome.explanation = explanation;
        return outcome;
    }

    /** 条目解析状态：命中 / 歧义（多命中）/ 未命中 */
    public enum Status
    {
        /** 命中唯一候选 */
        MATCHED,
        /** 多命中（同名多条 / 归一化多命中 / 前缀多命中） */
        AMBIGUOUS,
        /** 各级均未命中 */
        NOT_FOUND
    }

    /** 命中级别（容错命中说明的"来源"）：整串优先 / 精确 / 归一化 / 唯一前缀 */
    public enum MatchLevel
    {
        /** 整串优先——整条单元格文本直接命中（未转义含逗号名称的既有数据兜底） */
        WHOLE,
        /** 条目与候选名全等 */
        EXACT,
        /** 归一化（trim/全角半角/大小写）后相等 */
        NORMALIZED,
        /** 唯一前缀容错（前缀长度≥2） */
        PREFIX
    }

    /**
     * 单条目解析结果（R8 失败清单的容错命中说明载体）。
     */
    public static class EntryResult
    {
        /** 原始条目文本（CSV 转义还原并 trim 后） */
        private final String rawEntry;
        /** 参与匹配的名称（25 列锚词剥离后；回退整条目时与 rawEntry 相同） */
        private final String name;
        /** 25 列锚词（null=未剥离或整条目回退；空串=剥离了但锚词为空） */
        private final String anchor;
        /** 解析状态 */
        private final Status status;
        /** 命中级别（仅 MATCHED 有意义） */
        private final MatchLevel matchLevel;
        /** 命中的候选名原名（仅 MATCHED） */
        private final String matchedName;
        /** 歧义候选名原名列表（仅 AMBIGUOUS，按候选出现顺序） */
        private final List<String> ambiguousNames;
        /** 容错命中说明（各级尝试的命中数与来源） */
        private final String explanation;

        EntryResult(String rawEntry, String name, String anchor, MatchOutcome outcome)
        {
            this.rawEntry = rawEntry;
            this.name = name;
            this.anchor = anchor;
            this.status = outcome.status;
            this.matchLevel = outcome.level;
            this.matchedName = outcome.matchedName;
            this.ambiguousNames = outcome.ambiguousNames == null
                    ? Collections.emptyList() : outcome.ambiguousNames;
            this.explanation = outcome.explanation;
        }

        public String getRawEntry()
        {
            return rawEntry;
        }

        public String getName()
        {
            return name;
        }

        public String getAnchor()
        {
            return anchor;
        }

        public Status getStatus()
        {
            return status;
        }

        public MatchLevel getMatchLevel()
        {
            return matchLevel;
        }

        public String getMatchedName()
        {
            return matchedName;
        }

        public List<String> getAmbiguousNames()
        {
            return ambiguousNames;
        }

        public String getExplanation()
        {
            return explanation;
        }

        public boolean isMatched()
        {
            return status == Status.MATCHED;
        }

        public boolean isAmbiguous()
        {
            return status == Status.AMBIGUOUS;
        }

        public boolean isNotFound()
        {
            return status == Status.NOT_FOUND;
        }

        @Override
        public String toString()
        {
            if (status == Status.MATCHED)
            {
                return "\"" + rawEntry + "\"→命中\"" + matchedName + "\"（" + explanation + "）";
            }
            if (status == Status.AMBIGUOUS)
            {
                return "\"" + rawEntry + "\"→歧义（" + explanation + "）";
            }
            return "\"" + rawEntry + "\"→未命中（" + explanation + "）";
        }
    }
}
