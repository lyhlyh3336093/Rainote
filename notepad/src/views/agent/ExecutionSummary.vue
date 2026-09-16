<template>
  <div class="execution-summary" :aria-live="'assertive'">
    <div class="summary-header">
      <i :class="isCompleted ? 'bi bi-check-circle-fill success' : 'bi bi-exclamation-circle-fill warn'"></i>
      <span class="summary-title">{{ isCompleted ? '执行完成' : '执行已中断' }}</span>
    </div>

    <div class="summary-counts">
      <div class="count-card success">
        <span class="count-number">{{ successCount }}</span>
        <span class="count-label">成功</span>
      </div>
      <div class="count-card failed" v-if="failedCount > 0">
        <span class="count-number">{{ failedCount }}</span>
        <span class="count-label">失败</span>
      </div>
      <div class="count-card skipped" v-if="skippedCount > 0">
        <span class="count-number">{{ skippedCount }}</span>
        <span class="count-label">跳过</span>
      </div>
    </div>

    <div class="summary-actions">
      <a-button size="small" type="link" @click="$emit('view-audit')">查看审计记录</a-button>
      <a-button v-if="failedCount > 0" size="small" @click="$emit('retry-failed')">重试失败步骤</a-button>
      <a-button v-if="failedCount > 0" size="small" @click="$emit('cancel-close')">取消并关闭</a-button>
      <a-button v-else size="small" @click="$emit('close')">关闭</a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { useAgentStore } from '../../stores/agent';

defineProps<{
  isCompleted: boolean;
}>();

defineEmits<{
  'retry-failed': [];
  'view-audit': [];
  'cancel-close': [];
  'close': [];
}>();

const store = useAgentStore();

const successCount = computed(() => store.stepLogs.filter(l => l.status === 'success').length);
const failedCount = computed(() => store.stepLogs.filter(l => l.status === 'failed').length);
const skippedCount = computed(() => store.stepLogs.filter(l => l.status === 'skipped').length);
</script>

<style lang="scss" scoped>
.execution-summary {
  margin-top: 10px;
  padding: 12px;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
  border-radius: 6px;

  .summary-header {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
    font-size: 14px;
    margin-bottom: 10px;

    .success { color: #52c41a; }
    .warn { color: #faad14; }
  }

  .summary-counts {
    display: flex;
    gap: 10px;
    margin-bottom: 10px;

    .count-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 8px 16px;
      border-radius: 6px;
      min-width: 60px;

      &.success { background: #f6ffed; }
      &.failed { background: #fff2f0; }
      &.skipped { background: #f5f5f5; }

      .count-number {
        font-size: 24px;
        font-weight: 700;
        line-height: 1;
      }
      &.success .count-number { color: #52c41a; }
      &.failed .count-number { color: #ff4d4f; }
      &.skipped .count-number { color: #8c8c8c; }

      .count-label {
        font-size: 11px;
        color: #8c8c8c;
        margin-top: 2px;
      }
    }
  }

  .summary-actions {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
  }
}
</style>
