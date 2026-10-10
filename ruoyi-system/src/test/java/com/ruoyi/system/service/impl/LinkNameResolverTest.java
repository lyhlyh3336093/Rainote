package com.ruoyi.system.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ruoyi.system.service.impl.LinkNameResolver.EntryResult;
import com.ruoyi.system.service.impl.LinkNameResolver.MatchLevel;
import com.ruoyi.system.service.impl.LinkNameResolver.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LinkNameResolver} 单元测试（U2：共享名称解析组件，R7）。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>精确命中单候选；多值逐条目全命中（整串未命中才拆分）；候选为空仍未命中</li>
 *   <li>CSV 转义往返：含逗号名称被引号包裹 → 还原转义后整串命中；
 *       内层双引号翻倍还原（{@code ""} → {@code "}）</li>
 *   <li>整串优先（未转义含逗号名称的既有数据兜底，级别 WHOLE）；
 *       整串级同名多条 → 单条目歧义</li>
 *   <li>歧义：两候选同前缀唯一化前多命中 / 候选名重复（同名多条）/ 归一化多名碰撞</li>
 *   <li>唯一前缀 ≥2 字符命中（PREFIX）；1 字符前缀不触发容错、按未命中</li>
 *   <li>归一化：全角/半角、大小写、首尾空格（含全角空格）差异归一后命中</li>
 *   <li>空串条目跳过不计失败（对应"无链接"）；null/纯空白单元格无条目</li>
 *   <li>25 列条目语法：锚词剥离后命中笔记名、锚词正确返回（含空锚词、锚词内全角括号）；
 *       剥离名未命中回退整条目（21 列名称含全角括号后缀形态）；整条目与剥离名同时为候选时
 *       <b>整条目强匹配优先</b>（修复"甲（乙）"被剥离名"甲"抢占错链）</li>
 * </ul>
 *
 * @author ruoyi
 */
class LinkNameResolverTest
{
    private final LinkNameResolver resolver = new LinkNameResolver();

    // ---------- 精确 / 多值 / 未命中 ----------

    @Test
    void resolveShouldMatchSingleExactCandidate()
    {
        List<EntryResult> results = resolver.resolve("满攻", Arrays.asList("强攻", "满攻", "追击"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals(Status.MATCHED, result.getStatus());
        assertEquals("满攻", result.getMatchedName());
        assertEquals(MatchLevel.EXACT, result.getMatchLevel());
        assertNull(result.getAnchor());
    }

    @Test
    void resolveShouldMatchEachEntryOfMultiValueCell()
    {
        // 整串"满攻, 强攻"不是候选名 → 拆分逐条目，各自命中
        List<EntryResult> results = resolver.resolve("满攻, 强攻", Arrays.asList("满攻", "强攻", "追击"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isMatched());
        assertEquals("满攻", results.get(0).getMatchedName());
        assertTrue(results.get(1).isMatched());
        assertEquals("强攻", results.get(1).getMatchedName());
    }

    @Test
    void resolveShouldReturnNotFoundWhenCandidateListEmpty()
    {
        List<EntryResult> results = resolver.resolve("笔记A", Collections.emptyList());

        assertEquals(1, results.size());
        assertTrue(results.get(0).isNotFound());
        assertNull(results.get(0).getMatchedName());
    }

    // ---------- CSV 转义还原 ----------

    @Test
    void resolveShouldUnquoteAndMatchCommaContainingName()
    {
        // 导出侧 escapeCsvValue("满攻,强攻") = "\"满攻,强攻\""：还原包裹后整串命中
        List<EntryResult> results = resolver.resolve("\"满攻,强攻\"", Arrays.asList("满攻", "强攻", "满攻,强攻"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("满攻,强攻", result.getMatchedName());
        assertEquals(MatchLevel.EXACT, result.getMatchLevel());
    }

    @Test
    void resolveShouldRestoreInnerQuotesFromEscapedEntry()
    {
        // 导出侧 escapeCsvValue("他说\"你好\"") = "\"他说\"\"你好\"\"\""：内层双引号翻倍还原
        List<EntryResult> results = resolver.resolve("\"他说\"\"你好\"\"\"", Collections.singletonList("他说\"你好\""));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isMatched());
        assertEquals("他说\"你好\"", results.get(0).getMatchedName());
    }

    @Test
    void resolveShouldMatchMultipleEscapedEntriesWithMixedNames()
    {
        // 多值 + 转义混合：引号内逗号不拆分、引号外逗号拆分
        List<EntryResult> results = resolver.resolve("\"满攻,强攻\", 追击", Arrays.asList("满攻,强攻", "追击"));

        assertEquals(2, results.size());
        assertEquals("满攻,强攻", results.get(0).getMatchedName());
        assertEquals("追击", results.get(1).getMatchedName());
    }

    // ---------- 整串优先（未转义含逗号名称兜底） ----------

    @Test
    void resolveShouldMatchUnquotedCommaContainingNameAsWhole()
    {
        // 未转义含逗号名称（既有数据形态）：整串优先命中，不拆逗号
        List<EntryResult> results = resolver.resolve("满攻,强攻", Arrays.asList("满攻", "强攻", "满攻,强攻"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("满攻,强攻", result.getMatchedName());
        assertEquals(MatchLevel.WHOLE, result.getMatchLevel());
    }

    @Test
    void resolveShouldReportAmbiguousWhenWholeTextMatchesDuplicateNames()
    {
        // 整串优先级同名多条：单条目歧义，不拆分
        List<EntryResult> results = resolver.resolve("满攻,强攻", Arrays.asList("满攻,强攻", "满攻,强攻"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isAmbiguous());
        assertEquals(Collections.singletonList("满攻,强攻"), result.getAmbiguousNames());
    }

    // ---------- 歧义（多命中即歧义，不任选） ----------

    @Test
    void resolveShouldReturnAmbiguousOnSharedPrefix()
    {
        // 两个候选同前缀"满攻"：唯一前缀多命中 → 歧义而非任选
        List<EntryResult> results = resolver.resolve("满攻", Arrays.asList("满攻A", "满攻B", "追击"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isAmbiguous());
        assertEquals(Arrays.asList("满攻A", "满攻B"), result.getAmbiguousNames());
        assertNull(result.getMatchedName());
    }

    @Test
    void resolveShouldReportAmbiguousOnDuplicateCandidateNames()
    {
        // 候选名重复出现（同名多条记录）：精确多命中 → 歧义（18 列"同名取 sort 最小"不进本组件）
        List<EntryResult> results = resolver.resolve("笔记A", Arrays.asList("笔记A", "笔记A", "笔记B"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isAmbiguous());
    }

    @Test
    void resolveShouldReportAmbiguousOnNormalizedCollision()
    {
        // 两个不同候选归一化后相同：精确 0、归一化 2 命中 → 歧义
        List<EntryResult> results = resolver.resolve("NOTE", Arrays.asList("note", "Note"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isAmbiguous());
        assertEquals(Arrays.asList("note", "Note"), result.getAmbiguousNames());
    }

    // ---------- 唯一前缀 ----------

    @Test
    void resolveShouldMatchUniquePrefixOfTwoChars()
    {
        List<EntryResult> results = resolver.resolve("笔记", Arrays.asList("笔记A", "日历"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("笔记A", result.getMatchedName());
        assertEquals(MatchLevel.PREFIX, result.getMatchLevel());
    }

    @Test
    void resolveShouldNotMatchSingleCharPrefix()
    {
        // 1 字符前缀不触发容错、按未命中
        List<EntryResult> results = resolver.resolve("笔", Collections.singletonList("笔记A"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isNotFound());
    }

    // ---------- 归一化（trim / 全角半角 / 大小写） ----------

    @Test
    void resolveShouldMatchAfterNormalization()
    {
        // 首尾空格（含 trim 不掉的全角空格 U+3000）+ 全角字母 + 大小写差异，归一后命中
        List<EntryResult> results = resolver.resolve("　Ｎｏｔｅ　", Arrays.asList("Other", "Note"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("Note", result.getMatchedName());
        assertEquals(MatchLevel.NORMALIZED, result.getMatchLevel());
    }

    @Test
    void resolveShouldNormalizeFullWidthPunctuationAndCase()
    {
        // 全角冒号/数字与半角互换、大小写归一后命中（不以"）"结尾，不触发锚词剥离）
        List<EntryResult> results = resolver.resolve("ｓｋｉｌｌ：１", Collections.singletonList("Skill:1"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("Skill:1", result.getMatchedName());
        assertEquals(MatchLevel.NORMALIZED, result.getMatchLevel());
    }

    // ---------- 空串条目 / 空白单元格 ----------

    @Test
    void resolveShouldSkipEmptyEntries()
    {
        // 连续逗号与纯空格条目跳过不计失败（对应"无链接"）
        List<EntryResult> results = resolver.resolve("笔记A,,  ,笔记B", Arrays.asList("笔记A", "笔记B"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isMatched());
        assertEquals("笔记A", results.get(0).getMatchedName());
        assertTrue(results.get(1).isMatched());
        assertEquals("笔记B", results.get(1).getMatchedName());
    }

    @Test
    void resolveShouldReturnNoEntriesForBlankCell()
    {
        assertEquals(Collections.emptyList(), resolver.resolve(null, Arrays.asList("笔记A")));
        assertEquals(Collections.emptyList(), resolver.resolve("   ", Arrays.asList("笔记A")));
    }

    // ---------- 25 列条目语法（锚词剥离） ----------

    @Test
    void resolveShouldStripAnchorAndMatchNoteName()
    {
        // "笔记名（锚词）"：剥离锚词后命中笔记名，锚词正确返回（R2）
        List<EntryResult> results = resolver.resolve("笔记A（攻击力）", Arrays.asList("笔记A", "笔记B"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("笔记A", result.getMatchedName());
        assertEquals("攻击力", result.getAnchor());
        assertEquals("笔记A", result.getName());
        assertEquals("笔记A（攻击力）", result.getRawEntry());
    }

    @Test
    void resolveShouldReturnEmptyAnchorForEmptyParentheses()
    {
        List<EntryResult> results = resolver.resolve("笔记A（）", Collections.singletonList("笔记A"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isMatched());
        assertEquals("笔记A", results.get(0).getMatchedName());
        assertEquals("", results.get(0).getAnchor());
    }

    @Test
    void resolveShouldPairAnchorWithLastClosingParen()
    {
        // 锚词内含全角"）"：取末位"）"配对，锚词完整返回
        List<EntryResult> results = resolver.resolve("笔记A（攻）高）", Collections.singletonList("笔记A"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isMatched());
        assertEquals("笔记A", results.get(0).getMatchedName());
        assertEquals("攻）高", results.get(0).getAnchor());
    }

    @Test
    void resolveShouldFallbackToWholeEntryWhenAnchorStrippedNameMisses()
    {
        // 名称本身含全角括号后缀（如 21 列记录名）：剥离名未命中 → 整条目回退命中，锚词置空
        List<EntryResult> results = resolver.resolve("笔记（红）", Arrays.asList("笔记（红）", "其他"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("笔记（红）", result.getMatchedName());
        assertNull(result.getAnchor());
        assertEquals("笔记（红）", result.getName());
    }

    @Test
    void resolveShouldPreferWholeEntryWhenBothAreCandidates()
    {
        // 整条目与剥离名同时为候选：整条目强匹配优先（"笔记（红）"是真实候选名时
        // 不得被剥离名"笔记"抢占错链），锚词置空（命中整条目=名称本身含括号后缀）
        List<EntryResult> results = resolver.resolve("笔记（红）", Arrays.asList("笔记", "笔记（红）"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("笔记（红）", result.getMatchedName());
        assertNull(result.getAnchor());
        assertEquals("笔记（红）", result.getName());
    }

    @Test
    void resolveShouldPreferWholeEntryOverStrippedNameWhenBothCoexist()
    {
        // "甲"与"甲（乙）"并存为候选、条目为"甲（乙）"：命中整条目"甲（乙）"——
        // 剥离名"甲"仅当整条目强匹配未命中时才接管（锚词语法的可信回退）
        List<EntryResult> results = resolver.resolve("甲（乙）", Arrays.asList("甲", "甲（乙）"));

        assertEquals(1, results.size());
        EntryResult result = results.get(0);
        assertTrue(result.isMatched());
        assertEquals("甲（乙）", result.getMatchedName());
        assertNull(result.getAnchor());

        // 整条目不是候选时剥离名接管（25 列"笔记名（锚词）"语义不变，锚词保留）
        List<EntryResult> strippedOnly = resolver.resolve("甲（乙）", Collections.singletonList("甲"));
        assertEquals(1, strippedOnly.size());
        assertTrue(strippedOnly.get(0).isMatched());
        assertEquals("甲", strippedOnly.get(0).getMatchedName());
        assertEquals("乙", strippedOnly.get(0).getAnchor());
    }

    @Test
    void resolveShouldTreatEntryWithoutClosingParenAsName()
    {
        // 不以"）"结尾：不剥离，整条目为名称
        List<EntryResult> results = resolver.resolve("笔记A（锚词", Collections.singletonList("笔记A（锚词"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isMatched());
        assertEquals("笔记A（锚词", results.get(0).getMatchedName());
        assertNull(results.get(0).getAnchor());
    }

    @Test
    void resolveShouldStripAnchorForEachMultiValueEntry()
    {
        // 25 列多锚点单元格：逐条目剥离锚词、各自命中
        List<EntryResult> results = resolver.resolve("笔记A（攻击力）, 笔记B（防御）",
                Arrays.asList("笔记A", "笔记B"));

        assertEquals(2, results.size());
        assertEquals("笔记A", results.get(0).getMatchedName());
        assertEquals("攻击力", results.get(0).getAnchor());
        assertEquals("笔记B", results.get(1).getMatchedName());
        assertEquals("防御", results.get(1).getAnchor());
    }

    // ---------- 混合状态与说明 ----------

    @Test
    void resolveShouldReportPerEntryStatusForPartiallyMatchedCell()
    {
        // 多值部分未命中：命中与未命中并存（任一失败由调用方并入清单，R9）
        List<EntryResult> results = resolver.resolve("笔记A, 不存在的笔记", Arrays.asList("笔记A", "笔记B"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isMatched());
        assertTrue(results.get(1).isNotFound());
    }

    @Test
    void resolveShouldCarryExplanationWithAttemptedLevels()
    {
        // 容错命中说明：各级尝试的命中数与来源（供失败清单呈现，R8）
        List<EntryResult> ambiguous = resolver.resolve("满攻", Arrays.asList("满攻A", "满攻B"));
        assertTrue(ambiguous.get(0).getExplanation().contains("唯一前缀 2 命中"));

        List<EntryResult> notFound = resolver.resolve("笔", Collections.singletonList("笔记A"));
        assertTrue(notFound.get(0).getExplanation().contains("唯一前缀未触发（长度<2）"));

        List<EntryResult> whole = resolver.resolve("满攻,强攻", Collections.singletonList("满攻,强攻"));
        assertTrue(whole.get(0).getExplanation().contains("整串优先"));
    }
}
