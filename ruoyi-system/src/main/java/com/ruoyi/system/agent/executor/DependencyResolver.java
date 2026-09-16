package com.ruoyi.system.agent.executor;

import com.ruoyi.system.agent.model.AgentStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * 步骤依赖解析器。
 * <p>
 * 负责判断步骤是否可执行（依赖均已完成）以及计算级联阻塞范围（依赖失败时传递阻塞给所有后继步骤）。
 *
 * @see AgentPlanExecutor
 */
@Component
public class DependencyResolver
{
    /**
     * 判断步骤是否可执行：所有 dependsOn 引用的步骤均已完成。
     *
     * @param step             待执行的步骤
     * @param completedStepIds 已成功完成的步骤 ID 集合
     * @return true 若所有依赖均已完成
     */
    public boolean canExecute(AgentStep step, Set<String> completedStepIds)
    {
        for (String dep : step.getDependsOn())
        {
            if (!completedStepIds.contains(dep))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算指定步骤的所有传递依赖后继（BFS）。
     * <p>
     * 当步骤失败或跳过时，所有传递依赖该步骤的后继步骤都应被标记为 blocked。
     *
     * @param failedStepId 失败/跳过的步骤 ID
     * @param allSteps     计划中的全部步骤
     * @return 应被阻塞的后继步骤 ID 集合（不含 failedStepId 自身）
     */
    public Set<String> getTransitiveDependents(String failedStepId, List<AgentStep> allSteps)
    {
        Set<String> result = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(failedStepId);

        while (!queue.isEmpty())
        {
            String current = queue.poll();
            for (AgentStep step : allSteps)
            {
                if (step.getDependsOn().contains(current) && !result.contains(step.getStepId()))
                {
                    result.add(step.getStepId());
                    queue.add(step.getStepId());
                }
            }
        }
        return result;
    }

    /**
     * 获取步骤在计划中的索引。
     */
    public int getStepIndex(List<AgentStep> steps, String stepId)
    {
        for (int i = 0; i < steps.size(); i++)
        {
            if (steps.get(i).getStepId().equals(stepId))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取计划中尚未执行的步骤（用于恢复时确定续跑起点）。
     *
     * @param allSteps        计划全部步骤
     * @param completedStepIds 已完成步骤 ID 集合
     * @return 尚未完成的步骤列表
     */
    public List<AgentStep> getPendingSteps(List<AgentStep> allSteps, Set<String> completedStepIds)
    {
        List<AgentStep> pending = new ArrayList<>();
        for (AgentStep step : allSteps)
        {
            if (!completedStepIds.contains(step.getStepId()))
            {
                pending.add(step);
            }
        }
        return pending;
    }
}
