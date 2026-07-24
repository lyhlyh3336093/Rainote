<template>
  <div>
    <a-table
      rowKey="id"
      :columns="columns"
      :dataSource="state.list.filter((item) => item.noteType >= 2)"
      :loading="!state.isFinished"
      :pagination="state.pagination"
      @resizeColumn="handleResizeColumn"
      :scroll="{ y: 650 }"
      :show-expand-column="false"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.dataIndex === 'title'">
          <a-button type="link" class="cursor-pointer" @click="click(record)">
            {{ text }}
          </a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>
<script lang="tsx">
import { onBeforeMount, ref, watch } from "vue";
import { useJump, usePagination } from "../../hooks";

export default {
  setup() {
    const { click } = useJump();
   
    const { state, getData } = usePagination({
      url: "system/note/list",
    });

    onBeforeMount(() => {
      getData();
    });
    const columns = ref([
      {
        title: "文件名称",
        dataIndex: "title",
        key: "title",
        resizable: true,
        width: 500,
      },
      {
        title: "描述",
        dataIndex: "remark",
        key: "remark",
        resizable: true,
        width: 500,
      },
    ]);

    const handleResizeColumn = (w, col) => {
      col.width = w;
    };

    return {
      click,
      state,
      columns,
      handleResizeColumn,
    };
  },
};
</script>
<style scoped lang="scss">
:deep(.ant-table) {
  .ant-table-body {
    height: 650px;
  }
}
</style>