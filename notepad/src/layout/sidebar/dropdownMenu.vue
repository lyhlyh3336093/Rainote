<script setup lang="ts">
import { inject, reactive, computed } from "vue";
import { message, Modal } from "ant-design-vue";
import useFetch from "@/hooks/useFetch";
import { useRouter } from "vue-router";
import { NoteType } from '../../enum';

// Define interfaces
interface MenuItem {
  id: string;
  label: string;
  value: string;
  authCode: NoteType[];
  noteType?: NoteType;
  children?: MenuItem[];
}

interface NodeProps {
  id: string;
  title: string;
  noteType: NoteType;
}

// Props and emits
const props = defineProps<{
  node: NodeProps;
}>();

const emits = defineEmits(['rename']);

// Store and router
const store: any = inject("store");
const router = useRouter();

// Menu structure
const menuItems = reactive<MenuItem[]>([
  {
    id: "1",
    label: "新建",
    value: "create",
    authCode: [NoteType.文件夹],
    children: [
      {
        id: "1-1",
        label: "文档",
        value: "docx",
        noteType: NoteType.笔记,
        authCode: [NoteType.文件夹],
      },
      {
        id: "1-2",
        label: "表格",
        value: "sheets",
        noteType: NoteType.表格,
        authCode: [NoteType.文件夹],
      },
      {
        id: "1-3",
        label: "多维表格",
        value: "base",
        noteType: NoteType.多维表格,
        authCode: [NoteType.文件夹],
      },
    ],
  },
  {
    authCode: [NoteType.文件夹, NoteType.笔记, NoteType.表格, NoteType.多维表格],
    id: "2",
    label: "重命名",
    value: "rename",
  },
  {
    authCode: [NoteType.文件夹, NoteType.笔记, NoteType.表格, NoteType.多维表格],
    id: "3",
    label: "删除",
    value: "delete",
  },
]);

// Computed properties
const filteredMenuItems = computed(() => menuItems.filter(item => item.authCode?.includes(props.node.noteType)));

// Default file names
const defaultFileNames: Record<string, string> = {
  docx: "未命名文件",
  sheets: "未命名表格",
  base: "未命名多维表格",
};

// Methods
const handleDelete = async (): Promise<void> => {
  Modal.confirm({
    icon: null,
    title: `是否删除${props.node.title}?`,
    content: "删除的内容将进入你的云文档回收站，30 天后自动彻底删除。",
    okText: "确认",
    cancelText: "取消",
    centered: true,
    okButtonProps: {
      type: "primary",
      danger: true,
    },
    async onOk() {
      const { data } = await useFetch(`/system/note/remove/${props.node.id}`)
        .get()
        .json();
      if (data?.value) {
        message.success("删除成功");
        store?.getMenu();
      }
    },
  });
};

const handleCreate = async (noteType: NoteType, value: string): Promise<void> => {
  const params = {
    parentId: props.node.id,
    title: defaultFileNames[value],
    noteType,
    delFlag: 0,
  };
  const { data } = await useFetch("/system/note/add").post(params).json();
  if (data?.value) {
    store.getMenu();
    const { href } = router.resolve({
      name: value,
      params: {
        id: data.value.data,
      },
    });
    window.open(href, "_blank");
  }
};

const handleMenuItemClick = (item: MenuItem): void => {
  if (item.value === "rename") {
    emits('rename');
    return;
  }
  if (item.value === "delete") {
    handleDelete();
    return;
  }
  if (item.noteType) {
    handleCreate(item.noteType, item.value);
  }
};
</script>

<template>
  <a-menu>
    <template v-for="item in filteredMenuItems" :key="item.id">
      <!-- Submenu items with children -->
      <template v-if="item.children && item.children.length > 0">
        <a-sub-menu :key="item.id">
          <template #title>{{ item.label }}</template>
          <a-menu-item v-for="sub in item.children" :key="sub.id" @click="handleMenuItemClick(sub)">
            {{ sub.label }}
          </a-menu-item>
        </a-sub-menu>
      </template>

      <!-- Regular menu items -->
      <template v-else>
        <a-menu-item :key="item.id" @click="handleMenuItemClick(item)">
          {{ item.label }}
        </a-menu-item>
      </template>
    </template>
  </a-menu>
</template>

<style scoped lang="scss"></style>