<template>
  <div class="execution-progress" aria-live="polite">
    <!-- SSE 断连指示器（R20 连接异常态） -->
    <div v-if="store.sseDisconnected" class="sse-disconnected">
      <a-spin size="small" />
      <span>连接中断，正在重连…（第 {{ store.sseRetryCount }} 次）</span>
    </div>

    <!-- 陈旧计划警告（R16） -->
    <div v-if="hasStaleWarning" class="stale-warning">
      <i class="bi bi-exclamation-triangle"></i>
      <span>计划可能已陈旧</span>
      <a-button size="small" @click="$emit('regenerate')">重新生成计划</a-button>
      <a-button size="small" type="primary" @click="dismissStale">继续执行剩余步骤</a-button>
    </div>

    <!-- 暂停态横幅（R15c） -->
    <div v-if="isPaused" class="pause-banner">
      <i class="bi bi-pause-circle-fill"></i>
      <span>已暂停</span>
      <a-button type="primary" size="small" @click="handleResume">继续执行</a-button>
      <a-button size="small" danger @click="handleCancel">取消剩余计划</a-button>
    </div>

    <!-- 破坏性步骤待确认（R17，使用 DestructiveConfirm 组件） -->
    <DestructiveConfirm
      v-if="store.hasPendingDestructive && !store.isCompleted && pendingStep"
      :step="pendingStep"
      :batch-destructive-count="batchDestructiveCount"
      @confirm="handleConfirmDestructive"
      @skip="handleSkipDestructive"
    />

    <!-- 步骤列表 -->
    <div class="progress-steps">
      <StepItem
        v-for="step in plan.steps"
        :key="step.stepId"
        :step="step"
        :step-log="getStepLog(step.stepId)"
      />
    </div>

    <!-- 执行汇总（R20，使用 ExecutionSummary 组件） -->
    <ExecutionSummary
      v-if="store.isCompleted || store.isInterrupted"
      :is-completed="store.isCompleted"
      @retry-failed="handleRetryFailed"
      @view-audit="$emit('view-audit')"
      @cancel-close="handleCancelClose"
      @close="$emit('close')"
    />
  </div>
</template>

<script lang="ts" setup>
import { ref, computed } from 'vue';
import { useAgentStore } from '../../stores/agent';
import type { AgentStep } from '../../api/agent';
import StepItem from './StepItem.vue';
import DestructiveConfirm from './DestructiveConfirm.vue';
import ExecutionSummary from './ExecutionSummary.vue';

const props = defineProps<{
  plan: { steps: AgentStep[] };
}>();

defineEmits<{
  'confirm-step': [stepId: string];
  'skip-step': [stepId: string];
  'retry-failed': [];
  'view-audit': [];
  'close': [];
  'regenerate': [];
}>();

const store = useAgentStore();
const staleDismissed = ref(false);

/** 是否暂停态 */
const isPaused = computed(() =>
  !store.isCompleted && !store.isInterrupted && !store.hasPendingDestructive
  && !store.isExecuting && store.stepLogs.length > 0
);

/** 待确认的破坏性步骤 */
const pendingStep = computed(() => {
  const pending = store.stepLogs.find(l => l.status === 'pending_confirm');
  if (!pending) return null;
  return props.plan.steps.find(s => s.stepId === pending.stepId) || null;
});

/** 批量破坏性步骤总数 */
const batchDestructiveCount = computed(() =>
  props.plan.steps.filter(s => s.destructive).length,
);

/** 陈旧计划警告（R16）：有失败步骤且未恢复 */
const hasStaleWarning = computed(() =>
  !staleDismissed.value
  && store.stepLogs.some(l => l.status === 'failed')
  && !store.isCompleted
  && !store.isInterrupted
);

function getStepLog(stepId: string) {
  return store.stepLogs.find(l => l.stepId === stepId) || null;
}

function dismissStale() {
  staleDismissed.value = true;
}

async function handleConfirmDestructive() {
  if (pendingStep.value) {
    await store.confirmDestructiveStep(pendingStep.value.stepId);
  }
}

async function handleSkipDestructive() {
  if (pendingStep.value) {
    await store.skipDestructiveStep(pendingStep.value.stepId);
  }
}

async function handleRetryFailed() {
  const failed = store.stepLogs.find(l => l.status === 'failed');
  if (failed) await store.retryFailedStep(failed.stepId);
}

async function handleResume() {
  await store.resumePlan();
}

async function handleCancel() {
  await store.cancelPlan();
}

async function handleCancelClose() {
  await store.cancelPlan();
}
</script>

<style lang="scss" scoped>
.execution-progress {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 12px;
  background: #fff;

  .sse-disconnected {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    background: #fffbe6;
    border: 1px solid #ffe58f;
    border-radius: 4px;
    margin-bottom: 8px;
    font-size: 12px;
    color: #ad6800;
  }

  .stale-warning {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    background: #fff7e6;
    border: 1px solid #ffd591;
    border-radius: 4px;
    margin-bottom: 8px;
    font-size: 12px;
    color: #ad6800;
    flex-wrap: wrap;

    i { font-size: 14px; color: #fa8c16; }
  }

  .pause-banner {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    background: #e6f4ff;
    border: 1px solid #91caff;
    border-radius: 4px;
    margin-bottom: 8px;
    font-size: 13px;

    i { color: #1677ff; font-size: 16px; }
  }

  .progress-steps {
    max-height: 300px;
    overflow-y: auto;
  }
}
</style>
