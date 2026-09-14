package com.ruoyi.system.service.impl;

import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.vo.NoteColumnVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColumnDefaultValueSupport} 单元测试（U2/R7/KTD6）。
 * <p>
 * 覆盖：read/merge/validate 六类型合法与非法值、merge 保留既有键、
 * inheritDefault 继承语义、isDefaultOnlyChange 路由判定、readSubmittedDefault。
 */
class ColumnDefaultValueSupportTest
{
    private NoteColumn column(Long type, String property)
    {
        NoteColumn column = new NoteColumn();
        column.setId(1L);
        column.setName("测试列");
        column.setType(type);
        column.setDwtableId(10L);
        column.setProperty(property);
        return column;
    }

    // ---------------- read ----------------

    @Test
    void read_returnsDefaultValue()
    {
        assertEquals("进行中", ColumnDefaultValueSupport.read(column(3L, "{\"select\":\"进行中,已完成\",\"default\":\"进行中\"}")));
    }

    @Test
    void read_missingKey_returnsNull()
    {
        assertNull(ColumnDefaultValueSupport.read(column(3L, "{\"select\":\"进行中\"}")));
    }

    @Test
    void read_blankOrNullProperty_returnsNull()
    {
        assertNull(ColumnDefaultValueSupport.read(column(1L, null)));
        assertNull(ColumnDefaultValueSupport.read(column(1L, "")));
        assertNull(ColumnDefaultValueSupport.read(null));
    }

    @Test
    void read_blankDefaultValue_returnsNull()
    {
        assertNull(ColumnDefaultValueSupport.read(column(1L, "{\"default\":\"  \"}")));
    }

    // ---------------- merge ----------------

    @Test
    void merge_keepsExistingKeysAndSetsDefault()
    {
        NoteColumn origin = column(3L, "{\"select\":\"进行中,已完成\"}");
        String merged = ColumnDefaultValueSupport.merge(origin, "进行中");
        assertTrue(merged.contains("\"select\":\"进行中,已完成\""));
        assertTrue(merged.contains("\"default\":\"进行中\""));
    }

    @Test
    void merge_emptyDefaultValue_removesDefaultKey()
    {
        NoteColumn origin = column(3L, "{\"select\":\"A,B\",\"default\":\"A\"}");
        String merged = ColumnDefaultValueSupport.merge(origin, "");
        assertTrue(merged.contains("\"select\":\"A,B\""));
        assertFalse(merged.contains("\"default\""));
        assertNull(ColumnDefaultValueSupport.read(column(3L, merged)));
    }

    @Test
    void merge_nullDefaultValue_removesDefaultKey()
    {
        NoteColumn origin = column(1L, "{\"default\":\"x\"}");
        assertFalse(ColumnDefaultValueSupport.merge(origin, null).contains("\"default\""));
    }

    @Test
    void merge_nullOriginProperty_producesDefaultOnlyJson()
    {
        NoteColumn origin = column(1L, null);
        assertEquals("{\"default\":\"abc\"}", ColumnDefaultValueSupport.merge(origin, "abc"));
    }

    // ---------------- validate：六类型合法值 ----------------

    @Test
    void validate_text_acceptsAnyNonBlank()
    {
        ColumnDefaultValueSupport.validate(column(1L, null), "任意文本");
    }

    @Test
    void validate_number_acceptsParsable()
    {
        ColumnDefaultValueSupport.validate(column(2L, null), "123");
        ColumnDefaultValueSupport.validate(column(2L, null), "-12.5");
    }

    @Test
    void validate_select_acceptsOption()
    {
        ColumnDefaultValueSupport.validate(column(3L, "{\"select\":\"进行中,已完成\"}"), "进行中");
    }

    @Test
    void validate_multiSelect_acceptsOptionSubsetAndNormalizesFullWidthComma()
    {
        ColumnDefaultValueSupport.validate(column(4L, "{\"select\":\"进行中,已完成,已取消\"}"), "进行中,已完成");
        ColumnDefaultValueSupport.validate(column(4L, "{\"select\":\"进行中,已完成\"}"), "进行中，已完成");
    }

    @Test
    void validate_date_acceptsFullPattern()
    {
        ColumnDefaultValueSupport.validate(column(5L, null), "2026-01-02 03:04:05");
    }

    @Test
    void validate_checkbox_acceptsTrueFalse()
    {
        ColumnDefaultValueSupport.validate(column(7L, null), "true");
        ColumnDefaultValueSupport.validate(column(7L, null), "false");
    }

    @Test
    void validate_blankValue_passesWithoutTypeSupport()
    {
        // 清除默认值无需校验，包括不支持默认值的列类型
        ColumnDefaultValueSupport.validate(column(21L, null), null);
        ColumnDefaultValueSupport.validate(column(26L, null), "");
    }

    // ---------------- validate：非法值拒绝 ----------------

    @Test
    void validate_number_rejectsNonNumeric()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(2L, null), "abc"));
        assertTrue(ex.getMessage().contains("数字"));
        assertTrue(ex.getMessage().contains("测试列"));
    }

    @Test
    void validate_date_rejectsInvalidMonth()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(5L, null), "2026-13-01 00:00:00"));
        assertTrue(ex.getMessage().contains("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    void validate_date_rejectsWrongPattern()
    {
        assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(5L, null), "2026/1/1"));
    }

    @Test
    void validate_select_rejectsUnknownOption()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(3L, "{\"select\":\"进行中,已完成\"}"), "不存在选项"));
        assertTrue(ex.getMessage().contains("不在选项集内"));
    }

    @Test
    void validate_multiSelect_rejectsUnknownOption()
    {
        assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(4L, "{\"select\":\"进行中\"}"), "进行中,不存在"));
    }

    @Test
    void validate_checkbox_rejectsNonBoolean()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(7L, null), "yes"));
        assertTrue(ex.getMessage().contains("true 或 false"));
    }

    @Test
    void validate_unsupportedType_rejects()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(21L, "{\"table_id\":5}"), "x"));
        assertTrue(ex.getMessage().contains("不支持默认值"));
        assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(26L, null), "x"));
        assertThrows(ServiceException.class,
                () -> ColumnDefaultValueSupport.validate(column(1001L, null), "x"));
    }

    // ---------------- inheritDefault ----------------

    @Test
    void inheritDefault_submittedWithoutKey_inheritsOriginDefault()
    {
        NoteColumn origin = column(3L, "{\"select\":\"A,B\",\"default\":\"B\"}");
        String result = ColumnDefaultValueSupport.inheritDefault("{\"select\":\"A,B\"}", origin);
        assertTrue(result.contains("\"default\":\"B\""));
        assertTrue(result.contains("\"select\":\"A,B\""));
    }

    @Test
    void inheritDefault_submittedBlankValue_removesAndDoesNotInherit()
    {
        NoteColumn origin = column(1L, "{\"default\":\"x\"}");
        assertFalse(ColumnDefaultValueSupport.inheritDefault("{\"default\":\"\"}", origin).contains("\"default\""));
    }

    @Test
    void inheritDefault_submittedWithValue_keepsSubmitted()
    {
        NoteColumn origin = column(3L, "{\"select\":\"A,B\",\"default\":\"B\"}");
        String result = ColumnDefaultValueSupport.inheritDefault("{\"select\":\"A,B\",\"default\":\"A\"}", origin);
        assertTrue(result.contains("\"default\":\"A\""));
    }

    @Test
    void inheritDefault_blankSubmittedProperty_inheritsOriginDefault()
    {
        NoteColumn origin = column(1L, "{\"default\":\"x\"}");
        assertTrue(ColumnDefaultValueSupport.inheritDefault("", origin).contains("\"default\":\"x\""));
    }

    // ---------------- isDefaultOnlyChange ----------------

    private NoteColumnVo voWithProperty(myHashMap<String, Object> property)
    {
        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(1L);
        vo.setName("测试列");
        vo.setType(3L);
        vo.setDwtableId(10L);
        vo.setProperty(property);
        return vo;
    }

    @Test
    void isDefaultOnlyChange_defaultOnly_returnsTrue()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "A");
        assertTrue(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\"}"), voWithProperty(property)));
    }

    @Test
    void isDefaultOnlyChange_sameEverything_returnsTrue()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "B");
        assertTrue(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\",\"default\":\"B\"}"), voWithProperty(property)));
    }

    @Test
    void isDefaultOnlyChange_nameChanged_returnsFalse()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "A");
        NoteColumnVo vo = voWithProperty(property);
        vo.setName("新列名");
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\"}"), vo));
    }

    @Test
    void isDefaultOnlyChange_selectChanged_returnsFalse()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B,C");
        property.put("default", "A");
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\"}"), voWithProperty(property)));
    }

    @Test
    void isDefaultOnlyChange_withoutDefaultKey_returnsFalse()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\"}"), voWithProperty(property)));
    }

    @Test
    void isDefaultOnlyChange_isShowChanged_returnsFalse()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "A");
        NoteColumnVo vo = voWithProperty(property);
        vo.setIsShow(1L);
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\"}"), vo));
    }

    @Test
    void isDefaultOnlyChange_typeChanged_returnsFalse()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("default", "A");
        NoteColumnVo vo = voWithProperty(property);
        vo.setType(1L);
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(
                column(3L, "{\"select\":\"A,B\"}"), vo));
    }

    @Test
    void isDefaultOnlyChange_nullOriginOrProperty_returnsFalse()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("default", "A");
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(null, voWithProperty(property)));
        NoteColumnVo voWithoutProperty = new NoteColumnVo();
        voWithoutProperty.setId(1L);
        assertFalse(ColumnDefaultValueSupport.isDefaultOnlyChange(column(1L, null), voWithoutProperty));
    }

    // ---------------- readSubmittedDefault ----------------

    @Test
    void readSubmittedDefault_extractsValue()
    {
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "A");
        assertEquals("A", ColumnDefaultValueSupport.readSubmittedDefault(property));
    }

    @Test
    void readSubmittedDefault_missingOrNullBlank_returnsNull()
    {
        assertNull(ColumnDefaultValueSupport.readSubmittedDefault(null));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        assertNull(ColumnDefaultValueSupport.readSubmittedDefault(property));
        property.put("default", "  ");
        assertNull(ColumnDefaultValueSupport.readSubmittedDefault(property));
    }
}
