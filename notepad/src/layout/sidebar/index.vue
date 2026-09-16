<template>
  <a-layout-sider
    :width="isCollapsed ? 80 : 280"
    :collapsed="isCollapsed"
    :trigger="null"
    collapsible
  >
    <div class="sidebar-header">
      <a-input-search
        v-model:value="searchQuery"
        placeholder="搜索文件/文件夹"
        style="width: 100%"
        @search="handleSearch"
      />
    </div>
    <a-menu
      v-model:open-keys="openKeys"
      v-model:selected-keys="selectedKeys"
      @click="handleMenuClick"
      mode="inline"
    >
      <menu-item :data="filteredMenuItems" />
    </a-menu>
    <div class="flex justify-center items-center gap-2 my-1">
      <a-button @click="isAiChatVisible = true" type="primary">AI助手</a-button>
      <a-button @click="isChatVisible = true">进入聊天室</a-button>
      <a-button @click="toggleAgentPanel" type="primary" ghost>Agent</a-button>
    </div>
    <!-- <div class="sidebar-trigger" @click="toggleSidebar">
      <component :is="isCollapsed ? 'MenuUnfoldOutlined' : 'MenuFoldOutlined'" />
    </div> -->
  </a-layout-sider>
  <a-modal
    centered
    destroy-on-close
    v-model:open="isChatVisible"
    title="聊天室"
    :footer="null"
    width="1200px"
  >
    <div class="chat-container">
      <chat-room />
    </div>
  </a-modal>
  <a-modal
    centered
    destroy-on-close
    v-model:open="isAiChatVisible"
    title="AI助手"
    :footer="null"
    width="900px"
    :body-style="{ padding: 0, height: '600px' }"
    @cancel="handleAiChatClose"
  >
    <ai-chat-dialog />
  </a-modal>
</template>

<script lang="ts" setup>
import { storeToRefs } from "pinia";
import { useStore } from "../../stores/menu";
import { useAiChatStore } from "../../stores/aiChat";
import { useAgentStore } from "../../stores/agent";
import { onBeforeMount, ref, computed, provide } from "vue";
import { useRouter } from "vue-router";
import MenuItem from "./menu-item.vue";
import xeUtils from "xe-utils";
import { NoteType } from "../../enum";
import { MenuFoldOutlined, MenuUnfoldOutlined } from "@ant-design/icons-vue";
import ChatRoom from "../../views/chat/index.vue";
import AiChatDialog from "../../views/aiChat/index.vue";

const router = useRouter();
const isChatVisible = ref(false);
const isAiChatVisible = ref(false);
const aiChatStore = useAiChatStore();
const agentStore = useAgentStore();
const isCollapsed = ref(false);
const searchQuery = ref("");
const store = useStore();
const { list, openKeys, selectedKeys } = storeToRefs(store);

const filteredMenuItems = computed(() => {
  if (!searchQuery.value) return list.value;
  return filterTreeItems(list.value);
});

const filterTreeItems = (items) => {
  return items.filter((item) => {
    const matchesTitle = item.title
      .toLowerCase()
      .includes(searchQuery.value.toLowerCase());
    if (item.children) {
      item.children = filterTreeItems(item.children);
      return matchesTitle || item.children.length > 0;
    }
    return matchesTitle;
  });
};

onBeforeMount(() => {
  store.getMenu();
});
provide("store", store);

const toggleSidebar = () => {
  isCollapsed.value = !isCollapsed.value;
};

// R5b/Group D2 — 关闭AI助手弹框时取消正在进行的 SSE 流
const handleAiChatClose = () => {
  aiChatStore.abortStream();
};

/** 切换 Agent 面板开关（R1 入口） */
const toggleAgentPanel = () => {
  agentStore.togglePanel();
};

const handleSearch = (value) => {
  searchQuery.value = value;
};

const handleMenuClick = ({ key, keyPath }) => {
  const menuItem = xeUtils
    .toTreeArray(list.value)
    .find((item) => item.id === key);
  store.setOpenKeys(keyPath);
  if (menuItem.id > 0) {
    if (menuItem.noteType === NoteType.文件夹) {
      router.push(!menuItem.parentId ? "/me" : `/folder/${menuItem.id}`);
    } else {
      const noteTypeToRouteMap = {
        [NoteType.笔记]: "docx",
        [NoteType.表格]: "sheets",
        [NoteType.多维表格]: "base",
      };
      const { href } = router.resolve({
        name: noteTypeToRouteMap[menuItem.noteType],
        params: {
          id: menuItem.id,
        },
      });
      window.open(href, "_blank");
    }
  } else {
    router.push({
      name: menuItem.path,
    });
  }
};
</script>

<style lang="scss" scoped>
.ant-layout-sider {
  overflow: hidden;
  position: relative;
  transition: all 0.3s;

  .sidebar-header {
    padding: 16px;
    border-bottom: 1px solid #f0f0f0;
  }

  .sidebar-trigger {
    position: absolute;
    bottom: 16px;
    right: -16px;
    width: 16px;
    height: 16px;
    background: #fff;
    border: 1px solid #f0f0f0;
    border-left: none;
    border-radius: 0 4px 4px 0;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    z-index: 1;
    transition: all 0.3s;

    &:hover {
      background: #f5f5f5;
    }
  }

  .ant-menu {
    height: calc(100% - 120px);
    overflow: hidden auto;

    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      background: #ccc;
      border-radius: 3px;
    }

    &::-webkit-scrollbar-track {
      background: #f1f1f1;
    }
  }
}

.chat-container {
  width: 100%;
  height: 600px;
}

:deep(.np-tree) {
  .ant-tree-treenode {
    height: 40px;
    align-items: center;

    > span,
    .ant-tree-iconEle {
      line-height: 35px;
    }
  }

  .ant-tree-node-content-wrapper {
    display: block;
    overflow: hidden;

    &:hover {
      .node-item-action {
        opacity: 1;
      }
    }

    > span:first-child {
      float: left;
    }
  }
}

:deep(.drop-node-item),
:deep(.node-item) {
  display: flex;
  align-items: center;

  .node-item-action {
    margin-left: auto;
    margin-right: 15px;
  }
}

:deep(.ant-menu-submenu-title),
:deep(.ant-menu-item) {
  &:hover {
    .opacity-0 {
      opacity: 1 !important;
    }
  }
}

:deep(.ant-menu-item-selected) {
  background-color: #e6f7ff !important;
}
</style>
