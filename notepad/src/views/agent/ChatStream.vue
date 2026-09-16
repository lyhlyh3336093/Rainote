<template>
  <div class="chat-stream">
    <!-- 上下文指示器与编辑器（R5/R7） -->
    <ExecutionContext />

    <!-- 对话回复独立 live region（R15b：与执行进度 live region 分离，避免互相打断） -->
    <div class="sr-only" aria-live="polite" aria-atomic="true">{{ agentAnnouncement }}</div>

    <!-- 排队指示器（R17a） -->
    <div v-if="store.queuedMessages.length > 0" class="queue-indicator">
      <a-badge :count="store.queuedMessages.length" />
      <span>排队中</span>
      <a-button type="link" size="small" @click="showQueue = !showQueue">
        {{ showQueue ? '收起' : '展开' }}
      </a-button>
      <div v-if="showQueue" class="queue-dropdown">
        <div v-for="(msg, idx) in store.queuedMessages" :key="idx" class="queue-item">
          <span class="queue-msg" :title="msg">{{ msg }}</span>
          <button class="queue-action" title="编辑" aria-label="编辑排队消息"
                  @click="store.editQueuedMessage(idx); showQueue = false">
            <i class="bi bi-pencil-square"></i>
          </button>
          <button class="queue-action" title="移除" aria-label="移除排队消息"
                  @click="store.removeQueuedMessage(idx)">
            <i class="bi bi-x-lg"></i>
          </button>
        </div>
      </div>
    </div>

    <!-- 消息列表 -->
    <div class="message-list" ref="messageListRef">
      <!-- 空状态 -->
      <div v-if="store.entries.length === 0 && !store.isWaiting" class="empty-state">
        <p class="empty-title">Agent 会感知当前页面上下文</p>
        <div class="examples">
          <div
            v-for="example in examples"
            :key="example"
            class="example-item"
            role="button"
            tabindex="0"
            @click="store.sendMessage(example)"
            @keydown.enter="store.sendMessage(example)"
          >
            <i class="bi bi-lightbulb"></i> {{ example }}
          </div>
        </div>
      </div>

      <!-- 条目列表 -->
      <template v-for="entry in store.entries" :key="entry.id">
        <!-- 恢复卡片（R20b 刷新恢复） -->
        <div v-if="entry.type === 'resume'" class="entry agent-entry">
          <div v-if="!entry.ignored" class="resume-card">
            <div class="resume-header">
              <i class="bi bi-arrow-clockwise"></i>
              <span>上次执行未完成</span>
            </div>
            <div class="resume-summary">{{ entry.content }}</div>
            <div v-if="entry.plan" class="resume-steps">
              {{ entry.plan.steps?.length || 0 }} 步计划
            </div>
            <div class="resume-actions">
              <a-button type="primary" size="small" @click="store.resumeIncompletePlan(entry.auditLogId!)">
                恢复执行
              </a-button>
              <a-button size="small" @click="store.ignoreResumeEntry(entry.id)">忽略</a-button>
            </div>
          </div>
          <div
            v-else
            class="resume-collapsed"
            role="button"
            tabindex="0"
            aria-label="展开已忽略的未完成计划"
            @click="entry.ignored = false"
            @keydown.enter="entry.ignored = false"
          >
            <i class="bi bi-clock-history"></i>
            <span>已忽略的未完成计划：{{ entry.content }}</span>
          </div>
        </div>

        <!-- 用户消息 -->
        <div v-if="entry.type === 'user'" class="entry user-entry">
          <div class="entry-bubble user-bubble">{{ entry.content }}</div>
        </div>

        <!-- Loading -->
        <div v-else-if="entry.type === 'loading'" class="entry agent-entry">
          <div class="entry-bubble loading-bubble">
            <a-spin size="small" />
            <span>{{ entry.content }}</span>
            <span v-if="entry.retryCount" class="retry-count">第 {{ entry.retryCount }}/3 次尝试</span>
          </div>
        </div>

        <!-- 计划卡片 -->
        <div v-else-if="entry.type === 'plan'" class="entry agent-entry">
          <!-- 执行中或已完成：显示执行进度 -->
          <ExecutionProgress
            v-if="store.auditLogId && (store.isExecuting || store.isCompleted || store.isInterrupted || store.stepLogs.length > 0)"
            :plan="entry.plan"
            @retry-failed="handleRetryFailed"
            @view-audit="handleViewAudit"
            @close="handleCloseExecution"
            @regenerate="handleRegenerate"
          />
          <!-- 待确认：显示计划卡片 -->
          <PlanCard
            v-else
            :plan="entry.plan"
            :show-actions="true"
            :loading="store.isExecuting"
            @confirm="handleConfirmPlan"
            @cancel="handleCancelPlan"
          />
        </div>

        <!-- 查询结果（R9 查询路径，大数字/表格/文本三种渲染） -->
        <div v-else-if="entry.type === 'query'" class="entry agent-entry">
          <div class="entry-bubble agent-bubble query-result">
            <!-- 大数字卡片（标量计数） -->
            <div v-if="isScalarNumber(entry.queryResult)" class="query-number-card">
              <span class="query-number">{{ entry.queryResult }}</span>
            </div>
            <!-- 表格渲染（JSON 数组） -->
            <div v-else-if="isTableData(entry.queryResult)" class="query-table-wrapper">
              <a-table
                :data-source="parseTableData(entry.queryResult)"
                :pagination="false"
                size="small"
                :scroll="{ y: 400 }"
                :row-key="(_, idx) => idx"
              />
              <div v-if="parseTableData(entry.queryResult).length > 20" class="table-more">
                共 {{ parseTableData(entry.queryResult).length }} 条，前 20 条
              </div>
            </div>
            <!-- 文本 -->
            <div v-else class="query-content">
              <i class="bi bi-search query-icon"></i>
              {{ entry.queryResult || entry.content }}
            </div>
          </div>
        </div>

        <!-- 澄清卡片（R11，超 3 轮转提示） -->
        <div v-else-if="entry.type === 'clarify'" class="entry agent-entry">
          <div class="clarify-card">
            <template v-if="entry.clarifyRound && entry.clarifyRound > 3">
              <div class="clarify-exceed">
                <i class="bi bi-info-circle"></i>
                请尝试更具体地描述你的目标
              </div>
            </template>
            <template v-else>
              <div class="clarify-question">{{ entry.clarifyingQuestion }}</div>
              <div class="clarify-round" v-if="entry.clarifyRound">
                澄清轮次 {{ entry.clarifyRound }}/3
              </div>
              <a-textarea
                v-model:value="clarifyAnswer"
                placeholder="输入你的回答…"
                :auto-size="{ minRows: 1, maxRows: 3 }"
                @keydown.enter.exact.prevent="handleClarifyAnswer"
              />
              <a-button type="primary" size="small" @click="handleClarifyAnswer">回答</a-button>
            </template>
          </div>
        </div>

        <!-- 错误条目 -->
        <div v-else-if="entry.type === 'error'" class="entry agent-entry">
          <div class="entry-bubble error-bubble">
            <i class="bi bi-exclamation-triangle"></i>
            <span>{{ entry.content }}</span>
            <a-button v-if="entry.userInput" type="link" size="small" @click="store.retryLastMessage(entry)">
              重试
            </a-button>
          </div>
        </div>
      </template>
    </div>

    <!-- PII 提示 -->
    <div class="pii-disclosure">
      请勿在对话中提交敏感个人信息（如身份证号、银行卡号、密码等）。
    </div>

    <!-- 数据表上下文缺失提示：有 noteId 但无 dwtableId 时提示用户 -->
    <div v-if="showDwtableHint" class="dwtable-hint">
      <i class="bi bi-exclamation-triangle"></i>
      <span>当前未选中数据表，数据表相关操作（新增记录等）请先打开数据表页面</span>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <a-textarea
        v-model:value="store.inputText"
        :placeholder="inputPlaceholder"
        :auto-size="{ minRows: 1, maxRows: 4 }"
        @keydown.enter.exact.prevent="handleSend"
        :disabled="store.isWaiting || store.isExecuting"
      />
      <a-button
        type="primary"
        @click="handleSend"
        :disabled="!store.inputText.trim() || store.isWaiting || store.isExecuting"
      >
        发送
      </a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, watch, nextTick, computed } from 'vue';
import { useAgentStore } from '../../stores/agent';
import PlanCard from './PlanCard.vue';
import ExecutionProgress from './ExecutionProgress.vue';
import ExecutionContext from './ExecutionContext.vue';

const store = useAgentStore();
const messageListRef = ref<HTMLElement>();
const clarifyAnswer = ref('');
const showQueue = ref(false);

const examples = computed(() => {
  if (store.hasContext) {
    return [
      '查看当前笔记的内容',
      '在当前表格中新增一列"状态"',
      '删除当前选中的记录',
      '查询这个表格有多少条数据',
    ];
  }
  return [
    '创建一个叫"项目计划"的笔记',
    '创建一个多维表',
    '查看我最近的笔记',
  ];
});

const inputPlaceholder = computed(() => {
  if (!store.hasContext) {
    return '请先打开笔记或表格，或指定操作目标…';
  }
  return '输入你的需求，Enter 发送…';
});

/**
 * 数据表上下文缺失提示：有 noteId 但无 dwtableId 时显示。
 * 引导用户先打开数据表页面，再执行"新增记录"等需要 dwtableId 的操作。
 */
const showDwtableHint = computed(() => {
  return store.context.noteId !== null && store.context.dwtableId === null;
});

/**
 * 对话回复播报文本（R15b 独立 live region）。
 * 取最后一条 agent 侧条目（plan/query/clarify/error/loading）生成简短播报，
 * 与 ExecutionProgress 的 aria-live 分离，避免执行进度打断回复播报。
 */
const agentAnnouncement = computed(() => {
  for (let i = store.entries.length - 1; i >= 0; i--) {
    const e = store.entries[i];
    if (e.type === 'user' || e.type === 'resume') continue;
    if (e.type === 'plan') return `已生成操作计划，共 ${e.plan?.steps?.length || 0} 步`;
    if (e.type === 'query') return `查询完成：${e.queryResult || e.content}`;
    if (e.type === 'clarify') return `需要澄清：${e.clarifyingQuestion || e.content}`;
    if (e.type === 'error') return `处理失败：${e.content}`;
    if (e.type === 'loading') return 'AI 思考中';
  }
  return '';
});

function handleSend() {
  store.sendMessage();
}

function handleClarifyAnswer() {
  if (clarifyAnswer.value.trim()) {
    store.sendMessage(clarifyAnswer.value.trim());
    clarifyAnswer.value = '';
  }
}

async function handleConfirmPlan() {
  await store.confirmPlan();
}

function handleCancelPlan() {
  store.currentPlan = null;
}

async function handleRetryFailed() {
  const failed = store.stepLogs.find(l => l.status === 'failed');
  if (failed) await store.retryFailedStep(failed.stepId);
}

/** R16 重新生成计划：将最后一条用户消息重新发送 */
function handleRegenerate() {
  const lastUserEntry = store.entries.filter(e => e.type === 'user').pop();
  if (lastUserEntry?.userInput) {
    store.resetExecution();
    store.sendMessage(lastUserEntry.userInput);
  }
}

function handleViewAudit() {
  // 跳转到审计记录（可后续实现）
  if (store.auditLogId) {
    store.loadHistory();
  }
}

function handleCloseExecution() {
  store.reset();
}

// 消息变化时滚动到底部
watch(
  () => store.entries.length,
  () => {
    nextTick(() => {
      if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
      }
    });
  },
);

// ===== 查询结果解析（R9 查询路径） =====

/** 判断是否为标量数字 */
function isScalarNumber(result?: string): boolean {
  if (!result) return false;
  const trimmed = result.trim();
  return /^\d+$/.test(trimmed);
}

/** 判断是否为表格数据（JSON 数组） */
function isTableData(result?: string): boolean {
  if (!result) return false;
  const trimmed = result.trim();
  if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) return false;
  try {
    const parsed = JSON.parse(trimmed);
    return Array.isArray(parsed) && parsed.length > 0 && typeof parsed[0] === 'object';
  } catch {
    return false;
  }
}

/** 解析表格数据（最多 20 行） */
function parseTableData(result?: string): Record<string, any>[] {
  if (!result) return [];
  try {
    const parsed = JSON.parse(result.trim());
    if (Array.isArray(parsed)) {
      return parsed.slice(0, 20);
    }
  } catch { /* ignore */ }
  return [];
}
</script>

<style lang="scss" scoped>
/* 屏幕阅读器专用：视觉隐藏但可被 SR 读取（R15b） */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.chat-stream {
  display: flex;
  flex-direction: column;
  height: 100%;

  .context-bar {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 12px;
    background: #f5f5f5;
    font-size: 12px;
    color: #8c8c8c;
    border-bottom: 1px solid #f0f0f0;

    .context-value { color: #595959; }
  }

  .queue-indicator {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 12px;
    background: #e6f4ff;
    font-size: 12px;
    color: #1677ff;
    flex-wrap: wrap;
    position: relative;

    .queue-dropdown {
      width: 100%;
      background: #fff;
      border-radius: 4px;
      padding: 6px 8px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.08);

      .queue-item {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 4px 6px;
        border-radius: 4px;
        font-size: 12px;
        color: #595959;
        border: 1px dashed #d9d9d9;
        margin-bottom: 4px;

        .queue-msg {
          flex: 1;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .queue-action {
          cursor: pointer;
          color: #bfbfbf;
          padding: 2px;
          font-size: 11px;
          border: none;
          background: transparent;
          border-radius: 3px;
          &:hover { color: #1677ff; }
          &:focus-visible { outline: 2px solid #4096ff; outline-offset: 1px; }
        }
      }
    }
  }

  .message-list {
    flex: 1;
    overflow-y: auto;
    padding: 12px;

    .empty-state {
      text-align: center;
      padding: 40px 20px;

      .empty-title {
        color: #8c8c8c;
        margin-bottom: 16px;
      }
      .examples {
        display: flex;
        flex-direction: column;
        gap: 8px;
        align-items: center;

        .example-item {
          padding: 8px 16px;
          background: #f5f5f5;
          border-radius: 16px;
          font-size: 13px;
          cursor: pointer;
          transition: all 0.2s;
          max-width: 280px;

          &:hover {
            background: #e6f4ff;
            color: #1677ff;
          }
          &:focus-visible {
            outline: 2px solid #4096ff;
            outline-offset: 2px;
          }
          i { margin-right: 4px; }
        }
      }
    }

    .entry {
      margin-bottom: 12px;

      &.user-entry {
        display: flex;
        justify-content: flex-end;

        .user-bubble {
          background: #1677ff;
          color: #fff;
          border-radius: 12px 12px 2px 12px;
        }
      }

      &.agent-entry {
        .entry-bubble {
          background: #f5f5f5;
          border-radius: 12px 12px 12px 2px;
        }
      }

      .entry-bubble {
        display: inline-block;
        padding: 8px 12px;
        font-size: 13px;
        max-width: 85%;
        word-break: break-word;

        &.loading-bubble {
          display: flex;
          align-items: center;
          gap: 6px;

          .retry-count { font-size: 11px; color: #8c8c8c; }
        }

        &.error-bubble {
          display: flex;
          align-items: center;
          gap: 6px;
          background: #fff2f0;
          color: #cf1322;
        }

        &.query-result {
          display: flex;
          flex-direction: column;
          gap: 4px;
          max-width: 90%;

          .query-content {
            white-space: pre-wrap;
            .query-icon { margin-right: 4px; color: #8c8c8c; }
          }
          .query-number-card {
            text-align: center;
            padding: 12px;

            .query-number {
              font-size: 36px;
              font-weight: 700;
              color: #1677ff;
            }
          }
          .query-table-wrapper {
            .table-more {
              text-align: center;
              font-size: 11px;
              color: #8c8c8c;
              margin-top: 4px;
            }
          }
        }
      }

      .clarify-card {
        background: #f5f5f5;
        border-radius: 12px;
        padding: 12px;
        max-width: 85%;

        .clarify-question {
          font-size: 13px;
          margin-bottom: 8px;
        }
        .clarify-round {
          font-size: 11px;
          color: #8c8c8c;
          margin-bottom: 8px;
        }
        .clarify-exceed {
          font-size: 13px;
          color: #fa8c16;
          display: flex;
          align-items: center;
          gap: 6px;
          padding: 8px;
          background: #fffbe6;
          border-radius: 4px;
        }
      }

      .resume-card {
        background: #e6f4ff;
        border: 1px solid #91caff;
        border-radius: 8px;
        padding: 12px;
        max-width: 90%;

        .resume-header {
          display: flex;
          align-items: center;
          gap: 6px;
          font-size: 13px;
          font-weight: 500;
          color: #1677ff;
          margin-bottom: 6px;

          i { font-size: 15px; }
        }
        .resume-summary {
          font-size: 13px;
          color: #595959;
          margin-bottom: 4px;
        }
        .resume-steps {
          font-size: 12px;
          color: #8c8c8c;
          margin-bottom: 8px;
        }
        .resume-actions {
          display: flex;
          gap: 8px;
        }
      }

      .resume-collapsed {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 6px 12px;
        background: #f5f5f5;
        border-radius: 8px;
        font-size: 12px;
        color: #8c8c8c;
        cursor: pointer;
        max-width: 85%;

        &:hover { background: #f0f0f0; }
        &:focus-visible { outline: 2px solid #4096ff; outline-offset: 1px; }
      }
    }
  }

  .pii-disclosure {
    padding: 4px 12px;
    font-size: 11px;
    color: #bfbfbf;
    text-align: center;
  }

  .dwtable-hint {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    margin: 0 12px 4px;
    font-size: 12px;
    color: #d46b08;
    background: #fff7e6;
    border: 1px solid #ffd591;
    border-radius: 4px;

    i {
      color: #fa8c16;
    }
  }

  .input-area {
    display: flex;
    gap: 8px;
    padding: 10px 12px;
    border-top: 1px solid #f0f0f0;
    background: #fff;
  }
}
</style>
