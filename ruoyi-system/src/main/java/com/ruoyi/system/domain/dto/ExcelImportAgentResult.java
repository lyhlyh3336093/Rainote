package com.ruoyi.system.domain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 多维表格 Excel 导入 agent 操作（{@code dwtable.importExcel}）的返回结构（成功/失败双态）。
 * <p>
 * agent 为单次计划模型（无多轮交互），缺参无法弹框：
 * <ul>
 *   <li>成功态（{@code success=true}）：导入已执行，携带 {@link #importedCount} 与静默变更清单
 *       （默认值填充/新选项创建/对称写入影响/歧义预选），供 agent 结果呈现与用户知悉；</li>
 *   <li>失败态（{@code success=false}）：导入未执行（零写入），携带 {@link #missingParams}
 *       未覆盖缺参清单——LLM 可将清单呈现给用户，用户在对话中提供补值后由 LLM 生成带
 *       {@code columnValues} 的第二次调用；关联类缺参（关联缺列/关联未命中/记录选择）无法由
 *       文本补值覆盖，须提示用户改走界面导入。</li>
 * </ul>
 * 复用 {@link ExcelImportPrecheckResult} 的内部静态类作为清单元素类型。
 *
 * @author ruoyi
 */
public class ExcelImportAgentResult
{
    /** 是否成功执行导入（false=未执行任何写入，仅返回缺参报告） */
    private boolean success;

    /** 人类可读摘要（成功：导入统计；失败：未执行原因与下一步指引） */
    private String message;

    /** 成功导入的记录数（仅成功态） */
    private Integer importedCount;

    /** 列默认值填充清单（仅成功态，静默变更知悉） */
    private List<ExcelImportPrecheckResult.DefaultValueFill> defaultValueFills = new ArrayList<>();

    /** 新选项创建清单（仅成功态，静默变更知悉） */
    private List<ExcelImportPrecheckResult.NewOption> newOptions = new ArrayList<>();

    /** 双链对称写入跨表影响（仅成功态，静默变更知悉） */
    private List<ExcelImportPrecheckResult.SymmetricWriteImpact> symmetricWriteImpact = new ArrayList<>();

    /** 歧义项清单（仅成功态：同名多条记录已按 sort 靠前预选建立关联，与界面预检默认一致） */
    private List<ExcelImportPrecheckResult.AmbiguityItem> ambiguityApplied = new ArrayList<>();

    /** 未覆盖缺参清单（仅失败态：columnValues 未覆盖的基础列缺参 + 无法覆盖的关联类缺参） */
    private List<ExcelImportPrecheckResult.MissingParam> missingParams = new ArrayList<>();

    public boolean isSuccess()
    {
        return success;
    }

    public void setSuccess(boolean success)
    {
        this.success = success;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public Integer getImportedCount()
    {
        return importedCount;
    }

    public void setImportedCount(Integer importedCount)
    {
        this.importedCount = importedCount;
    }

    public List<ExcelImportPrecheckResult.DefaultValueFill> getDefaultValueFills()
    {
        return defaultValueFills;
    }

    public void setDefaultValueFills(List<ExcelImportPrecheckResult.DefaultValueFill> defaultValueFills)
    {
        this.defaultValueFills = defaultValueFills;
    }

    public List<ExcelImportPrecheckResult.NewOption> getNewOptions()
    {
        return newOptions;
    }

    public void setNewOptions(List<ExcelImportPrecheckResult.NewOption> newOptions)
    {
        this.newOptions = newOptions;
    }

    public List<ExcelImportPrecheckResult.SymmetricWriteImpact> getSymmetricWriteImpact()
    {
        return symmetricWriteImpact;
    }

    public void setSymmetricWriteImpact(List<ExcelImportPrecheckResult.SymmetricWriteImpact> symmetricWriteImpact)
    {
        this.symmetricWriteImpact = symmetricWriteImpact;
    }

    public List<ExcelImportPrecheckResult.AmbiguityItem> getAmbiguityApplied()
    {
        return ambiguityApplied;
    }

    public void setAmbiguityApplied(List<ExcelImportPrecheckResult.AmbiguityItem> ambiguityApplied)
    {
        this.ambiguityApplied = ambiguityApplied;
    }

    public List<ExcelImportPrecheckResult.MissingParam> getMissingParams()
    {
        return missingParams;
    }

    public void setMissingParams(List<ExcelImportPrecheckResult.MissingParam> missingParams)
    {
        this.missingParams = missingParams;
    }
}
