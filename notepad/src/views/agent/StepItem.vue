<template>
  <div class="step-item" :class="statusClass">
    <!-- 状态图标 -->
    <div class="step-icon" :aria-label="statusLabel">
      <span v-if="status === 'executing'" class="spinner"></span>
      <i v-else :class="statusIcon"></i>
    </div>

    <!-- 步骤内容 -->
    <div class="step-content">
      <div class="step-header">
        <span class="step-name">{{ step.operationName }}</span>
        <a-tag v-if="step.destructive" color="red" :bordered="false">破坏性</a-tag>
        <a-tag :color="typeColor" :bordered="false">{{ typeLabel }}</a-tag>
        <span v-if="stepLog" class="step-status-text">{{ statusLabel }}</span>
      </div>
      <div class="step-params">
        <span v-for="(val, key) in displayParams" :key="key" class="param-chip">
          <span class="param-key">{{ key }}</span>: <span class="param-val">{{ val }}</span>
        </span>
      </div>
      <div v-if="stepLog?.errorMessage" class="step-error">
        <i class="bi bi-exclamation-triangle"></i> {{ stepLog.errorMessage }}
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import type { AgentStep, AgentAuditStepLog } from '../../api/agent';

const props = defineProps<{
  step: AgentStep;
  stepLog?: AgentAuditStepLog | null;
}>();

/** 步骤状态：未执行时为 null */
const status = computed(() => props.stepLog?.status || null);

const statusClass = computed(() => {
  if (!status.value) return 'pending';
  return status.value;
});

const statusIcon = computed(() => {
  switch (status.value) {
    case 'success': return 'bi bi-check-circle-fill';
    case 'failed': return 'bi bi-x-circle-fill';
    case 'skipped': return 'bi bi-dash-circle-fill';
    case 'blocked': return 'bi bi-lock-fill';
    case 'pending_confirm': return 'bi bi-exclamation-circle-fill';
    default: return 'bi bi-circle';
  }
});

const statusLabel = computed(() => {
  switch (status.value) {
    case 'executing': return '执行中';
    case 'success': return '成功';
    case 'failed': return '失败';
    case 'skipped': return '已跳过';
    case 'blocked': return '已阻塞';
    case 'pending_confirm': return '待确认';
    default: return '待执行';
  }
});

const typeLabel = computed(() => {
  const map: Record<string, string> = {
    query: '查询', create: '创建', update: '更新', delete: '删除',
  };
  return map[props.step.operationType] || props.step.operationType;
});

const typeColor = computed(() => {
  const map: Record<string, string> = {
    query: 'blue', create: 'green', update: 'orange', delete: 'red',
  };
  return map[props.step.operationType] || 'default';
});

/** 显示参数（截断过长的值） */
const displayParams = computed(() => {
  const result: Record<string, string> = {};
  for (const [key, val] of Object.entries(props.step.params || {})) {
    const str = typeof val === 'object' ? JSON.stringify(val) : String(val);
    result[key] = str.length > 50 ? str.slice(0, 50) + '…' : str;
  }
  return result;
});
</script>

<style lang="scss" scoped>
.step-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  transition: background 0.2s;

  &:hover { background: rgba(0, 0, 0, 0.03); }

  &.pending .step-icon { color: #bfbfbf; }
  &.executing .step-icon { color: #1677ff; }
  &.success .step-icon { color: #52c41a; }
  &.failed .step-icon { color: #ff4d4f; }
  &.skipped .step-icon { color: #bfbfbf; }
  &.blocked .step-icon { color: #fa8c16; }
  &.pending_confirm .step-icon { color: #fa8c16; }
  &.success, &.skipped { opacity: 0.65; }

  .step-icon {
    flex-shrink: 0;
    font-size: 16px;
    line-height: 22px;
    width: 20px;
    text-align: center;

    .spinner {
      display: inline-block;
      width: 14px;
      height: 14px;
      border: 2px solid currentColor;
      border-top-color: transparent;
      border-radius: 50%;
      animation: spin 0.6s linear infinite;
    }
  }

  .step-content {
    flex: 1;
    min-width: 0;

    .step-header {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;

      .step-name {
        font-weight: 500;
        font-size: 13px;
      }
      .step-status-text {
        font-size: 12px;
        color: #8c8c8c;
      }
    }

    .step-params {
      margin-top: 4px;
      display: flex;
      flex-wrap: wrap;
      gap: 4px 8px;

      .param-chip {
        font-size: 12px;
        color: #595959;
        background: rgba(0,0,0,0.04);
        padding: 1px 6px;
        border-radius: 3px;

        .param-key { color: #8c8c8c; }
      }
    }

    .step-error {
      margin-top: 4px;
      font-size: 12px;
      color: #ff4d4f;
    }
  }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
