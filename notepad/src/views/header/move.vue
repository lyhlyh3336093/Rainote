<template>
  <a-directory-tree
    :expandAction="false"
    :fieldNames="fieldNames"
    :tree-data="folderList"
    block-node
    class="np-tree"
    @select="selectTree"
    :height="500"
  />
</template>
<script lang="tsx">
import { storeToRefs } from "pinia";
import { useStore } from "../../stores/menu";
import { defineComponent, onBeforeMount, ref, computed, provide } from "vue";

export default defineComponent({
  setup() {
    const fieldNames = {
      children: "children",
      title: "title",
      key: "id",
    };
    const treeRef = ref();
    const store = useStore();
    const current = ref();
    const { folderList, fullList } = storeToRefs(store);
    onBeforeMount(() => {
      store.getMenu();
    });
    const selectTree = (key) => {
      current.value = fullList.value.find((item) => item.id === key[0]);
    };

    return {
      folderList,
      treeRef,
      fieldNames,
      selectTree,
      current,
    };
  },
});
</script>
<style lang="scss" scoped>
.ant-layout-sider {
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
}

:deep(.drop-node-item),
:deep(.node-item) {
  display: flex;
  align-items: center;

  .node-item-action {
    &.node-item-action-active {
      opacity: 1;
    }

    opacity: 0;
    margin-left: auto;
    margin-right: 15px;
  }
}
</style>