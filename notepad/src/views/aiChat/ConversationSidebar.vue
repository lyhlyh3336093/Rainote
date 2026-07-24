<template>
  <div class="conversation-sidebar">
    <div class="sidebar-header">
      <a-button type="primary" block @click="store.createSession" :loading="false">
        <template #icon><PlusOutlined /></template>
        新建对话
      </a-button>
    </div>
    <div class="session-list">
      <a-spin :spinning="store.isLoadingSessions">
        <div v-if="store.sessions.length === 0 && !store.isLoadingSessions" class="empty-state">
          <p>暂无对话</p>
        </div>
        <div
          v-for="session in store.sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: session.id === store.activeSessionId }"
          @click="handleSelect(session.id)"
        >
          <!-- R4a/Group F — 可编辑标题 -->
          <a-input
            v-if="editingId === session.id"
            v-model:value="editingTitle"
            size="small"
            :maxlength="50"
            placeholder="标题"
            @pressEnter="submitTitle(session.id)"
            @blur="submitTitle(session.id)"
            @keydown.esc="cancelEdit"
            @click.stop
            ref="editingInputRef"
          />
          <div v-else class="session-title" @dblclick.stop="startEdit(session)">
            {{ session.title || '新对话' }}
          </div>
          <a-popconfirm
            title="确定删除此对话？"
            @confirm="store.deleteSession(session.id)"
            ok-text="删除"
            cancel-text="取消"
          >
            <DeleteOutlined
              class="delete-btn"
              @click.stop
            />
          </a-popconfirm>
        </div>
      </a-spin>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, nextTick } from 'vue';
import { useAiChatStore, type Session } from '../../stores/aiChat';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons-vue';

const store = useAiChatStore();

// R4a/Group F — 可编辑标题状态
const editingId = ref<number | null>(null);
const editingTitle = ref('');
const editingInputRef = ref<any>(null);

const handleSelect = (id: number) => {
  // 编辑中点击其他会话不切换，避免 blur 与 click 冲突
  if (editingId.value !== null) return;
  store.selectSession(id);
};

// 进入标题编辑模式
const startEdit = (session: Session) => {
  editingId.value = session.id;
  editingTitle.value = session.title || '';
  nextTick(() => {
    editingInputRef.value?.focus?.();
  });
};

// 提交标题（Enter 或 blur 触发）
const submitTitle = async (id: number) => {
  const newTitle = editingTitle.value.trim();
  const session = store.sessions.find(s => s.id === id);
  // 标题未变化或为空且原也为空 → 取消
  if (session && newTitle === (session.title || '')) {
    editingId.value = null;
    return;
  }
  if (newTitle) {
    await store.updateTitle(id, newTitle);
  }
  editingId.value = null;
};

// ESC 取消编辑
const cancelEdit = () => {
  editingId.value = null;
};
</script>

<style lang="scss" scoped>
.conversation-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  border-right: 1px solid #f0f0f0;

  .sidebar-header {
    padding: 12px;
    border-bottom: 1px solid #f0f0f0;
  }

  .session-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }

  .empty-state {
    text-align: center;
    color: #999;
    padding: 40px 0;
    font-size: 13px;
  }

  .session-item {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    border-radius: 6px;
    cursor: pointer;
    margin-bottom: 4px;
    transition: background 0.2s;

    &:hover {
      background: #f5f5f5;

      .delete-btn {
        opacity: 1;
      }
    }

    &.active {
      background: #e6f7ff;
    }

    .session-title {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 13px;
      color: #333;
    }

    :deep(.ant-input) {
      flex: 1;
      margin-right: 8px;
    }

    .delete-btn {
      opacity: 0;
      color: #999;
      transition: opacity 0.2s, color 0.2s;
      flex-shrink: 0;
      margin-left: 8px;

      &:hover {
        color: #ff4d4f;
      }
    }
  }
}
</style>
