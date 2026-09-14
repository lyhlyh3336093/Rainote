package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.vo.NoteColumnVo;

/**
 * 列默认值工具类（U2，KTD6）。
 * <p>
 * 默认值存储于 {@code NoteColumn.property} JSON 的 {@code default} 键：
 * <ul>
 *   <li>文本(1)：任意非空文本</li>
 *   <li>数字(2)：可 parse 的数字</li>
 *   <li>单选(3)：选项集（property {@code select} 逗号字符串）中的单项</li>
 *   <li>多选(4)：选项集子集，多个值以英文逗号分隔</li>
 *   <li>日期(5)：{@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>复选框(7)：{@code "true"} / {@code "false"}</li>
 * </ul>
 * 其余列类型不支持默认值。
 * <p>
 * 路由不变量（KTD6）：仅当列保存 diff 只含 {@code property.default} 键变更时
 * 走 mapper 直更（见 {@link #isDefaultOnlyChange}）；任何 property 写入路径
 * 覆写前须经 {@link #inheritDefault} 合并保留 {@code default} 键，
 * 不得静默清除（清空输入=显式提交空值移除键）。
 */
public final class ColumnDefaultValueSupport
{
    /** property JSON 中默认值的键名 */
    public static final String DEFAULT_KEY = "default";

    /** 单选/多选选项集在 property JSON 中的键名（逗号分隔字符串，非数组） */
    public static final String SELECT_KEY = "select";

    /** 日期默认值格式（与导出/单元格显示值一致） */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private ColumnDefaultValueSupport()
    {
    }

    /**
     * 读取列的默认值。
     *
     * @param column 列（property 为 JSON 字符串，可能为 null/空/含尾逗号的历史格式）
     * @return 默认值；无 {@code default} 键或值为空时返回 null
     */
    public static String read(NoteColumn column)
    {
        JSONObject prop = parseSafe(column == null ? null : column.getProperty());
        String defaultValue = prop.getString(DEFAULT_KEY);
        if (defaultValue == null || defaultValue.trim().isEmpty())
        {
            return null;
        }
        return defaultValue;
    }

    /**
     * 合并默认值到列的 property（直更路径使用：以原 property 为基础）。
     * <p>
     * 保留原 property 中 {@code select}、{@code back_field_id} 等既有键；
     * defaultValue 为 null/空时移除 {@code default} 键（清除语义）。
     *
     * @param column 原列
     * @param defaultValue 新默认值（null/空=移除）
     * @return 新 property JSON 字符串
     */
    public static String merge(NoteColumn column, String defaultValue)
    {
        JSONObject prop = parseSafe(column == null ? null : column.getProperty());
        if (defaultValue == null || defaultValue.trim().isEmpty())
        {
            prop.remove(DEFAULT_KEY);
        }
        else
        {
            prop.put(DEFAULT_KEY, defaultValue);
        }
        return prop.toJSONString();
    }

    /**
     * 校验默认值与列类型匹配，违规抛 {@link ServiceException}（消息含列名与原因）。
     * <p>
     * defaultValue 为 null/空时直接通过（清除默认值无需校验）。
     *
     * @param column 列（单选/多选的选项集取自该列 property 的 select 键）
     * @param defaultValue 待校验的默认值
     * @throws ServiceException 类型不支持或格式/取值非法
     */
    public static void validate(NoteColumn column, String defaultValue)
    {
        if (column == null)
        {
            throw new ServiceException("列不存在，无法配置默认值");
        }
        if (defaultValue == null || defaultValue.trim().isEmpty())
        {
            return;
        }
        String value = defaultValue.trim();
        Long type = column.getType();
        String columnName = column.getName() == null ? "" : column.getName();
        if (type == null)
        {
            throw new ServiceException("列“" + columnName + "”缺少类型，无法配置默认值");
        }
        switch (type.intValue())
        {
            case 1:
                // 文本：任意非空（空已在上方排除）
                return;
            case 2:
                try
                {
                    new BigDecimal(value);
                }
                catch (NumberFormatException e)
                {
                    throw new ServiceException("列“" + columnName + "”的默认值必须是数字");
                }
                return;
            case 3:
            case 4:
                Set<String> options = parseSelectOptions(column);
                for (String part : normalizeCommas(value).split(","))
                {
                    String candidate = part.trim();
                    if (candidate.isEmpty())
                    {
                        continue;
                    }
                    if (!options.contains(candidate))
                    {
                        throw new ServiceException(
                                "列“" + columnName + "”的默认值“" + candidate + "”不在选项集内");
                    }
                }
                return;
            case 5:
                try
                {
                    LocalDateTime.parse(value, DATE_TIME_FORMATTER);
                }
                catch (Exception e)
                {
                    throw new ServiceException(
                            "列“" + columnName + "”的默认值必须是 yyyy-MM-dd HH:mm:ss 格式的日期");
                }
                return;
            case 7:
                if (!"true".equals(value) && !"false".equals(value))
                {
                    throw new ServiceException("列“" + columnName + "”的默认值必须是 true 或 false");
                }
                return;
            default:
                throw new ServiceException("列“" + columnName + "”该列类型不支持默认值");
        }
    }

    /**
     * service 原路径覆写 property 前的 default 键继承（KTD6 保留不变量）。
     * <p>
     * 提交 property 显式携带 {@code default} 键时以提交为准（空值=清除语义，
     * 移除键且不回填原值）；未携带 {@code default} 键时从原列 property 继承
     * （防改名/改选项保存静默清除默认值）。
     *
     * @param submittedPropertyJson 提交的 property JSON 字符串（可能为 null/空）
     * @param originColumn 原列
     * @return 合并后的 property JSON 字符串
     */
    public static String inheritDefault(String submittedPropertyJson, NoteColumn originColumn)
    {
        JSONObject submitted = parseSafe(submittedPropertyJson);
        if (submitted.containsKey(DEFAULT_KEY))
        {
            // 显式携带 default 键（空值=清除），以提交为准
            String submittedDefault = submitted.getString(DEFAULT_KEY);
            if (submittedDefault == null || submittedDefault.trim().isEmpty())
            {
                submitted.remove(DEFAULT_KEY);
            }
        }
        else
        {
            // 未携带 default 键：从原列继承（KTD6 保留不变量）
            String originDefault = read(originColumn);
            if (originDefault != null)
            {
                submitted.put(DEFAULT_KEY, originDefault);
            }
        }
        return submitted.toJSONString();
    }

    /**
     * 判断提交的列变更是否仅含 {@code property.default} 键变更（KTD6 路由不变量）。
     * <p>
     * 需同时满足：name/type/isShow/sort/dwtableId 均无变更（null 视为未提交）、
     * property 除 {@code default} 键外其余键值完全一致、且提交 property 显式
     * 携带 {@code default} 键（六类基础列编辑面板的提交形态）。
     *
     * @param origin 库中原列
     * @param submitted 提交的列 VO
     * @return true 时可走 mapper 直更，否则须走 service 原路径
     */
    public static boolean isDefaultOnlyChange(NoteColumn origin, NoteColumnVo submitted)
    {
        if (origin == null || submitted == null || submitted.getProperty() == null)
        {
            return false;
        }
        if (fieldChanged(submitted.getName(), origin.getName())
                || fieldChanged(submitted.getType(), origin.getType())
                || fieldChanged(submitted.getIsShow(), origin.getIsShow())
                || fieldChanged(submitted.getSort(), origin.getSort())
                || fieldChanged(submitted.getDwtableId(), origin.getDwtableId()))
        {
            return false;
        }
        JSONObject submittedJson = parseSafe(JSON.toJSONString(submitted.getProperty()));
        if (!submittedJson.containsKey(DEFAULT_KEY))
        {
            return false;
        }
        JSONObject originJson = parseSafe(origin.getProperty());
        Set<String> keys = new HashSet<>(originJson.keySet());
        keys.addAll(submittedJson.keySet());
        keys.remove(DEFAULT_KEY);
        for (String key : keys)
        {
            if (!jsonValueEquals(originJson.get(key), submittedJson.get(key)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 从提交的 property 中读取默认值（null 安全）。
     *
     * @param submittedProperty 提交的 property map（myHashMap，可为 null）
     * @return 默认值；键不存在或值为空时返回 null
     */
    public static String readSubmittedDefault(Map<String, Object> submittedProperty)
    {
        if (submittedProperty == null)
        {
            return null;
        }
        Object defaultValue = submittedProperty.get(DEFAULT_KEY);
        if (defaultValue == null)
        {
            return null;
        }
        String value = String.valueOf(defaultValue).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析单选/多选列的选项集（property select 逗号字符串，全角逗号归一为半角）。
     */
    private static Set<String> parseSelectOptions(NoteColumn column)
    {
        JSONObject prop = parseSafe(column.getProperty());
        Set<String> options = new HashSet<>();
        Object select = prop.get(SELECT_KEY);
        if (select == null)
        {
            return options;
        }
        for (String part : normalizeCommas(String.valueOf(select)).split(","))
        {
            String option = part.trim();
            if (!option.isEmpty())
            {
                options.add(option);
            }
        }
        return options;
    }

    /**
     * 全角逗号归一为半角逗号。
     */
    private static String normalizeCommas(String value)
    {
        return value == null ? "" : value.replace("，", ",");
    }

    /**
     * 字段级变更判定：submitted 为 null 视为未提交（无变更）。
     */
    private static boolean fieldChanged(Object submitted, Object origin)
    {
        return submitted != null && !submitted.equals(origin);
    }

    /**
     * JSON 值宽松相等（跨 Integer/Long 等数值表示）。
     */
    private static boolean jsonValueEquals(Object a, Object b)
    {
        if (a == null && b == null)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /**
     * 宽松解析 property JSON：null/空/非法时返回空 JSONObject（不抛异常）。
     */
    private static JSONObject parseSafe(String propertyJson)
    {
        if (propertyJson == null || propertyJson.trim().isEmpty())
        {
            return new JSONObject();
        }
        try
        {
            JSONObject parsed = JSONObject.parseObject(propertyJson);
            return parsed == null ? new JSONObject() : parsed;
        }
        catch (Exception e)
        {
            return new JSONObject();
        }
    }
}
