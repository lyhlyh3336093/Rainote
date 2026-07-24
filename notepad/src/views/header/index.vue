<template>
  <a-page-header :ghost="false" @back="() => $router.push('/')">
    <template #title>
      <div v-if="state.titleEditing" class="title-edit" ref="titleEditRef">
        <a-input
          v-model:value="state.title"
          @pressEnter="updateTitle"
          auto-focus
        />
        <check-outlined class="save-icon" @click.stop="updateTitle" />
        <close-outlined class="cancel-icon" @click.stop="toggleTitleEdit" />
      </div>
      <div v-else class="title-display">
        {{ state.title }}
        <edit-outlined class="edit-icon" @click="toggleTitleEdit" />
      </div>
    </template>
    <template #tags>
      <div class="doc-tags">
        <a-tooltip :title="state.star ? '已收藏' : '收藏'">
          <span class="" @click="onAddStar">
            <star-filled v-if="state.star" class="star" />
            <star-outlined v-else />
          </span>
        </a-tooltip>
        <a-tooltip
          v-if="route.name === 'docx'"
          :title="state.templateFlag ? '撤销模板' : '设为模板'"
        >
          <span class="" @click="onAddTemplate">
            <!--          <a-tag color="#108ee9" v-if="props.data.templateFlag===0">模板</a-tag>-->
            <!--          <a-tag v-else>模板</a-tag>-->
            <container-outlined
              v-if="state.templateFlag"
              style="color: #1890ff"
            />
            <container-outlined v-else />
          </span>
        </a-tooltip>
      </div>
    </template>
    <template #extra>
      <a-button key="1" type="primary">分享</a-button>
      <a-button
        @click="state.templateVisible = true"
        v-if="route.name === 'docx'"
        key="2"
        >另存为新笔记</a-button
      >
      <a-dropdown :trigger="['click']">
        <ellipsis-outlined />
        <template #overlay>
          <a-menu @click="onClick">
            <template v-for="item in state.menu[route.name]">
              <a-sub-menu
                v-if="item.children"
                :title="item.title"
                :key="item.title"
              >
                <template #icon>
                  <component :is="item.icon"></component>
                </template>
                <template v-for="v in item.children" :key="v.title">
                  <a-menu-item
                    :class="{
                      'ant-dropdown-menu-item-selected':
                        config.page.width === v.value,
                    }"
                  >
                    {{ v.title }}
                  </a-menu-item>
                </template>
              </a-sub-menu>
              <template v-else>
                <a-menu-item :key="item.title">
                  <template #icon>
                    <component :is="item.icon"></component>
                  </template>
                  {{ item.title }}
                </a-menu-item>
              </template>
            </template>
          </a-menu>
        </template>
      </a-dropdown>
    </template>
  </a-page-header>
  <a-modal
    v-model:open="state.visible"
    title="移动到"
    width="500px"
    okText="确定"
    cancelText="取消"
    @ok="onMove"
    centered
    destroyOnClose
  >
    <Move ref="moveRef" />
  </a-modal>
  <a-modal
    v-model:open="state.infoVisible"
    title="文档信息"
    width="800px"
    :footer="null"
    centered
    destroyOnClose
  >
    <Info />
  </a-modal>
  <a-modal
    v-model:open="state.templateVisible"
    title="文档信息"
    width="600px"
    :footer="null"
    centered
    destroyOnClose
  >
    <Tamplate :id="data.id" v-model:visible="state.templateVisible" />
  </a-modal>
</template>
<script lang="tsx">
import {
  StarFilled,
  StarOutlined,
  ContainerOutlined,
  EllipsisOutlined,
  SplitCellsOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  LogoutOutlined,
  EditOutlined,
  CheckOutlined,
  CloseOutlined,
} from "@ant-design/icons-vue";
import { defineComponent, reactive, watch, ref } from "vue";
import { useFetch } from "../../hooks";
import { message } from "ant-design-vue";
import { useRoute } from "vue-router";
import { useStore } from "../../stores/editor";
import { storeToRefs } from "pinia";
import Move from "./move.vue";
import Info from "./info.vue";
import Tamplate from "./tamplate.vue";
import { useUserStore } from "../../stores/user";
import { NoteType } from "@/enum";

export default defineComponent({
  components: {
    Move,
    Info,
    Tamplate,
    StarFilled,
    StarOutlined,
    ContainerOutlined,
    EllipsisOutlined,
    SplitCellsOutlined,
    ExportOutlined,
    InfoCircleOutlined,
    LogoutOutlined,
    EditOutlined,
    CheckOutlined,
    CloseOutlined,
  },
  props: ["data"],
  setup(props) {
    const moveRef = ref(null);
    const route = useRoute();
    const store = useStore();
    const userStore = useUserStore();
    const { config, id } = storeToRefs(store);

    const state = reactive({
      star: false,
      title: "",
      originalTitle: "",
      titleEditing: false,
      templateFlag: false,
      visible: false,
      infoVisible: false,
      templateVisible: false,
      menu: {
        docx: [
          {
            title: "页宽设置",
            icon: "SplitCellsOutlined",
            children: [
              {
                title: "默认",
                value: "820px",
              },
              {
                title: "较宽",
                value: "1200px",
              },
              {
                title: "全宽",
                value: "90%",
              },
            ],
          },
          {
            title: "移动到",
            icon: "ExportOutlined",
          },
          {
            title: "文档信息",
            icon: "InfoCircleOutlined",
          },
          {
            title: "退出登录",
            icon: "LogoutOutlined",
          },
        ],
        base: [
          {
            title: "页宽设置",
            icon: "SplitCellsOutlined",
            children: [
              {
                title: "默认",
                value: "820px",
              },
              {
                title: "较宽",
                value: "1200px",
              },
              {
                title: "全宽",
                value: "90%",
              },
            ],
          },
          {
            title: "移动到",
            icon: "ExportOutlined",
          },
          {
            title: "文档信息",
            icon: "InfoCircleOutlined",
          },
          {
            title: "退出登录",
            icon: "LogoutOutlined",
          },
        ],
      },
    });

    const titleEditRef = ref(null);

    const handleClickOutside = (event) => {
      if (titleEditRef.value && !titleEditRef.value.contains(event.target)) {
        state.title = state.originalTitle;
        state.titleEditing = false;
      }
    };

    watch(
      () => state.titleEditing,
      (value) => {
        if (value) {
          document.addEventListener("click", handleClickOutside);
        } else {
          document.removeEventListener("click", handleClickOutside);
        }
      }
    );

    const toggleTitleEdit = (event) => {
      event.stopPropagation();
      if (!state.titleEditing) {
        state.title = state.originalTitle;
      }
      state.titleEditing = !state.titleEditing;
    };

    watch(
      () => props.data,
      (value) => {
        if (!value) return;
        state.star = value.collectionFlag;
        state.title = value.title;
        state.originalTitle = value.title;
        state.templateFlag = value.templateFlag;
      },
      { deep: true, immediate: true }
    );

    const onAddStar = async () => {
      let res: any = null;
      if (!props.data.collectionFlag) {
        res = await useFetch(`/system/note/collectionNote/${id.value}`)
          .get()
          .json();
      } else {
        res = await useFetch(`/system/note/cancelCollection/${id.value}`)
          .get()
          .json();
      }
      const { data } = res;
      if (data?.value) {
        props.data.collectionFlag = !props.data.collectionFlag;
        const msg = props.data.collectionFlag
          ? "已添加到收藏"
          : "已从收藏中移除";
        message.success(msg);
      }
    };

    const action = {
      页宽设置: (key) => {
        const value = state.menu[route.name]
          .find((item) => item.title === key.keyPath[0])
          ?.children.find((item) => item.title === key.key)?.value;
        store.updatePage("width", value);
      },
      移动到: () => {
        state.visible = true;
      },
      文档信息: () => {
        state.infoVisible = true;
      },
      退出登录: () => {
        userStore.logout();
      },
    };

    const onClick = (key) => {
      action?.[key.keyPath[0]]?.(key);
    };

    const onMove = async () => {
      state.visible = false;
      const parent = moveRef.value.current;
      if (!parent) return;
      const { data } = await useFetch("/system/note/user/update")
        .post({
          id: id.value,
          title: props.data.title,
          noteType: 1, // Using 1 for 笔记 as NoteType enum seems missing
          parentId: parent.id,
        })
        .json();
      if (data?.value) {
        message.success("移动成功");
      }
    };

    const onAddTemplate = async () => {
      const { data } = await useFetch(
        `/system/note/${
          props.data.templateFlag === 1 ? "removeTemplate" : "setTemplate"
        }/${id.value}`
      )
        .get()
        .json();
      if (data?.value) {
        props.data.templateFlag = !props.data.templateFlag;
        message.success("添加模板成功");
      }
    };

    const onSaveTemplate = () => {
      // Implementation kept empty as in original
    };

    const updateTitle = async () => {
      if (props.data.title === state.title) {
        state.titleEditing = false;
        return;
      }

      try {
        const { data } = await useFetch("/system/note/user/update")
          .post({
            ...props.data,
            title: state.title,
          })
          .json();

        if (data?.value) {
          props.data.title = state.title;
          state.originalTitle = state.title;
          message.success("标题修改成功");
        }
      } catch (error) {
        state.title = state.originalTitle;
        message.error("标题修改失败");
      } finally {
        state.titleEditing = false;
      }
    };

    return {
      route,
      onAddStar,
      onAddTemplate,
      onSaveTemplate,
      config,
      state,
      onClick,
      onMove,
      moveRef,
      toggleTitleEdit,
      updateTitle,
      titleEditRef,
    };
  },
});
</script>
<style lang="scss" scoped>
.star {
  color: var(--np-y-500);
}

.ant-page-header-heading-tags {
  > span {
    cursor: pointer;
  }
}

.doc-tags {
  span {
    display: inline-block;
    margin: 0 1px;
    cursor: pointer;
  }
}

.title-edit {
  display: flex;
  align-items: center;
  gap: 8px;

  .ant-input {
    width: 200px;
  }

  .save-icon,
  .cancel-icon {
    cursor: pointer;
    font-size: 16px;

    &:hover {
      color: #1890ff;
    }
  }
}

.title-display {
  display: flex;
  align-items: center;
  gap: 8px;

  .edit-icon {
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.3s;
  }

  &:hover .edit-icon {
    opacity: 1;
  }
}

// .ant-page-header {
//   border-bottom: 1px solid var(--np-line-boder-card);
// }
</style>