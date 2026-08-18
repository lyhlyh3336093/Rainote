<template>
  <Teleport to="body">
  <!-- 遮罩层：面板打开时覆盖页面内容，点击可关闭面板 -->
  <transition name="fade">
    <div
      v-if="store.panelOpen"
      class="agent-overlay"
      @click="store.closePanel()"
      aria-hidden="true"
    ></div>
  </transition>
  <transition name="slide">
    <div v-if="store.panelOpen" class="agent-panel">
      <!-- 被其他标签页占用 -->
      <template v-if="!store.sessionId">
        <div class="locked-banner">
          <i class="bi bi-lock-fill"></i>
          <span>Agent 已在另一标签页打开</span>
          <a-button
            v-if="store.panelState !== 'takeover'"
            type="primary"
            size="small"
            @click="store.startTakeover()"
          >
            在此标签页接管
          </a-button>
          <div v-else class="takeover-waiting">
            <a-spin size="small" />
            <span>接管中…{{ store.takeoverCountdown }}s</span>
            <a-button size="small" @click="store.cancelTakeover()">取消</a-button>
          </div>
        </div>
        <div class="locked-content">
          <p class="locked-hint">输入框已禁用，历史对话只读可见</p>
          <!-- 只读历史 -->
          <div class="readonly-entries">
            <div v-for="entry in store.entries" :key="entry.id" class="readonly-entry">
              <span v-if="entry.type === 'user'" class="readonly-user">{{ entry.content }}</span>
              <span v-else class="readonly-agent">{{ entry.content }}</span>
            </div>
            <p v-if="store.entries.length === 0" class="readonly-empty">暂无历史对话</p>
          </div>
        </div>
        <div class="panel-footer">
          <a-button size="small" @click="store.closePanel()">关闭面板</a-button>
        </div>
      </template>

      <!-- 正常使用 -->
      <template v-else>
        <!-- 面板头部 -->
        <div class="panel-header">
          <div class="header-left">
            <i class="bi bi-robot"></i>
            <span class="panel-title">Agent</span>
            <a-tag v-if="store.minuteRemaining <= 3" color="orange" :bordered="false">
              剩余 {{ store.minuteRemaining }}/分钟
            </a-tag>
          </div>
          <div class="header-right">
            <a-tooltip title="执行历史">
              <a-button type="text" size="small" @click="showHistory = !showHistory">
                <i class="bi bi-clock-history"></i>
              </a-button>
            </a-tooltip>
            <a-tooltip title="关闭">
              <a-button type="text" size="small" @click="store.closePanel()">
                <i class="bi bi-x-lg"></i>
              </a-button>
            </a-tooltip>
          </div>
        </div>

        <!-- 历史列表（可展开） -->
        <div v-if="showHistory" class="history-panel">
          <a-spin :spinning="store.isLoadingHistory">
            <div v-if="store.history.length === 0 && !store.isLoadingHistory" class="history-empty">
              暂无执行历史
            </div>
            <div
              v-for="item in store.history"
              :key="item.id"
              class="history-item"
              :class="{ 'history-item-expanded': expandedHistoryId === item.id }"
              role="button"
              tabindex="0"
              :aria-label="`查看历史记录：${item.userInput}`"
              @click="handleClickHistory(item)"
              @keydown.enter="handleClickHistory(item)"
            >
              <div class="history-item-header">
                <i :class="statusIcon(item.status)"></i>
                <span class="history-status">{{ statusLabel(item.status) }}</span>
                <a-tag v-if="item.degraded" color="orange" :bordered="false">审计不完整</a-tag>
                <i
                  v-if="item.status !== 'executing'"
                  class="bi history-expand-icon"
                  :class="expandedHistoryId === item.id ? 'bi-chevron-up' : 'bi-chevron-down'"
                ></i>
              </div>
              <div class="history-input">{{ item.userInput }}</div>
              <div class="history-time">{{ item.createdAt }}</div>

              <!-- 展开的详情：计划摘要 + 步骤状态 -->
              <div
                v-if="expandedHistoryId === item.id"
                class="history-detail"
                @click.stop
              >
                <a-spin :spinning="isLoadingDetail" size="small">
                  <div v-if="historyDetail && historyDetail.id === item.id">
                    <!-- 计划摘要 -->
                    <div v-if="parsePlan(historyDetail.planJson)" class="detail-plan">
                      <div class="detail-section-title">计划</div>
                      <div class="detail-step-count">
                        共 {{ parsePlan(historyDetail.planJson).steps?.length || 0 }} 个步骤
                      </div>
                    </div>
                    <!-- 步骤列表 -->
                    <div v-if="historyDetail.stepLogs && historyDetail.stepLogs.length > 0" class="detail-steps">
                      <div class="detail-section-title">步骤执行</div>
                      <div
                        v-for="(step, idx) in historyDetail.stepLogs"
                        :key="step.id || idx"
                        class="detail-step"
                      >
                        <span class="step-index">{{ idx + 1 }}.</span>
                        <span class="step-op">{{ step.operationName }}</span>
                        <span class="step-status" :class="`step-status-${step.status}`">
                          {{ stepStatusLabel(step.status) }}
                        </span>
                        <div v-if="step.errorMessage" class="step-error">{{ step.errorMessage }}</div>
                      </div>
                    </div>
                    <div v-else class="detail-empty">无步骤记录</div>
                  </div>
                  <div v-else-if="!isLoadingDetail" class="detail-empty">详情加载失败</div>
                </a-spin>
              </div>
            </div>
          </a-spin>
        </div>

        <!-- 对话流 -->
        <ChatStream class="panel-body" />

        <!-- 速率限制信息 -->
        <div class="rate-limit-bar">
          <span>今日 {{ store.rateLimit.dayUsed }}/{{ store.rateLimit.dayLimit }}</span>
        </div>
      </template>
    </div>
  </transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { ref } from 'vue';
import { useAgentStore } from '../../stores/agent';
import { auditDetail as fetchAuditDetail } from '../../api/agent';
import type { AgentAuditLog, AgentAuditStepLog } from '../../api/agent';
import ChatStream from './ChatStream.vue';

const store = useAgentStore();
const showHistory = ref(false);

// 历史项展开状态
const expandedHistoryId = ref<number | null>(null);
const historyDetail = ref<AgentAuditLog | null>(null);
const isLoadingDetail = ref(false);

function statusIcon(status: string): string {
  switch (status) {
    case 'completed': return 'bi bi-check-circle-fill';
    case 'interrupted': return 'bi bi-exclamation-circle-fill';
    default: return 'bi bi-arrow-clockwise';
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'completed': return '已完成';
    case 'interrupted': return '已中断';
    default: return '未完成';
  }
}

function stepStatusLabel(status: string): string {
  switch (status) {
    case 'success': return '成功';
    case 'failed': return '失败';
    case 'skipped': return '跳过';
    case 'blocked': return '阻塞';
    case 'pending_confirm': return '待确认';
    default: return '执行中';
  }
}

/** 解析计划 JSON */
function parsePlan(planJson?: string): any | null {
  if (!planJson) return null;
  try { return JSON.parse(planJson); } catch { return null; }
}

/** 点击历史项：toggle 展开，展开时加载详情；executing 状态额外支持恢复执行 */
async function handleClickHistory(item: AgentAuditLog) {
  // executing 状态优先走恢复执行路径
  if (item.status === 'executing') {
    await store.resumeFromAudit(item.id);
    showHistory.value = false;
    return;
  }

  // 非 executing：toggle 展开
  if (expandedHistoryId.value === item.id) {
    // 已展开，点击收起
    expandedHistoryId.value = null;
    historyDetail.value = null;
    return;
  }

  // 展开并加载详情
  expandedHistoryId.value = item.id;
  historyDetail.value = null;
  isLoadingDetail.value = true;
  try {
    const res = await fetchAuditDetail(item.id);
    if (res.code === 200 && res.data) {
      historyDetail.value = res.data;
    }
  } catch {
    // 忽略，详情加载失败不阻塞
  } finally {
    isLoadingDetail.value = false;
  }
}
</script>

<style lang="scss" scoped>
/* 遮罩层：面板打开时覆盖页面内容，点击可关闭 */
.agent-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(2px);
  z-index: 999;
}

/* 遮罩层淡入淡出动画 */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

/* 网页版风格面板：浮动卡片 + 圆角 + 柔和阴影 */
.agent-panel {
  position: fixed;
  top: 16px;
  left: 16px;
  bottom: 16px;
  width: 440px;
  background: #f7f7f8;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  z-index: 1000;
  overflow: hidden;
  -webkit-font-smoothing: antialiased;

  /* 锁定横幅：柔和提示 */
  .locked-banner {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 20px;
    background: #fff8e6;
    border-bottom: 1px solid #ffe7a0;
    font-size: 13px;
    color: #946300;

    i { font-size: 16px; }
    .takeover-waiting {
      display: flex;
      align-items: center;
      gap: 6px;
    }
  }

  .locked-content {
    flex: 1;
    overflow-y: auto;
    padding: 16px 20px;

    .locked-hint {
      font-size: 12px;
      color: #9b9b9b;
      margin-bottom: 12px;
    }
    .readonly-entries {
      .readonly-entry {
        margin-bottom: 10px;
        font-size: 13px;

        .readonly-user {
          display: inline-block;
          background: #e8f0fe;
          color: #1a56db;
          padding: 6px 12px;
          border-radius: 12px;
          max-width: 85%;
        }
        .readonly-agent {
          display: inline-block;
          background: #ffffff;
          color: #3c3c3c;
          padding: 6px 12px;
          border-radius: 12px;
          max-width: 85%;
          box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
        }
      }
      .readonly-empty {
        text-align: center;
        color: #bfbfbf;
        padding: 40px;
      }
    }
  }

  .panel-footer {
    padding: 12px 20px;
    background: #ffffff;
    border-top: 1px solid #ececec;
    text-align: right;
  }

  /* 头部：白色背景 + 底部阴影分隔 + 渐变图标 */
  .panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 20px;
    background: #ffffff;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);

    .header-left {
      display: flex;
      align-items: center;
      gap: 10px;

      .panel-title {
        font-weight: 600;
        font-size: 16px;
        color: #1a1a1a;
        letter-spacing: 0.2px;
      }
      i.bi-robot {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 34px;
        height: 34px;
        border-radius: 50%;
        background: linear-gradient(135deg, #1677ff, #4096ff);
        color: #fff;
        font-size: 17px;
      }
    }
    .header-right {
      display: flex;
      gap: 2px;
    }
  }

  /* 历史面板：卡片式 */
  .history-panel {
    max-height: 280px;
    overflow-y: auto;
    padding: 8px;
    background: #f7f7f8;

    .history-empty {
      text-align: center;
      color: #bfbfbf;
      padding: 24px;
      font-size: 13px;
    }
    .history-item {
      padding: 10px 12px;
      border-radius: 10px;
      background: #ffffff;
      cursor: pointer;
      transition: box-shadow 0.2s, transform 0.15s;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
      margin-bottom: 6px;

      &:hover {
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
        transform: translateY(-1px);
      }
      &:focus-visible { outline: 2px solid #4096ff; outline-offset: -2px; }
      &.history-item-expanded {
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
      }

      .history-item-header {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;

        .history-status { color: #6b6b6b; }
        .history-expand-icon {
          margin-left: auto;
          color: #b0b0b0;
          font-size: 11px;
        }
      }
      .history-input {
        font-size: 13px;
        color: #2c2c2c;
        margin: 4px 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .history-time {
        font-size: 11px;
        color: #b0b0b0;
      }

      /* 展开的详情区域 */
      .history-detail {
        margin-top: 8px;
        padding-top: 8px;
        border-top: 1px dashed #ececec;

        .detail-section-title {
          font-size: 11px;
          color: #8c8c8c;
          font-weight: 600;
          margin-bottom: 4px;
          text-transform: uppercase;
          letter-spacing: 0.3px;
        }
        .detail-step-count {
          font-size: 12px;
          color: #595959;
          margin-bottom: 8px;
        }
        .detail-step {
          display: flex;
          align-items: center;
          gap: 6px;
          font-size: 12px;
          padding: 3px 0;
          flex-wrap: wrap;

          .step-index { color: #8c8c8c; min-width: 18px; }
          .step-op { color: #2c2c2c; font-family: monospace; }
          .step-status {
            padding: 1px 6px;
            border-radius: 4px;
            font-size: 11px;
            margin-left: auto;

            &.step-status-success { color: #52c41a; background: #f6ffed; }
            &.step-status-failed { color: #ff4d4f; background: #fff2f0; }
            &.step-status-skipped { color: #8c8c8c; background: #f5f5f5; }
            &.step-status-blocked { color: #fa8c16; background: #fff7e6; }
            &.step-status-pending_confirm { color: #fa8c16; background: #fff7e6; }
            &.step-status-executing { color: #1677ff; background: #e6f4ff; }
          }
          .step-error {
            width: 100%;
            color: #ff4d4f;
            font-size: 11px;
            padding-left: 24px;
            margin-top: 2px;
          }
        }
        .detail-empty {
          font-size: 12px;
          color: #bfbfbf;
          text-align: center;
          padding: 8px;
        }
      }
    }
  }

  .panel-body {
    flex: 1;
    min-height: 0;
  }

  /* 速率限制栏：精致底栏 */
  .rate-limit-bar {
    padding: 8px 20px;
    font-size: 12px;
    color: #9b9b9b;
    background: #ffffff;
    text-align: center;
    border-top: 1px solid #ececec;
  }
}

/* 左侧抽屉滑入动画 */
.slide-enter-active, .slide-leave-active {
  transition: transform 0.3s cubic-bezier(0.32, 0.72, 0, 1);
}
.slide-enter-from, .slide-leave-to {
  transform: translateX(-100%);
}

/* 窄视口适配（<768px，R15b）：面板折叠为底部抽屉 */
@media (max-width: 767px) {
  .agent-panel {
    top: auto !important;
    bottom: 0;
    left: 0;
    right: 0;
    width: 100% !important;
    height: 80vh !important;
    border-radius: 16px 16px 0 0;
  }
  .slide-enter-from, .slide-leave-to {
    transform: translateY(100%);
  }
}
</style>
