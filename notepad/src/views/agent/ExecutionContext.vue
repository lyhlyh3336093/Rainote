<template>
  <div class="execution-context">
    <!-- 上下文指示器（R5/R6） -->
    <div
      class="context-bar"
      role="button"
      tabindex="0"
      :aria-expanded="showEditor"
      aria-label="编辑上下文"
      @click="toggleEditor"
      @keydown.enter="toggleEditor"
    >
      <i class="bi bi-contextual" :class="{ active: store.hasContext }"></i>
      <span class="context-label">上下文：</span>
      <template v-if="store.hasContext">
        <a-tag
          v-for="item in contextItems"
          :key="item.key"
          :color="item.color"
          :bordered="false"
          class="context-tag"
        >
          {{ item.label }} #{{ item.value }}
        </a-tag>
      </template>
      <span v-else class="context-empty">无上下文</span>
      <i class="bi bi-pencil-square edit-icon" v-if="!showEditor"></i>
      <i class="bi bi-chevron-up edit-icon" v-else></i>
    </div>

    <!-- 空上下文提示（R5） -->
    <div v-if="!store.hasContext && !showEditor" class="context-hint">
      <i class="bi bi-info-circle"></i>
      <span>请先打开笔记或表格，或在此指定操作目标</span>
    </div>

    <!-- 上下文编辑器（R7a） -->
    <transition name="expand">
      <div v-if="showEditor" class="context-editor">
        <div class="editor-row" v-for="field in editFields" :key="field.key">
          <label class="field-label">
            <i :class="field.icon"></i> {{ field.label }}
          </label>
          <a-input
            v-model:value="editValues[field.key]"
            :placeholder="field.placeholder"
            :status="field.error ? 'error' : ''"
            @blur="validateField(field.key)"
            allow-clear
            size="small"
          />
          <span v-if="field.error" class="field-error">{{ field.error }}</span>
        </div>

        <!-- 历史实体选择器（R7c） -->
        <div class="recent-entities" v-if="store.recentEntities.length > 0">
          <div class="recent-header">
            <span class="recent-title">最近访问</span>
            <span class="recent-count">{{ store.recentEntities.length }} 条</span>
          </div>
          <div class="recent-list">
            <div
              v-for="entity in store.recentEntities.slice(0, 8)"
              :key="`${entity.type}-${entity.id}`"
              class="recent-item"
              role="button"
              tabindex="0"
              :aria-label="`选择 ${entity.title} 作为上下文`"
              @click="handleSelectRecent(entity)"
              @keydown.enter="handleSelectRecent(entity)"
            >
              <i :class="entityIcon(entity.type)"></i>
              <span class="recent-name">{{ entity.title }}</span>
              <button class="remove-icon" aria-label="移除该历史实体"
                      @click.stop="store.removeRecentEntity(entity.type, entity.id)">
                <i class="bi bi-x"></i>
              </button>
            </div>
          </div>
        </div>
        <div v-else class="recent-empty">
          暂无最近访问的实体，请先在笔记中打开
        </div>

        <div class="editor-actions">
          <a-button size="small" type="primary" @click="applyContext">应用</a-button>
          <a-button size="small" @click="clearAll">清空</a-button>
          <a-button size="small" type="text" @click="showEditor = false">收起</a-button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive, computed } from 'vue';
import { message } from 'ant-design-vue';
import { useAgentStore, type RecentEntity } from '../../stores/agent';

const store = useAgentStore();
const showEditor = ref(false);

/** 编辑中的上下文值 */
const editValues = reactive({
  noteId: store.context.noteId?.toString() || '',
  dwtableId: store.context.dwtableId?.toString() || '',
  columnId: store.context.columnId?.toString() || '',
  recordId: store.context.recordId?.toString() || '',
});

/** 字段错误信息 */
const fieldErrors = reactive<Record<string, string>>({
  noteId: '', dwtableId: '', columnId: '', recordId: '',
});

/** 当前上下文项（用于显示） */
const contextItems = computed(() => {
  const items = [];
  if (store.context.noteId) items.push({ key: 'noteId', label: '笔记', value: store.context.noteId, color: 'blue' });
  if (store.context.dwtableId) items.push({ key: 'dwtableId', label: '多维表', value: store.context.dwtableId, color: 'cyan' });
  if (store.context.columnId) items.push({ key: 'columnId', label: '列', value: store.context.columnId, color: 'purple' });
  if (store.context.recordId) items.push({ key: 'recordId', label: '记录', value: store.context.recordId, color: 'green' });
  return items;
});

/** 编辑字段配置 */
const editFields = computed(() => [
  { key: 'noteId', label: '笔记 ID', icon: 'bi bi-file-earmark', placeholder: '输入笔记 ID', error: fieldErrors.noteId },
  { key: 'dwtableId', label: '多维表 ID', icon: 'bi bi-table', placeholder: '输入多维表 ID', error: fieldErrors.dwtableId },
  { key: 'columnId', label: '列 ID', icon: 'bi bi-text-right', placeholder: '输入列 ID', error: fieldErrors.columnId },
  { key: 'recordId', label: '记录 ID', icon: 'bi bi-list-ul', placeholder: '输入记录 ID', error: fieldErrors.recordId },
]);

/** 切换编辑器 */
function toggleEditor() {
  showEditor.value = !showEditor.value;
  if (showEditor.value) {
    // 同步当前值到编辑器
    editValues.noteId = store.context.noteId?.toString() || '';
    editValues.dwtableId = store.context.dwtableId?.toString() || '';
    editValues.columnId = store.context.columnId?.toString() || '';
    editValues.recordId = store.context.recordId?.toString() || '';
  }
}

/** 校验字段格式（R7a 失焦校验） */
function validateField(key: string) {
  const val = editValues[key as keyof typeof editValues].trim();
  if (val && !/^\d+$/.test(val)) {
    fieldErrors[key] = 'ID 必须为正整数';
  } else {
    fieldErrors[key] = '';
  }
}

/** 应用编辑的上下文 */
function applyContext() {
  // 校验所有字段
  let hasError = false;
  for (const key of Object.keys(editValues)) {
    validateField(key);
    if (fieldErrors[key]) hasError = true;
  }
  if (hasError) {
    message.warning('请修复无效的 ID 格式');
    return;
  }
  store.setContextManual({
    noteId: editValues.noteId ? Number(editValues.noteId) : null,
    dwtableId: editValues.dwtableId ? Number(editValues.dwtableId) : null,
    columnId: editValues.columnId ? Number(editValues.columnId) : null,
    recordId: editValues.recordId ? Number(editValues.recordId) : null,
  });
  showEditor.value = false;
  message.success('上下文已更新');
}

/** 清空所有上下文 */
function clearAll() {
  editValues.noteId = '';
  editValues.dwtableId = '';
  editValues.columnId = '';
  editValues.recordId = '';
  store.clearContext();
  message.success('上下文已清空');
}

/** 选择历史实体作为上下文（R7c） */
function handleSelectRecent(entity: RecentEntity) {
  store.selectRecentEntity(entity);
  editValues[entity.type === 'note' ? 'noteId'
    : entity.type === 'dwtable' ? 'dwtableId'
    : entity.type === 'column' ? 'columnId'
    : 'recordId'] = entity.id.toString();
  message.success(`已选择：${entity.title}`);
}

/** 实体类型图标 */
function entityIcon(type: string): string {
  const map: Record<string, string> = {
    note: 'bi bi-file-earmark',
    dwtable: 'bi bi-table',
    column: 'bi bi-text-right',
    record: 'bi bi-list-ul',
  };
  return map[type] || 'bi bi-circle';
}
</script>

<style lang="scss" scoped>
.execution-context {
  border-bottom: 1px solid #f0f0f0;

  .context-bar {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 12px;
    background: #f5f5f5;
    font-size: 12px;
    color: #8c8c8c;
    cursor: pointer;
    transition: background 0.2s;

    &:hover { background: #f0f0f0; }
    &:focus-visible { outline: 2px solid #4096ff; outline-offset: -2px; }

    .context-label { flex-shrink: 0; }
    .context-empty { color: #bfbfbf; }
    .context-tag { font-size: 11px; margin: 0; }
    .edit-icon { margin-left: auto; color: #bfbfbf; font-size: 12px; }

    i.active { color: #1677ff; }
  }

  .context-hint {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 4px 12px;
    font-size: 11px;
    color: #fa8c16;
    background: #fffbe6;

    i { font-size: 13px; }
  }

  .context-editor {
    padding: 10px 12px;
    background: #fafafa;

    .editor-row {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 6px;

      .field-label {
        font-size: 12px;
        color: #595959;
        width: 80px;
        flex-shrink: 0;
        display: flex;
        align-items: center;
        gap: 4px;
      }

      .field-error {
        font-size: 11px;
        color: #ff4d4f;
        flex-shrink: 0;
      }
    }

    .recent-entities {
      margin-top: 8px;
      padding-top: 8px;
      border-top: 1px dashed #e8e8e8;

      .recent-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 6px;

        .recent-title { font-size: 12px; color: #595959; font-weight: 500; }
        .recent-count { font-size: 11px; color: #bfbfbf; }
      }

      .recent-list {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;

        .recent-item {
          display: flex;
          align-items: center;
          gap: 4px;
          padding: 3px 8px;
          background: #fff;
          border: 1px solid #e8e8e8;
          border-radius: 12px;
          font-size: 11px;
          cursor: pointer;
          transition: all 0.2s;

          &:hover {
            border-color: #1677ff;
            color: #1677ff;
          }
          &:focus-visible {
            outline: 2px solid #4096ff;
            outline-offset: 1px;
          }

          .recent-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
          .remove-icon {
            font-size: 10px;
            color: #bfbfbf;
            padding: 2px;
            border: none;
            background: transparent;
            cursor: pointer;
            border-radius: 3px;
            &:hover { color: #ff4d4f; }
            &:focus-visible { outline: 2px solid #4096ff; outline-offset: 1px; }
          }
        }
      }
    }

    .recent-empty {
      margin-top: 8px;
      padding: 8px;
      text-align: center;
      font-size: 11px;
      color: #bfbfbf;
    }

    .editor-actions {
      display: flex;
      gap: 6px;
      margin-top: 8px;
    }
  }
}

/* 展开/收起动画 */
.expand-enter-active, .expand-leave-active {
  transition: all 0.2s ease;
  overflow: hidden;
}
.expand-enter-from, .expand-leave-to {
  max-height: 0;
  opacity: 0;
}
.expand-enter-to, .expand-leave-from {
  max-height: 500px;
  opacity: 1;
}
</style>
