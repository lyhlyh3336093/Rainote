<template>
  <div class="semantic-link-selector">
    <!-- 表格选择 -->
    <a-select
        v-model:value="tableId"
        placeholder="请选择多维表格"
        style="width: 100%; margin-bottom: 12px"
        :loading="loadingTable"
        @change="handleTableChange"
    >
      <a-select-option v-for="item in tableList" :key="item.id" :value="item.id">
        {{ item.name }}
      </a-select-option>
    </a-select>

    <!-- 数据预览与选择 -->
    <a-table
        :loading="loadingData"
        :data-source="tableData"
        :columns="columns"
        :pagination="false"
        :row-selection="rowSelection"
        :custom-row="customRow"
        row-key="id"
        size="small"
        :scroll="{  y: 500 }"
        class="custom-table"
    >
    </a-table>

    <div class="footer-actions" v-if="selectedRowKeys.length > 0">
      <a-button type="primary" block @click="handleConfirmSelection">
        确认关联已选行
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, onMounted, computed } from 'vue';
import { useFetch } from '../../../../../src/hooks';
import { useStore } from '../../../../../src/stores/editor';
import { FieldEnum } from "@/enum";
import { message } from 'ant-design-vue';

// 定义组件事件，用于向父组件（Inline Tool）传递选择的数据
const emit = defineEmits(['select']);

// 源笔记 id(用户当前正在编辑、划词的那个笔记) — FORWARD 模式下点击单元格文字应回跳此笔记,
// 而非 table.noteId(多维表格的归属笔记 id,语义完全不同)
const store = useStore();
const pid = store.id;

// 状态定义
const tableId = ref<string | null>(null);
const tableList = ref([]);
const columns = ref([]);
const tableData = shallowRef([]); // 使用 shallowRef 优化大数据性能
const loadingTable = ref(false);
const loadingData = ref(false);

// 选中项状态
const selectedRowKeys = ref<string[]>([]);
const selectedRows = ref<any[]>([]);

// 1. 初始化
onMounted(async () => {
  loadingTable.value = true;
  try {
    const { data } = await useFetch(`/system/dwtable/list`).get().json();
    tableList.value = data.value?.data || [];
  } finally {
    loadingTable.value = false;
  }
});

// 2. 表格切换处理
const handleTableChange = async (id: string) => {
  if (!id) return;

  // 重置状态
  loadingData.value = true;
  selectedRowKeys.value = [];
  selectedRows.value = [];
  tableData.value = [];

  try {
    const [colRes, dataRes] = await Promise.all([
      useFetch(`/system/column/columnList?dwtableId=${id}`).get().json(),
      useFetch(`/system/record/dataList?dwtableId=${id}`).get().json()
    ]);

    const columnList = colRes.data.value?.data || [];
    const rawDataList = dataRes.data.value?.data || [];

    // 构建列定义
    columns.value = columnList.map(col => ({
      title: col.name,
      dataIndex: String(col.id),
      key: String(col.id),
      ellipsis: true,
      width: 150
    }));

    // 处理行数据
    tableData.value = rawDataList
        .filter(item => item?.tableData)
        .map(item => {
          const row = { ...item.tableData };
          row.id = String(item.id); // 统一转为字符串 ID
          columnList.forEach(col => {
            const val = row[col.id];
            // 处理集合类数据
            if (val && [FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(col.type)) {
              row[col.id] = typeof val === 'string' ? val.split(',').filter(Boolean) : val;
            }
            // 处理空数组
            if (Array.isArray(row[col.id]) && row[col.id].length === 0) {
              row[col.id] = null;
            }
          });
          return row;
        })
        .sort((a, b) => Number(a.id) - Number(b.id));

  } catch (error) {
    message.error("获取数据失败");
  } finally {
    loadingData.value = false;
  }
};

// 3. 单选配置
const rowSelection = computed(() => ({
  type: 'radio',
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: string[], rows: any[]) => {
    selectedRowKeys.value = keys;
    selectedRows.value = rows;
  },
}));

const customRow = (record: any) => {
  return {
    onClick: () => {
      selectedRowKeys.value = [record.id];
      selectedRows.value = [record];
    },
  };
};

// 5. 提交选择
const handleConfirmSelection = () => {
  if (selectedRows.value.length === 0) return;

  const record = selectedRows.value[0];
  const displayTitle = record[columns.value[0]?.dataIndex] || '未命名记录';
  // tableNoteId: 多维表格的归属笔记 id(NoteDwtable.noteId)。
  // 用于点击 forward 锚点跳转 /base/{tableNoteId}/{dwtableId},语义上 ≠ pid(源笔记 id)。
  const tableNoteId = tableList.value.find((item: any) => item.id === tableId.value)?.noteId;
  emit('select', {
    tableId: tableId.value,
    recordId: record.id,
    title: displayTitle,
    noteId: pid,
    tableNoteId
  });
};
</script>

<style scoped>
.semantic-link-selector {
  display: flex;
  flex-direction: column;
  max-height: 600px;
  background: #fff;
}

.custom-table :deep(.ant-table-row) {
  cursor: pointer;
}

.tag-container {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.empty-text {
  color: #bfbfbf;
}

.footer-actions {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}

/* 隐藏部分不需要的 Table 元素以适配 Inline Tool 宽度 */
:deep(.ant-table-pagination.ant-pagination) {
  margin: 8px 0 !important;
}
</style>
