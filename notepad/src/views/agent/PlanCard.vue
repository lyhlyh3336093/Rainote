<template>
  <div
    class="plan-card"
    :aria-live="store.isExecuting ? 'polite' : undefined"
    @keydown.esc="handleEsc"
  >
    <div class="plan-header">
      <i class="bi bi-list-task"></i>
      <span class="plan-title">操作计划</span>
      <span class="step-count">{{ plan.steps.length }} 步</span>
      <a-tag v-if="hasDestructive" color="red" :bordered="false">
        含 {{ destructiveCount }} 个破坏性操作
      </a-tag>
      <a-tag v-if="invalidCount > 0" color="orange" :bordered="false">
        {{ invalidCount }} 处参数无效
      </a-tag>
    </div>

    <!-- 汇总态（超 20 步） -->
    <div v-if="plan.needsSummary && !showAllDetails" class="plan-summary">
      <div class="summary-row" v-for="item in summaryCounts" :key="item.type">
        <a-tag :color="item.color" :bordered="false">{{ item.label }}</a-tag>
        <span>{{ item.count }} 步</span>
      </div>
      <div class="summary-destructive" v-if="destructiveSteps.length">
        <span class="summary-label">破坏性步骤：</span>
        <StepItem
          v-for="step in destructiveSteps"
          :key="step.stepId"
          :step="step"
        />
      </div>
      <a-button type="link" size="small" @click="showAllDetails = true">
        展开全部明细
      </a-button>
    </div>

    <!-- 完整步骤列表（含编辑功能 R14） -->
    <div v-else class="step-list">
      <template v-for="(step, idx) in plan.steps" :key="step.stepId">
        <div class="step-wrapper" :class="{ 'param-editing': editingStepId === step.stepId }">
          <!-- 步骤序号 + 编辑手柄 -->
          <div class="step-toolbar" v-if="editable && !store.isExecuting">
            <span class="step-index">{{ idx + 1 }}</span>
            <button
              class="tool-btn"
              @click="store.moveStep(step.stepId, 'up')"
              :disabled="idx === 0"
              aria-label="上移步骤"
              title="上移"
            ><i class="bi bi-arrow-up"></i></button>
            <button
              class="tool-btn"
              @click="store.moveStep(step.stepId, 'down')"
              :disabled="idx === plan.steps.length - 1"
              aria-label="下移步骤"
              title="下移"
            ><i class="bi bi-arrow-down"></i></button>
            <button
              class="tool-btn danger"
              @click="handleDelete(step)"
              :aria-label="`删除步骤 ${step.operationName}`"
              title="删除"
            ><i class="bi bi-trash"></i></button>
            <button
              class="tool-btn"
              @click="toggleParamEdit(step.stepId)"
              aria-label="编辑参数"
              title="编辑参数"
            ><i class="bi bi-pencil"></i></button>
          </div>

          <!-- 步骤项 -->
          <StepItem
            :step="step"
            :step-log="getStepLog(step.stepId)"
          />

          <!-- 行内参数编辑表单（R14） -->
          <div v-if="editingStepId === step.stepId" class="param-edit-form">
            <div v-for="(val, key) in step.params" :key="key" class="param-edit-row">
              <label class="param-edit-label">{{ key }}</label>
              <a-input
                :value="formatParamValue(val)"
                @change="(e: any) => handleParamChange(step.stepId, key, e.target.value)"
                @blur="validateParam(step.stepId, key)"
                :status="paramErrors[`${step.stepId}.${key}`] ? 'error' : ''"
                size="small"
              />
              <span v-if="paramErrors[`${step.stepId}.${key}`]" class="param-error">
                {{ paramErrors[`${step.stepId}.${key}`] }}
              </span>
            </div>
            <a-button size="small" type="link" @click="editingStepId = ''">完成</a-button>
          </div>
        </div>
      </template>
      <a-button
        v-if="plan.needsSummary"
        type="link"
        size="small"
        @click="showAllDetails = false"
      >
        收起明细
      </a-button>
    </div>

    <!-- 删除二次确认（破坏性步骤） -->
    <div v-if="deleteConfirmStep" class="delete-confirm">
      <i class="bi bi-exclamation-triangle"></i>
      <span>确认删除破坏性步骤「{{ deleteConfirmStep.operationName }}」？</span>
      <a-button size="small" danger @click="confirmDelete">确认删除</a-button>
      <a-button size="small" @click="deleteConfirmStep = null">取消</a-button>
    </div>

    <!-- 操作按钮 -->
    <div v-if="showActions" class="plan-actions">
      <a-button
        type="primary"
        @click="$emit('confirm')"
        :loading="loading"
        :disabled="invalidCount > 0 || plan.steps.length === 0"
      >
        <span v-if="invalidCount > 0">{{ invalidCount }} 处参数无效</span>
        <span v-else>确认执行</span>
      </a-button>
      <a-button @click="$emit('cancel')" aria-label="取消计划 (Esc)">取消 <span class="esc-hint">(Esc)</span></a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, computed, reactive } from 'vue';
import { useAgentStore } from '../../stores/agent';
import type { AgentStep, AgentAuditStepLog } from '../../api/agent';
import StepItem from './StepItem.vue';

const props = withDefaults(defineProps<{
  plan: { steps: AgentStep[]; needsSummary: boolean };
  stepLogs?: AgentAuditStepLog[];
  showActions?: boolean;
  loading?: boolean;
  /** 是否允许编辑（R14），默认 true */
  editable?: boolean;
}>(), {
  editable: true,
});

const emit = defineEmits<{
  confirm: [];
  cancel: [];
}>();

const store = useAgentStore();
const showAllDetails = ref(false);
const editingStepId = ref('');
const deleteConfirmStep = ref<AgentStep | null>(null);
const paramErrors = reactive<Record<string, string>>({});

const hasDestructive = computed(() =>
  props.plan.steps.some(s => s.destructive),
);

const destructiveCount = computed(() =>
  props.plan.steps.filter(s => s.destructive).length,
);

const destructiveSteps = computed(() =>
  props.plan.steps.filter(s => s.destructive),
);

const summaryCounts = computed(() => {
  const map: Record<string, { label: string; color: string }> = {
    create: { label: '创建', color: 'green' },
    update: { label: '更新', color: 'orange' },
    delete: { label: '删除', color: 'red' },
    query: { label: '查询', color: 'blue' },
  };
  return Object.entries(map).map(([type, info]) => ({
    type,
    ...info,
    count: props.plan.steps.filter(s => s.operationType === type).length,
  })).filter(item => item.count > 0);
});

/** 无效参数计数（R14 实时校验） */
const invalidCount = computed(() => {
  let count = 0;
  for (const step of props.plan.steps) {
    const violations = store.validateStep(step.stepId);
    count += violations.length;
  }
  return count;
});

/** 获取步骤的执行日志 */
function getStepLog(stepId: string): AgentAuditStepLog | null {
  return props.stepLogs?.find(l => l.stepId === stepId) || null;
}

/** 切换参数编辑模式 */
function toggleParamEdit(stepId: string) {
  editingStepId.value = editingStepId.value === stepId ? '' : stepId;
}

/** 格式化参数值用于显示 */
function formatParamValue(val: any): string {
  if (typeof val === 'object') return JSON.stringify(val);
  return String(val);
}

/** 处理参数变更 */
function handleParamChange(stepId: string, paramKey: string, value: string) {
  // 尝试解析为数字
  let parsed: any = value;
  if (/^\d+$/.test(value)) parsed = Number(value);
  else if (value.startsWith('{') || value.startsWith('[')) {
    try { parsed = JSON.parse(value); } catch { /* keep string */ }
  }
  store.updateStepParam(stepId, paramKey, parsed);
}

/** 校验参数 */
function validateParam(stepId: string, paramKey: string) {
  const errorKey = `${stepId}.${paramKey}`;
  const step = props.plan.steps.find(s => s.stepId === stepId);
  if (!step) return;
  const val = step.params[paramKey];
  if (val === null || val === undefined || val === '') {
    paramErrors[errorKey] = `${paramKey} 不能为空`;
  } else {
    delete paramErrors[errorKey];
  }
}

/** 处理删除步骤（破坏性步骤二次确认） */
function handleDelete(step: AgentStep) {
  if (step.destructive) {
    // 破坏性步骤需要二次确认
    deleteConfirmStep.value = step;
  } else {
    store.deleteStep(step.stepId);
  }
}

/** 确认删除 */
function confirmDelete() {
  if (deleteConfirmStep.value) {
    store.deleteStep(deleteConfirmStep.value.stepId);
    deleteConfirmStep.value = null;
  }
}

/**
 * Esc=取消计划（R15b）。
 * 仅在待确认态（非执行中、显示操作按钮）生效；执行中 Esc 不响应以免误触中止。
 */
function handleEsc(e: KeyboardEvent) {
  // 行内参数编辑或删除二次确认激活时，Esc 仅退出该子态，不取消整个计划
  if (editingStepId.value || deleteConfirmStep.value) {
    editingStepId.value = '';
    deleteConfirmStep.value = null;
    return;
  }
  if (props.showActions && !store.isExecuting && !props.loading) {
    e.preventDefault();
    emit('cancel');
  }
}
</script>

<style lang="scss" scoped>
.plan-card {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 12px;
  background: #fafafa;

  .plan-header {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 8px;
    flex-wrap: wrap;

    .plan-title { font-weight: 600; font-size: 14px; }
    .step-count { font-size: 12px; color: #8c8c8c; }
    i.bi-list-task { color: #1677ff; }
  }

  .plan-summary {
    .summary-row {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 0;
      font-size: 13px;
    }
    .summary-destructive {
      margin: 8px 0;
      padding: 8px;
      background: #fff2f0;
      border-radius: 4px;

      .summary-label {
        font-size: 12px;
        color: #ff4d4f;
        font-weight: 500;
      }
    }
  }

  .step-list {
    max-height: 360px;
    overflow-y: auto;

    .step-wrapper {
      position: relative;
      border-radius: 6px;
      transition: background 0.2s;

      &.param-editing {
        background: #fff;
        box-shadow: 0 0 0 1px #1677ff inset;
      }

      .step-toolbar {
        display: flex;
        align-items: center;
        gap: 2px;
        padding: 2px 4px;

        .step-index {
          font-size: 11px;
          color: #bfbfbf;
          min-width: 18px;
          text-align: center;
        }
        .tool-btn {
          border: none;
          background: transparent;
          cursor: pointer;
          padding: 2px 4px;
          font-size: 12px;
          color: #8c8c8c;
          border-radius: 3px;
          transition: all 0.2s;

          &:hover:not(:disabled) {
            background: #e6f4ff;
            color: #1677ff;
          }
          &.danger:hover:not(:disabled) {
            background: #fff2f0;
            color: #ff4d4f;
          }
          &:disabled {
            opacity: 0.3;
            cursor: not-allowed;
          }
        }
      }

      .param-edit-form {
        padding: 8px 10px;
        background: #fff;
        border-radius: 4px;
        margin: 4px 0;

        .param-edit-row {
          display: flex;
          align-items: center;
          gap: 6px;
          margin-bottom: 4px;

          .param-edit-label {
            font-size: 12px;
            color: #595959;
            width: 80px;
            flex-shrink: 0;
          }
          .param-error {
            font-size: 11px;
            color: #ff4d4f;
          }
        }
      }
    }
  }

  .delete-confirm {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    background: #fff2f0;
    border: 1px solid #ffccc7;
    border-radius: 4px;
    margin: 8px 0;
    font-size: 12px;
    color: #cf1322;

    i { font-size: 14px; }
  }

  .plan-actions {
    display: flex;
    gap: 8px;
    margin-top: 10px;
    padding-top: 10px;
    border-top: 1px solid #f0f0f0;

    .esc-hint {
      font-size: 10px;
      color: #bfbfbf;
      margin-left: 2px;
    }
  }
}
</style>
