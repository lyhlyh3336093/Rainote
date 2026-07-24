<template>
  <template v-for="item in data" :key="item.id">
    <a-sub-menu v-if="item.children && item.children.length > 0" :key="item.id">
      <template #title>
        <div class="flex justify-between">
          <a-dropdown v-if="item.id > 0 && item.parentId" trigger="contextmenu" class="drop-node-item"
            @contextmenu.prevent.stop>
            <div class="node-item flex-1">
              <template v-if="item.title.length > 10">
                <a-tooltip :title="item.title" placement="right">
                  <div class="node-item-title ellipsis">{{ item.title }}</div>
                </a-tooltip>
              </template>
              <template v-else>
                <div class="node-item-title">{{ item.title }}</div>
              </template>
              <div class="node-item-action opacity-0">
                <a-dropdown :trigger="['click']" @click.prevent.stop>
                  <span @click.prevent.stop>
                    <MoreOutlined style="font-size: 18px" />
                  </span>
                  <template #overlay>
                    <DropdownMenu :node="item" v-bind="$attrs" @rename="handleRename(item)" />
                  </template>
                </a-dropdown>
              </div>
            </div>

            <template #overlay>
              <DropdownMenu :list="data" :node="item" v-bind="$attrs" @rename="handleRename(item)" />
            </template>
          </a-dropdown>
          <span ref="subMenuRef" v-else>{{ item.title }}</span>
          <div v-if="item.id > 0 && !item.parentId" class="opacity-0" @click.prevent.stop="handleAddFolder(item)">
            <PlusOutlined style="font-size: 18px" />
          </div>
        </div>
      </template>
      <template #icon>
        <folder-outlined style="font-size: 18px" v-if="item.noteType === NoteType.文件夹" />
      </template>
      <menu-item :data="item.children" />
    </a-sub-menu>
    <a-menu-item v-else :key="item.id">
      <template #icon>
        <file-word-outlined style="font-size: 18px" v-if="[NoteType.笔记].includes(item.noteType)" />
        <file-excel-outlined style="font-size: 18px" v-else-if="[NoteType.表格, NoteType.多维表格].includes(item.noteType)" />
        <container-outlined v-else-if="item.id === -3" />
        <star-outlined v-else-if="item.id === -2" />
        <folder-outlined style="font-size: 18px" v-else-if="item.noteType === NoteType.待办事项" />
        <insert-row-above-outlined v-else />
      </template>
      <a-dropdown v-if="item.id > 0" trigger="contextmenu" class="drop-node-item" @contextmenu.prevent.stop>
        <div class="node-item">
          <template v-if="item.title.length > 10">
            <a-tooltip :title="item.title" placement="right">
              <div class="node-item-title ellipsis">{{ item.title }}</div>
            </a-tooltip>
          </template>
          <template v-else>
            <div class="node-item-title">{{ item.title }}</div>
          </template>
          <div class="node-item-action opacity-0">
            <a-dropdown :trigger="['click']" @click.prevent.stop>
              <span @click.prevent.stop>
                <MoreOutlined />
              </span>
              <template #overlay>
                <DropdownMenu :node="item" v-bind="$attrs" @rename="handleRename(item)" />
              </template>
            </a-dropdown>
          </div>
        </div>

        <template #overlay>
          <DropdownMenu :list="data" :node="item" v-bind="$attrs" @rename="handleRename(item)" />
        </template>
      </a-dropdown>
      <span ref="subMenuRef" v-else>{{ item.title }}</span>
    </a-menu-item>
  </template>
  <AddFolder v-model:visible="visibleState.create" :node="node" />
  <RenameFolder v-model:visible="visibleState.rename" :node="node" />
  <Tour v-model:current="current" :open="open" :steps="steps" @close="handleOpen(false)" @finish="handleFinish" />
</template>

<script lang="ts" setup>
import { reactive, ref, onMounted } from "vue";
import RenameFolder from "./ranemFolder.vue";
import AddFolder from "./addFolder.vue";
import DropdownMenu from "./dropdownMenu.vue";
import { NoteType } from "@/enum";
import { storeToRefs } from "pinia";
import { useUserStore } from "@/stores/user";
import { useFetch } from "@/hooks";
import { Tour } from "ant-design-vue";
const store = useUserStore();
const { userInfo } = storeToRefs(store);
const subMenuRef = ref();
interface MenuItem {
  id: number;
  title: string;
  noteType: NoteType;
  parentId?: number;
  children?: MenuItem[];
}
const open = ref(true);
const current = ref(0);
const steps = ref([]);
const handleOpen = (val) => {
  open.value = val;
};
const visibleState = reactive({
  create: false,
  rename: false,
});
const contents = [
  "这里是您个人的领域，您可以在这里创建和管理您的文件夹，组织您的文档和资源。在文件夹内通过鼠标悬浮或者右键菜单创建文档、多维表格等一些操作",
  "这是一个非常重要的站点。在这里，您可以提出任何遇到的问题或建议，我们的团队会及时响应，帮助您解决问题，让您的数字空间之旅更加顺畅。",
  "这里提供了丰富的指南和教程，无论您是初学者还是资深用户，都能找到适合自己的学习资源。通过这些教程，您可以掌握更多技巧，让您的数字空间变得更加高效和个性化。",
  "这里汇集了各种实用的模板，无论是工作文档、设计素材还是生活记录，您都可以在这里找到灵感，快速开始您的创作。",
  "这是一个安全的存放点，当您不小心删除了重要文件时，可以在这里找回它们。请记得定期清理，保持空间的整洁。",
  "这里是您珍藏的重要文件和资源的宝库。您可以将喜欢的内容添加到这里，方便随时访问和使用。",
];
const node = ref<MenuItem>();
const props = defineProps<{
  data: MenuItem[];
}>();

const handleAddFolder = (item: MenuItem) => {
  visibleState.create = true;
  node.value = item;
};

const handleRename = (item: MenuItem) => {
  visibleState.rename = true;
  node.value = item;
};
const handleFinish = async () => {
  const { data } = await useFetch(`system/note/updateFirstLogin/${userInfo.value.user.userId}`).get().json();
  if (data.value.code === 200) {
    userInfo.value.user.firstLogin = '1';
  }
};
onMounted(() => {
  setTimeout(() => {
    if (Array.isArray(subMenuRef.value) && userInfo.value.user.firstLogin === '0') {
      steps.value = subMenuRef.value.map((item, index) => {
        return {
          title: item.innerHTML,
          description: contents[index],
          target: () => item,
        };
      });
    }
  }, 1000);
});
</script>

<style lang="scss" scoped>
.ant-menu-item-icon {
  font-size: 18px;
}

.node-item-title {
  width: 88%;
}

.node-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;

  &-title {
    flex: 1;
    margin-right: 8px;
  }

  &-action {
    transition: opacity 0.2s;
  }
}

.drop-node-item {
  width: 100%;

  &:hover {
    .opacity-0 {
      opacity: 1;
    }
  }
}

.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
