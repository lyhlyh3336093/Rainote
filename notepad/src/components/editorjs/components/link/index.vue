<template>
  <!-- 表格部分 -->
  <a-table :scroll="{ y: 800 }" :dataSource="data" :columns="columns" :show-expand-column="false" size="middle">
    <template #bodyCell="{ column, record, index }">
      <template v-if="column.key === 'action'">
        <div class="flex space-x-2">
          <a-button @click="handleAction('external', record)" type="link" size="small">单向链接</a-button>
          <a-button @click="handleAction('preview', record)" type="link" size="small">双向链接</a-button>
          <a-button @click="handleAction('edit', record, index)" type="link" size="small">修改</a-button>
          <a-popconfirm title="确定删除此链接吗？" @confirm="handleDelete(index)">
            <a-button type="link" danger size="small">删除</a-button>
          </a-popconfirm>
        </div>
      </template>
    </template>
  </a-table>

  <!-- 预览弹窗 -->
  <a-modal v-model:open="previewState.visible" :title="previewState.title" wrap-class-name="full-modal" width="90%"
    :footer="null" centered destroyOnClose>
    <div class="iframe-container">
      <iframe :src="previewState.url" class="w-full h-full border-none" style="min-height: 70vh;"></iframe>
    </div>
  </a-modal>

  <!-- 编辑弹窗 -->
  <a-modal v-model:open="editState.visible" :title="`修改链接: ${editState.title}`" @ok="handleEditSubmit"
    @cancel="closeEdit" width="500px" destroyOnClose>
    <a-form :model="formState" layout="vertical" class="mt-4">
      <a-form-item label="链接地址" name="link"
        :rules="[{ required: true, message: '请输入链接' }, { type: 'url', message: '请输入合法的URL' }]">
        <a-input v-focus v-model:value="formState.link" placeholder="https://..." />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';

// 类型定义
interface LinkItem {
  title: string;
  value: string;
  desc?: string;
  [key: string]: any;
}

const props = defineProps<{
  data: LinkItem[];
  target: HTMLElement;
}>();

const emit = defineEmits(['change-link']);

// --- 状态管理 ---

// 预览状态
const previewState = reactive({
  visible: false,
  title: '',
  url: ''
});

// 编辑状态
const editState = reactive({
  visible: false,
  title: '',
  index: -1
});

const formState = reactive({
  link: ''
});

// --- 工具函数 ---

/**
 * 确保URL包含协议头
 */
const formatUrl = (url: string) => {
  return /^https?:\/\//i.test(url) ? url : `https://${url}`;
};

/**
 * 更新 DOM 节点的属性并通知父组件
 */
const updateTargetAttribute = (links: LinkItem[]) => {
  if (links.length === 0) {
    props.target.removeAttribute('link');
  } else {
    props.target.setAttribute('link', JSON.stringify(links));
  }
  emit('change-link', links, true);
};

// --- 操作处理 ---

const handleAction = (type: 'external' | 'preview' | 'edit', record: LinkItem, index: number = -1) => {
  const formattedUrl = formatUrl(record.value);

  switch (type) {
    case 'external':
      window.open(formattedUrl, '_blank');
      break;
    case 'preview':
      previewState.title = record.title;
      previewState.url = formattedUrl;
      previewState.visible = true;
      break;
    case 'edit':
      editState.title = record.title;
      editState.index = index;
      formState.link = record.value;
      editState.visible = true;
      break;
  }
};

const handleEditSubmit = () => {
  if (!formState.link.trim()) return;

  const newList = [...props.data];
  if (newList[editState.index]) {
    newList[editState.index].value = formState.link;
    updateTargetAttribute(newList);
    message.success('修改成功');
    closeEdit();
  }
};

const closeEdit = () => {
  editState.visible = false;
  formState.link = '';
};

const handleDelete = (index: number) => {
  const newList = props.data.filter((_, i) => i !== index);
  updateTargetAttribute(newList);
  message.success('删除成功');
};

// --- 配置项 ---

const columns = [
  { title: '选词', dataIndex: 'title', key: 'title', width: 150, ellipsis: true },
  { title: '地址', dataIndex: 'value', key: 'value', width: 300, ellipsis: true },
  { title: '描述', dataIndex: 'desc', key: 'desc', width: 150, ellipsis: true },
  { title: '操作', key: 'action', align: 'center', width: 220 },
];
</script>

<style scoped lang="scss">
:deep(.ant-table-tbody) {
  tr>td {
    border-bottom: 1px solid #f0f0f0 !important; // 恢复轻微线条感，增加可读性
  }
}

.iframe-container {
  width: 100%;
  height: 70vh;
  background: #f5f5f5;
  border-radius: 4px;
  overflow: hidden;
}

.flex {
  display: flex;
  align-items: center;
}

.space-x-2>*+* {
  margin-left: 0.5rem;
}
</style>