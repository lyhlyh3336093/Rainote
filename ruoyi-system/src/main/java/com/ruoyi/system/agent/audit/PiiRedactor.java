package com.ruoyi.system.agent.audit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * PII 脱敏工具，在 LLM 调用前对用户输入应用脱敏（R27 信任边界）。
 * <p>
 * U2 首个使用方（{@code AgentLlmService}），U4 审计日志复用。
 * <p>
 * 提供两种脱敏入口：
 * <ul>
 *   <li>{@link #redact(String)} — 纯文本脱敏，用于用户输入</li>
 *   <li>{@link #redactJson(String)} — JSON 递归脱敏，用于审计日志的 planJson / paramsJson，
 *       递归遍历 JSON 对象和数组中的所有字符串值进行脱敏</li>
 * </ul>
 * 脱敏规则：
 * <ul>
 *   <li>手机号（1[3-9]\d{9}）→ 保留前3后4，中间4位 *</li>
 *   <li>邮箱 → 用户名保留首末字符，中间用 * 替换</li>
 *   <li>身份证号（18位）→ 保留前6后4，中间8位 *</li>
 *   <li>银行卡号（16-19位连续数字）→ 保留前4后4，中间 *</li>
 * </ul>
 */
@Component
public class PiiRedactor
{
    /** 中国手机号：1[3-9]开头 + 9位数字 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    /** 18位身份证号（最后一位可为X） */
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    /** 16-19位连续数字（银行卡号） */
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");

    /**
     * 对输入文本进行 PII 脱敏。
     *
     * @param input 原始输入，可能为 null
     * @return 脱敏后的文本，null 输入返回 null
     */
    public String redact(String input)
    {
        if (input == null || input.isEmpty())
        {
            return input;
        }
        String result = input;
        result = replaceAll(result, PHONE, new MaskFunction()
        {
            @Override
            public String apply(String match)
            {
                return maskPhone(match);
            }
        });
        result = replaceAll(result, EMAIL, new MaskFunction()
        {
            @Override
            public String apply(String match)
            {
                return maskEmail(match);
            }
        });
        result = replaceAll(result, ID_CARD, new MaskFunction()
        {
            @Override
            public String apply(String match)
            {
                return maskIdCard(match);
            }
        });
        result = replaceAll(result, BANK_CARD, new MaskFunction()
        {
            @Override
            public String apply(String match)
            {
                return maskBankCard(match);
            }
        });
        return result;
    }

    /**
     * 对 JSON 字符串进行递归 PII 脱敏。
     * <p>
     * 解析 JSON 后递归遍历所有层级的字符串值，对每个字符串值应用 {@link #redact(String)} 脱敏。
     * 用于审计日志的 planJson / paramsJson 字段（U4）。
     * <p>
     * 如果输入不是合法 JSON，回退到纯文本 {@link #redact(String)} 脱敏。
     *
     * @param json 原始 JSON 字符串，可能为 null
     * @return 脱敏后的 JSON 字符串，null 输入返回 null
     */
    public String redactJson(String json)
    {
        if (json == null || json.isEmpty())
        {
            return json;
        }
        try
        {
            Object parsed = JSON.parse(json);
            Object redacted = redactValue(parsed);
            return JSON.toJSONString(redacted);
        }
        catch (Exception e)
        {
            // 非 JSON 格式，回退到纯文本脱敏
            return redact(json);
        }
    }

    /**
     * 递归脱敏 JSON 值。
     * <ul>
     *   <li>JSONObject → 遍历每个 entry 的 value 递归脱敏</li>
     *   <li>JSONArray → 遍历每个元素递归脱敏</li>
     *   <li>String → 应用 {@link #redact(String)}</li>
     *   <li>其他类型（Number/Boolean/null）→ 原样返回</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Object redactValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof String)
        {
            return redact((String) value);
        }
        if (value instanceof JSONObject)
        {
            JSONObject obj = (JSONObject) value;
            JSONObject result = new JSONObject(obj.size());
            for (Map.Entry<String, Object> entry : obj.entrySet())
            {
                result.put(entry.getKey(), redactValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof JSONArray)
        {
            JSONArray arr = (JSONArray) value;
            JSONArray result = new JSONArray(arr.size());
            for (int i = 0; i < arr.size(); i++)
            {
                result.add(redactValue(arr.get(i)));
            }
            return result;
        }
        // Number, Boolean, BigDecimal 等非字符串类型原样返回
        return value;
    }

    /** Java 8 兼容的掩码函数接口 */
    private interface MaskFunction
    {
        String apply(String match);
    }

    /**
     * Java 8 兼容的 replaceAll：用 MaskFunction 对每个匹配做替换。
     */
    private String replaceAll(String input, Pattern pattern, MaskFunction fn)
    {
        java.util.regex.Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fn.apply(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 138****1234 */
    private String maskPhone(String phone)
    {
        if (phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** a***e@example.com */
    private String maskEmail(String email)
    {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        String user = email.substring(0, at);
        String domain = email.substring(at);
        if (user.length() <= 2) return user.charAt(0) + "*" + domain;
        return user.charAt(0) + repeat('*', user.length() - 2) + user.charAt(user.length() - 1) + domain;
    }

    /** 110102********1234 */
    private String maskIdCard(String idCard)
    {
        if (idCard.length() < 10) return idCard;
        return idCard.substring(0, 6) + repeat('*', idCard.length() - 10) + idCard.substring(idCard.length() - 4);
    }

    /** 6225****5678 */
    private String maskBankCard(String card)
    {
        if (card.length() < 8) return card;
        return card.substring(0, 4) + repeat('*', card.length() - 8) + card.substring(card.length() - 4);
    }

    private String repeat(char c, int count)
    {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}
