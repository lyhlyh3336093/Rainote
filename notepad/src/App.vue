<template>
  <router-view/>
  <!-- Agent 面板全局挂载（登录/注册页不显示，R1/R5） -->
  <AgentPanel v-if="showAgent"/>
  <!-- 左侧 Agent 抽屉入口（Teleport 到 body，脱离 #app 全局 CSS 影响） -->
  <Teleport to="body">
    <div
      v-if="showFloatingButton"
      class="agent-drawer-trigger"
      @click="agentStore.togglePanel()"
      title="Agent (Ctrl+Shift+A)"
      role="button"
      tabindex="0"
      @keydown.enter="agentStore.togglePanel()"
    >
      <svg class="trigger-icon" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
        <path d="M12 2a1 1 0 0 1 1 1v1h3a3 3 0 0 1 3 3v1h1a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-1v1a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-1H4a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h1V7a3 3 0 0 1 3-3h3V3a1 1 0 0 1 1-1zM9 8a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm-3 5a2 2 0 0 0-2 2v1h4v-1a2 2 0 0 0-2-2z"/>
      </svg>
    </div>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAgentStore } from './stores/agent';
import AgentPanel from './views/agent/AgentPanel.vue';

const route = useRoute();
const agentStore = useAgentStore();

/** layout 内的路由名（已有侧边栏 Agent 按钮，不显示浮动入口） */
const layoutRoutes = new Set(['home', 'me', 'shared', 'folder', 'collect', 'trash', 'template']);

/** 不显示 Agent 入口的路由名（登录/注册等未认证页面） */
const noAgentRoutes = new Set(['signin', 'signup']);

/** 是否显示 Agent 相关元素（登录/注册页隐藏） */
const showAgent = computed(() => !noAgentRoutes.has(route.name as string));

/** 不显示浮动 Agent 按钮的路由名（application 界面不需要 agent 入口） */
const noFloatingButtonRoutes = new Set(['application']);

/** 是否显示浮动 Agent 按钮（仅编辑页显示，认证页面/application 不显示） */
const showFloatingButton = computed(() =>
  showAgent.value
  && !layoutRoutes.has(route.name as string)
  && !noFloatingButtonRoutes.has(route.name as string));

/**
 * 路由切换时自动关闭 Agent 面板，防止面板跨页面持续遮挡内容。
 * 仅监听 route.name 变化（同一页面内 params 变化不关闭）。
 */
watch(
  () => route.name,
  (newName, oldName) => {
    if (oldName && newName !== oldName && agentStore.panelOpen) {
      agentStore.closePanel();
    }
  },
);

/**
 * R5 上下文感知：监听路由变化，自动提取 noteId / dwtableId。
 * - docx / sheets → noteId
 * - base → noteId + dwtableId (tableId)
 * - 其他页面 → 清空上下文
 */
watch(
  () => [route.name, route.params],
  () => {
    const name = route.name as string;
    const params = route.params;
    if (name === 'docx' || name === 'sheets') {
      const noteId = Number(params.id) || null;
      agentStore.setContext({
        noteId,
        dwtableId: null,
        columnId: null,
        recordId: null,
      });
      // R7c：推送到历史实体栈
      if (noteId) {
        agentStore.pushRecentEntity({
          type: 'note', id: noteId,
          title: `笔记 #${noteId}`,
          visitedAt: Date.now(),
        });
      }
    } else if (name === 'base') {
      const noteId = Number(params.id) || null;
      const dwtableId = Number(params.tableId) || null;
      agentStore.setContext({ noteId, dwtableId, columnId: null, recordId: null });
      if (noteId) {
        agentStore.pushRecentEntity({
          type: 'note', id: noteId,
          title: `多维表 #${noteId}`,
          visitedAt: Date.now(),
        });
      }
    }
    // 非编辑页不清空上下文（用户可能手动设置了上下文，R7c 历史实体选择器）
  },
  { immediate: true, deep: true },
);

/** R1 快捷键：Ctrl/Cmd+Shift+A 切换 Agent 面板（登录/注册页禁用） */
function handleKeyDown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'A' || e.key === 'a')) {
    if (!showAgent.value) return; // 认证页面不响应快捷键
    e.preventDefault();
    agentStore.togglePanel();
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeyDown);
});
onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown);
});
</script>

<style>
/* 左侧 Agent 抽屉入口把手 */
.agent-drawer-trigger {
  position: fixed;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 36px;
  height: 48px;
  padding: 0;
  background: #1677ff;
  color: #fff;
  border-radius: 0 10px 10px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 2px 0 8px rgba(22, 119, 255, 0.3);
  z-index: 998;
  transition: width 0.2s, box-shadow 0.2s;
}

.agent-drawer-trigger:hover {
  width: 42px;
  box-shadow: 2px 0 14px rgba(22, 119, 255, 0.5);
}

.agent-drawer-trigger:focus-visible {
  outline: 2px solid #4096ff;
  outline-offset: -2px;
}

.agent-drawer-trigger .trigger-icon {
  width: 22px;
  height: 22px;
}
</style>
