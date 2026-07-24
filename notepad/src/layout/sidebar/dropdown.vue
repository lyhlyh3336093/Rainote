<template>
  <template v-if="!node.data.parentId">
    <div class="node-item">
      <div class="node-item-title">{{ node.title }}</div>
      <div v-if="node.data.id > 0" class="node-item-action" @click.prevent.stop="onAddFolder">
        <PlusOutlined />
      </div>
    </div>

  </template>
  <template v-else>
    <a-dropdown :trigger="['contextmenu']" class="drop-node-item" @contextmenu.prevent.stop>
      <div class="node-item">
        <a-tooltip v-if="node.title.length > 10" :title="node.title" placement="right">
          <div class="node-item-title ellipsis">{{ node.title }}</div>
        </a-tooltip>
        <div v-else class="node-item-title">{{ node.title }}</div>
        <div :class="['node-item-action']">
          <a-dropdown :trigger="['click']" @click.prevent.stop>
            <span @click.prevent.stop>
              <MoreOutlined />
            </span>
            <template #overlay>
              <a-menu class="np-file-menu" @click="handleMenuClick">
                <template v-for="menuItem in filteredMenuItems" :key="menuItem.id">
                  <a-sub-menu v-if="menuItem.children && menuItem.children.length > 0" :key="'sub-'+menuItem.id" :title="menuItem.label">
                    <a-menu-item v-for="childItem in menuItem.children" :key="childItem.id">
                      {{ childItem.label }}
                    </a-menu-item>
                  </a-sub-menu>
                  <a-menu-item v-else :key="'item-'+menuItem.id">{{ menuItem.label }}</a-menu-item>
                </template>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </div>

      <template #overlay>
        <a-menu class="np-file-menu" @click="handleMenuClick">
          <template v-for="menuItem in filteredMenuItems" :key="menuItem.id">
            <a-sub-menu v-if="menuItem.children && menuItem.children.length > 0" :key="'sub-'+menuItem.id" :title="menuItem.label">
              <a-menu-item v-for="childItem in menuItem.children" :key="childItem.id">
                {{ childItem.label }}
              </a-menu-item>
            </a-sub-menu>
            <a-menu-item v-else :key="'item-'+menuItem.id">{{ menuItem.label }}</a-menu-item>
          </template>
        </a-menu>
      </template>
    </a-dropdown>
  </template>
  <AddFolder v-model:visible="visibleState.create" :node="node" />
  <RenameFolder v-model:visible="visibleState.rename" :node="node" />
</template>

<script lang="tsx">
import { useRouter } from "vue-router";
import AddFolder from "./addFolder.vue";
import RenameFolder from "./ranemFolder.vue";
import { message, Modal } from "ant-design-vue";
import { defineComponent, reactive, inject, computed } from "vue";
import useFetch from "../../hooks/useFetch";
import { NoteType } from "../../enum";

interface MenuItem {
  id: string;
  label: string;
  value: string;
  authCode: number[];
  noteType?: number;
  children?: {
    id: string;
    label: string;
    value: string;
    noteType: number;
    authCode: number[];
  }[];
}

interface FolderNode {
  data: {
    id: number;
    parentId?: number;
    title: string;
    noteType: number;
  };
  title: string;
}

interface StoreInterface {
  getMenu?: () => void;
  clear?: () => void;
}

export default defineComponent({
  components: { 
    AddFolder, 
    RenameFolder
  },
  props: {
    node: {
      type: Object as () => FolderNode,
      required: true
    }
  },
  setup(props) {
    const { node } = props;
    const visibleState = reactive({
      create: false,
      rename: false,
    });
    const store = inject("store") as StoreInterface;
    const router = useRouter();
    
    const menuItems = reactive<MenuItem[]>([
      {
        id: "1",
        label: "新建",
        value: "create",
        authCode: [1],
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

    const filteredMenuItems = computed(() => {
      return menuItems.filter(item => {
        return item.authCode?.includes(node.data.noteType);
      });
    });

    const removeItem = () => {
      Modal.confirm({
        icon: null,
        title: `是否删除${node.data.title}?`,
        content: "删除的内容将进入你的云文档回收站，30 天后自动彻底删除。",
        okText: "确认",
        cancelText: "取消",
        centered: true,
        okButtonProps: {
          type: "primary",
          danger: true,
        },
        async onOk() {
          const { data } = await useFetch(`/system/note/remove/${node.data.id}`)
            .get()
            .json();
          if (data?.value) {
            message.success("删除成功");
            if (store?.getMenu) store.getMenu();
          }
        },
      });
    };
    
    const fileNames = {
      docx: "未命名文件",
      sheets: "未命名表格",
      base: "未命名多维表格",
    };
    
    const createItem = async (noteType: number, value: string) => {
      const params = {
        parentId: node.data.id,
        title: fileNames[value as keyof typeof fileNames],
        noteType,
        delFlag: 0,
      };
      const { data } = await useFetch("/system/note/add").post(params).json();
      if (data?.value) {
        if (store?.getMenu) store.getMenu();
        if (store?.clear) store.clear();
        const { href } = router.resolve({
          name: value,
          params: {
            id: data.value.data,
          },
        });
        window.open(href, "_blank");
      }
    };

    const handleMenuClick = ({ key }: { key: string }) => {
      let selectedItem: MenuItem | undefined;
      
      for (const parent of menuItems) {
        if (key === 'item-' + parent.id || key === 'sub-' + parent.id) {
          selectedItem = parent;
          break;
        }
        if (parent.children && parent.children.length > 0) {
          for (const child of parent.children) {
            if (key === child.id) {
              selectedItem = child;
              break;
            }
          }
          if (selectedItem) break;
        }
      }
      
      if (!selectedItem) return;
      const { noteType, value } = selectedItem;
      
      if (value === "rename") {
        visibleState.rename = true;
        return;
      }
      if (value === "delete") {
        removeItem();
        return;
      }
      if (noteType !== undefined) {
        createItem(noteType, value);
      }
    };

    const onAddFolder = () => {
      visibleState.create = true;
    };
    
    return {
      filteredMenuItems,
      handleMenuClick,
      visibleState,
      onAddFolder,
    };
  },
});
</script>
<style lang="scss" scoped>
:deep(body) {
  .ant-dropdown-menu {
    padding: 0;
    margin: 0;
  }
}
</style>
