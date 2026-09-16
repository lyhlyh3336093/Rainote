<template>
  <div
    ref="dialogRef"
    class="destructive-confirm"
    role="dialog"
    aria-label="破坏性操作确认"
    aria-modal="true"
    tabindex="-1"
    @keydown.tab="handleTab"
  >
    <div class="confirm-header">
      <i class="bi bi-shield-exclamation"></i>
      <span class="confirm-title">破坏性操作需要二次确认</span>
    </div>

    <!-- 回显完整参数 -->
    <div class="confirm-params">
      <div class="param-label">操作：{{ step.operationName }}</div>
      <div class="param-list">
        <div v-for="(val, key) in step.params" :key="key" class="param-row">
          <span class="param-key">{{ key }}</span>
          <span class="param-val">{{ formatValue(val) }}</span>
        </div>
      </div>
    </div>

    <!-- 受影响数量 -->
    <div class="impact-section">
      <template v-if="queryState === 'loading'">
        <a-spin size="small" />
        <span class="impact-loading">正在查询受影响范围…</span>
      </template>
      <template v-else-if="queryState === 'success'">
        <div class="impact-result">
          <i class="bi bi-info-circle"></i>
          <span>受影响实体：<strong>{{ affectedCount }}</strong> 个</span>
          <span v-if="affectedCount > 10" class="impact-warn">（影响范围较大）</span>
        </div>
      </template>
      <template v-else-if="queryState === 'timeout'">
        <div class="impact-timeout">
          <i class="bi bi-exclamation-triangle"></i>
          <span>受影响数量查询超时，无法确认影响范围</span>
        </div>
        <div class="friction-input">
          <label>请输入受影响实体总数以确认：</label>
          <a-input-number
            v-model:value="manualCount"
            placeholder="输入受影响数量"
            :min="0"
            size="small"
          />
        </div>
      </template>
    </div>

    <!-- 升级摩擦（批量破坏性 >5 步或单步级联 >10 实体） -->
    <div v-if="needFriction" class="friction-upgrade">
      <div class="friction-warn">
        <i class="bi bi-shield-lock"></i>
        <span>{{ frictionReason }}</span>
      </div>
      <div class="friction-input">
        <label>请输入受影响实体总数以确认：</label>
        <a-input-number
          v-model:value="frictionCount"
          placeholder="输入受影响实体总数"
          :min="0"
          size="small"
        />
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="confirm-actions">
      <a-button
        type="primary"
        danger
        size="small"
        @click="handleConfirm"
        :disabled="!canConfirm"
      >
        确认执行
      </a-button>
      <a-button size="small" @click="$emit('skip')">
        跳过此步
        <span class="esc-hint" aria-label="Esc=跳过此步">(Esc)</span>
      </a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import type { AgentStep } from '../../api/agent';

const props = defineProps<{
  step: AgentStep;
  /** 批量破坏性步骤总数（用于判断升级摩擦） */
  batchDestructiveCount?: number;
}>();

const emit = defineEmits<{
  confirm: [];
  skip: [];
}>();

type QueryState = 'loading' | 'success' | 'timeout';
const queryState = ref<QueryState>('loading');
const affectedCount = ref(0);
const manualCount = ref<number | null>(null);
const frictionCount = ref<number | null>(null);

const dialogRef = ref<HTMLElement>();
/** 焦点陷阱：记录打开前焦点，用于关闭后恢复（R15b） */
let previousActiveEl: HTMLElement | null = null;
let timeoutTimer: ReturnType<typeof setTimeout> | null = null;
let superTimeoutTimer: ReturnType<typeof setTimeout> | null = null;

/** 获取对话框内所有可聚焦元素 */
function getFocusable(): HTMLElement[] {
  if (!dialogRef.value) return [];
  const selector = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';
  return Array.from(dialogRef.value.querySelectorAll<HTMLElement>(selector));
}

/**
 * 级联查询受影响数量。
 * 后端暂无级联查询 API，从步骤参数推断（如 ids 数组长度）。
 * 5s 超时降级。
 */
onMounted(() => {
  // 焦点陷阱：记录前焦点并聚焦对话框（R15b）
  previousActiveEl = document.activeElement as HTMLElement;
  nextTick(() => {
    const focusable = getFocusable();
    // 等待级联查询结束后聚焦"确认执行"按钮更合理，但初始先聚焦容器避免焦点丢失
    (focusable[0] || dialogRef.value)?.focus();
  });

  // 从步骤参数推断受影响数量
  const params = props.step.params || {};
  let count = 1;
  for (const val of Object.values(params)) {
    if (Array.isArray(val)) {
      count = Math.max(count, val.length);
    } else if (typeof val === 'string' && val.includes(',')) {
      count = Math.max(count, val.split(',').length);
    }
  }
  affectedCount.value = count;

  // 模拟级联查询（5s 超时降级）
  timeoutTimer = setTimeout(() => {
    // 如果后端 API 就绪，此处应调用 API 获取级联数量
    // 目前直接从参数推断，视为查询成功
    queryState.value = 'success';
    // 查询完成后聚焦"确认执行"按钮，便于键盘用户直接确认
    nextTick(() => {
      const focusable = getFocusable();
      const confirmBtn = focusable.find(el => el.textContent?.includes('确认执行'));
      confirmBtn?.focus();
    });
  }, 500);

  // 5s 超时
  superTimeoutTimer = setTimeout(() => {
    if (queryState.value === 'loading') {
      queryState.value = 'timeout';
    }
  }, 5000);

  // Esc 键跳过（R15b）
  window.addEventListener('keydown', handleEsc);
});

onBeforeUnmount(() => {
  if (timeoutTimer) clearTimeout(timeoutTimer);
  if (superTimeoutTimer) clearTimeout(superTimeoutTimer);
  window.removeEventListener('keydown', handleEsc);
  // 恢复打开前的焦点（R15b）
  previousActiveEl?.focus();
});

function handleEsc(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault();
    // Esc=跳过此步（R15b），不取消整个剩余计划
    emit('skip');
  }
}

/** Tab 焦点陷阱：在对话框内循环（R15b） */
function handleTab(e: KeyboardEvent) {
  const focusable = getFocusable();
  if (focusable.length === 0) {
    e.preventDefault();
    dialogRef.value?.focus();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement as HTMLElement;
  if (e.shiftKey) {
    // Shift+Tab：从第一个跳到最后一个
    if (active === first || !dialogRef.value?.contains(active)) {
      e.preventDefault();
      last.focus();
    }
  } else {
    // Tab：从最后一个跳到第一个
    if (active === last) {
      e.preventDefault();
      first.focus();
    }
  }
}

/** 是否需要升级摩擦 */
const needFriction = computed(() => {
  if ((props.batchDestructiveCount || 0) > 5) return true;
  if (affectedCount.value > 10) return true;
  return false;
});

const frictionReason = computed(() => {
  if ((props.batchDestructiveCount || 0) > 5) {
    return `批量破坏性操作（${props.batchDestructiveCount} 步），需确认受影响实体总数`;
  }
  return `单步级联影响 ${affectedCount.value} 个实体（>10），需确认受影响总数`;
});

/** 是否可以确认 */
const canConfirm = computed(() => {
  if (queryState.value === 'loading') return false;
  if (queryState.value === 'timeout') {
    return manualCount.value !== null && manualCount.value >= 0;
  }
  if (needFriction.value) {
    return frictionCount.value === affectedCount.value;
  }
  return true;
});

function formatValue(val: any): string {
  if (typeof val === 'object') return JSON.stringify(val);
  return String(val);
}

function handleConfirm() {
  emit('confirm');
}
</script>

<style lang="scss" scoped>
.destructive-confirm {
  padding: 10px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 6px;
  margin-bottom: 8px;

  &:focus-visible {
    outline: 2px solid #cf1322;
    outline-offset: 2px;
  }

  .confirm-header {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: #cf1322;
    font-weight: 500;
    margin-bottom: 8px;

    i { font-size: 16px; }
  }

  .confirm-params {
    margin-bottom: 8px;

    .param-label {
      font-size: 12px;
      color: #595959;
      margin-bottom: 4px;
    }
    .param-list {
      background: #fff;
      border-radius: 4px;
      padding: 6px 8px;

      .param-row {
        display: flex;
        gap: 8px;
        font-size: 12px;
        padding: 2px 0;

        .param-key {
          color: #8c8c8c;
          min-width: 60px;
        }
        .param-val {
          color: #333;
          word-break: break-all;
        }
      }
    }
  }

  .impact-section {
    margin-bottom: 8px;

    .impact-loading {
      font-size: 12px;
      color: #8c8c8c;
      margin-left: 6px;
    }
    .impact-result {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: #595959;

      .impact-warn { color: #fa8c16; }
    }
    .impact-timeout {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: #cf1322;
      margin-bottom: 6px;
    }
    .friction-input {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      margin-top: 6px;
    }
  }

  .friction-upgrade {
    margin-bottom: 8px;
    padding: 8px;
    background: #fff;
    border-radius: 4px;

    .friction-warn {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: #cf1322;
      margin-bottom: 6px;
    }
    .friction-input {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
    }
  }

  .confirm-actions {
    display: flex;
    gap: 8px;

    .esc-hint {
      font-size: 10px;
      color: #bfbfbf;
      margin-left: 2px;
    }
  }
}

/* 窄屏渲染（<768px）：全屏展示 */
@media (max-width: 767px) {
  .destructive-confirm {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 2000;
    border-radius: 0;
    border: none;
    display: flex;
    flex-direction: column;
    padding: 16px;

    .confirm-params {
      flex: 1;
      overflow-y: auto;
    }
    .confirm-actions {
      padding: 12px 0;
      padding-bottom: calc(12px + env(safe-area-inset-bottom, 0px));
      border-top: 1px solid #ffccc7;
    }
  }
}
</style>
