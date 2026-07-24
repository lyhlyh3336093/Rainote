<template>
  <!-- U5: 笔记编辑器右上角常驻图标按钮(toggle + 数字角标)+ 浮层面板 -->
  <div class="np-reference-panel">
    <a-popover
      v-model:open="visible"
      trigger="click"
      placement="bottomRight"
      overlay-class-name="np-reference-panel-popover"
      :get-popup-container="() => $el?.parentElement || document.body"
    >
      <template #content>
        <div class="np-reference-panel-content">
          <!-- 加载态 -->
          <div v-if="state.status === 'loading'" class="state state-loading">
            <a-spin size="small" />
            <span class="state-text">加载中…</span>
          </div>

          <!-- 空态 -->
          <div v-else-if="state.status === 'empty'" class="state state-empty">
            <a-empty
              :image="Empty.PRESENTED_IMAGE_SIMPLE"
              description="当前笔记暂未被多维表格引用"
            />
          </div>

          <!-- 错误态 -->
          <div v-else-if="state.status === 'error'" class="state state-error">
            <a-result status="error" sub-title="加载引用列表失败,请稍后重试">
              <template #extra>
                <a-button size="small" @click="reload">重试</a-button>
              </template>
            </a-result>
          </div>

          <!-- 数据态:按表格/单元格分组展示 -->
          <div v-else class="state state-data">
            <a-empty
              v-if="dataList.length === 0"
              :image="Empty.PRESENTED_IMAGE_SIMPLE"
              description="当前笔记暂未被多维表格引用"
            />
            <div v-else class="ref-list">
              <div
                v-for="group in groupedData"
                :key="group.key"
                class="ref-group"
              >
                <div class="ref-group-title">
                  <TableOutlined />
                  <span class="ref-group-name">{{ group.tableName }}</span>
                </div>
                <div
                  v-for="item in group.items"
                  :key="item.id"
                  class="ref-item"
                  @click="onLocate(item)"
                >
                  <span class="ref-cell">{{ group.tableName }} / 行 #{{ item.linkRecordId }}</span>
                  <span class="ref-word">{{ item.contextText || item.itemValue || '(未命名)' }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
      <a-badge :count="dataList.length" :offset="[-4, 4]">
        <a-button
          type="text"
          shape="circle"
          :class="{ 'is-active': visible }"
          title="被引用"
        >
          <template #icon>
            <LinkOutlined />
          </template>
        </a-button>
      </a-badge>
    </a-popover>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue';
import { Empty } from 'ant-design-vue';
import { LinkOutlined, TableOutlined } from '@ant-design/icons-vue';
import { useFetch } from '../../hooks';
import { jumpToTableCell } from '@/utils';

const props = defineProps<{
  noteId: string | number;
  // 由父组件传入的共享缓存(笔记 onReady 时已拉取);若提供则不再重复发起请求
  initialList?: any[];
  // 引用列表版本号,父组件更新缓存时递增,触发本组件重新读取
  cacheVersion?: number;
}>();

const visible = ref(false);
const state = reactive({
  status: 'loading' as 'loading' | 'empty' | 'error' | 'data',
  list: [] as any[],
});

// 优先用父组件传入的缓存;否则本地拉取
const fetchReferences = async () => {
  state.status = 'loading';
  try {
    const { data } = await useFetch(`system/notelink/byNote/${props.noteId}`).get().json();
    state.list = data.value?.data || [];
    state.status = state.list.length === 0 ? 'empty' : 'data';
  } catch (e) {
    console.error('ReferencePanel 拉取引用列表失败:', e);
    state.status = 'error';
  }
};

// 缓存优先:父组件传入 initialList 时直接用,不发起请求
watch(
  () => [props.initialList, props.cacheVersion],
  ([list]) => {
    if (list && Array.isArray(list)) {
      state.list = list;
      state.status = list.length === 0 ? 'empty' : 'data';
    }
  },
  { immediate: true, deep: true }
);

// 缓存为空且面板打开时,触发本地拉取(降级路径)
watch(visible, (open) => {
  if (open && state.list.length === 0 && state.status !== 'error' && !props.initialList) {
    fetchReferences();
  }
});

const reload = () => fetchReferences();

const dataList = computed(() => state.list);

// 按 linkDwTableId 分组
const groupedData = computed(() => {
  const map = new Map<string, { key: string; tableName: string; items: any[] }>();
  for (const item of state.list) {
    const tableId = String(item.linkDwTableId ?? '');
    if (!map.has(tableId)) {
      map.set(tableId, {
        key: tableId,
        tableName: item.linkDwTableName || `表格 ${tableId || '(未知)'}`,
        items: [],
      });
    }
    map.get(tableId)!.items.push(item);
  }
  return Array.from(map.values());
});

const onLocate = (item: any) => {
  jumpToTableCell({
    noteId: item.linkNoteId,
    linkDwTableId: item.linkDwTableId,
    linkColumnId: item.linkColumnId,
    linkItemId: item.linkItemId,
    linkRecordId: item.linkRecordId,
  });
};

defineExpose({ reload, state });
</script>

<style lang="scss" scoped>
.np-reference-panel {
  display: inline-block;

  :deep(.ant-btn) {
    &.is-active {
      color: #1890ff;
      background: #e6f7ff;
    }
  }
}

.np-reference-panel-content {
  width: 320px;
  max-height: 400px;
  overflow-y: auto;

  .state {
    padding: 8px 0;

    &.state-loading {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 24px 0;

      .state-text {
        color: #999;
        font-size: 13px;
      }
    }

    &.state-empty,
    &.state-error {
      padding: 12px 0;
    }
  }

  .ref-list {
    .ref-group {
      margin-bottom: 12px;

      .ref-group-title {
        display: flex;
        align-items: center;
        gap: 6px;
        font-weight: 500;
        font-size: 13px;
        color: #333;
        padding: 4px 0;
        border-bottom: 1px solid #f0f0f0;
        margin-bottom: 4px;

        .ref-group-name {
          flex: 1;
        }
      }

      .ref-item {
        display: flex;
        flex-direction: column;
        padding: 6px 8px;
        border-radius: 4px;
        cursor: pointer;
        transition: background 0.2s;

        &:hover {
          background: #f5f5f5;
        }

        .ref-cell {
          font-size: 12px;
          color: #999;
        }

        .ref-word {
          font-size: 13px;
          color: #1890ff;
          margin-top: 2px;
        }
      }
    }
  }
}
</style>
