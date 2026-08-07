package com.ruoyi.system.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ZipEntryNameSanitizer} 单元测试。
 * <p>
 * 覆盖 zip slip 防护向量：绝对路径、路径穿越（../）、反斜杠、长度、净化失败兜底。
 *
 * @author ruoyi
 */
class ZipEntryNameSanitizerTest
{
    @Test
    void sanitize_normalName_unchanged()
    {
        assertEquals("T1.sql", ZipEntryNameSanitizer.sanitize("T1.sql", 0));
        assertEquals("table_data_p1.xlsx", ZipEntryNameSanitizer.sanitize("table_data_p1.xlsx", 0));
    }

    @Test
    void sanitize_leadingSlash_stripped()
    {
        // 绝对路径 /etc/passwd → 剥离前导 /
        assertEquals("etc_passwd.sql", ZipEntryNameSanitizer.sanitize("/etc/passwd.sql", 0));
    }

    @Test
    void sanitize_leadingBackslash_stripped()
    {
        assertEquals("windows_system32.sql", ZipEntryNameSanitizer.sanitize("\\windows\\system32.sql", 0));
    }

    @Test
    void sanitize_pathTraversal_dotsReplaced()
    {
        // ../../etc/passwd.sql → split ["..","..","etc","passwd.sql"]
        // .. → _，分隔符也 → _：
        // i=0: _ (from ..)
        // i=1: _ (sep) + _ (from ..) = __
        // i=2: _ (sep) + etc = _etc
        // i=3: _ (sep) + passwd.sql = _passwd.sql
        // Total: ____etc_passwd.sql
        assertEquals("____etc_passwd.sql", ZipEntryNameSanitizer.sanitize("../../etc/passwd.sql", 0));
    }

    @Test
    void sanitize_singleDot_replaced()
    {
        // ./etc/passwd.sql → split [".","etc","passwd.sql"]
        // . → _，分隔符 → _，结果 __etc_passwd.sql
        assertEquals("__etc_passwd.sql", ZipEntryNameSanitizer.sanitize("./etc/passwd.sql", 0));
    }

    @Test
    void sanitize_mixedSeparators_replacedWithUnderscore()
    {
        assertEquals("a_b_c.sql", ZipEntryNameSanitizer.sanitize("a/b\\c.sql", 0));
    }

    @Test
    void sanitize_tooLong_truncatedWithExtensionPreserved()
    {
        String longName = new String(new char[300]).replace('\0', 'a') + ".sql";
        String result = ZipEntryNameSanitizer.sanitize(longName, 0);
        assertTrue(result.length() <= 255);
        assertTrue(result.endsWith(".sql"));
    }

    @Test
    void sanitize_tooLongNoExtension_truncatedTo255()
    {
        String longName = new String(new char[300]).replace('\0', 'a');
        String result = ZipEntryNameSanitizer.sanitize(longName, 0);
        assertEquals(255, result.length());
    }

    @Test
    void sanitize_null_returnsEntryIdx()
    {
        assertEquals("entry_0", ZipEntryNameSanitizer.sanitize(null, 0));
        assertEquals("entry_5", ZipEntryNameSanitizer.sanitize(null, 5));
    }

    @Test
    void sanitize_empty_returnsEntryIdx()
    {
        assertEquals("entry_0", ZipEntryNameSanitizer.sanitize("", 0));
    }

    @Test
    void sanitize_allSlashes_returnsEntryIdx()
    {
        // 全是路径分隔符 → 净化后为 _ 或空 → 回退
        String result = ZipEntryNameSanitizer.sanitize("///", 3);
        // 应为安全名（不含前导/）
        assertFalse(result.startsWith("/"));
        assertFalse(result.contains(".."));
    }

    @Test
    void sanitize_windowsAbsolutePath_strippedAndSafe()
    {
        // C:\Windows\system32\evil.dll → 剥离前导 \ + 替换分隔符
        String result = ZipEntryNameSanitizer.sanitize("C:\\Windows\\system32\\evil.dll", 0);
        assertFalse(result.startsWith("/"));
        assertFalse(result.startsWith("\\"));
        assertFalse(result.contains(".."));
        assertFalse(result.contains(":"));
    }
}
