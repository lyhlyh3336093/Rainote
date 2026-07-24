<template>
  <div>
    <div class="h-10">
      <a-button danger size="small" type="primary" v-show="selectedRowKeys.length > 0" @click="removeAll">批量删除
      </a-button>
    </div>
    <a-table rowKey="id" :columns="columns" :dataSource="state.list" :loading="!state.isFinished"
      :pagination="state.pagination" @resizeColumn="handleResizeColumn" :scroll="{ y: 650 }"
      :row-selection="rowSelection" :show-expand-column="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'operation'">
          <a-button primary size="small" style="margin: 0 10px" type="link" @click="onRestore(record)">恢复
          </a-button>
          <a-button danger size="small" type="link" @click="onDelete(record)">彻底删除
          </a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>
<script lang="tsx">
import { onBeforeMount, ref, watch } from "vue";
import { message, Modal } from "ant-design-vue";
import { useFetch, usePagination } from "@/hooks";
import type { TableProps } from "ant-design-vue";

export default {
  setup() {


    const { state, getData } = usePagination({
      url: "/system/note/garbageList",
    });
    const selectedRowKeys = ref([]);
    const onRestore = async (record) => {
      const { data } = await useFetch(`/system/note/recoverNote/${record.id}`)
        .get()
        .json();
      if (data?.value) {
        message.success("恢复成功");
        getData();
      }
    };
    const onDelete = async (record) => {
      Modal.confirm({
        centered: true,
        icon: null,
        title: `是否彻底删除?`,
        content: "彻底删除后，将无法恢复。",
        okText: "确认",
        cancelText: "取消",
        okButtonProps: {
          type: "primary",
          danger: true,
        },
        async onOk() {
          record = Array.isArray(record) ? record.toString() : record.id;
          const { data } = await useFetch(`/system/note/clearGarbage/${record}`)
            .get()
            .json();
          if (data?.value) {
            message.success("删除成功");
            getData();
          }
        },
      });
    };

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
      {
        align: "center",
        key: "remark",
        dataIndex: "operation",
        title: "操作",
      },
    ]);

    const handleResizeColumn = (w, col) => {
      col.width = w;
    };
    const rowSelection: TableProps["rowSelection"] = {
      onChange: (keys) => (selectedRowKeys.value = keys),
    };
    const removeAll = () => {
      if (selectedRowKeys.value.length === 0) {
        message.warning("请选择需要删除的数据");
        return;
      }
      onDelete(selectedRowKeys.value);
    };

    return {
      state,
      onRestore,
      onDelete,
      columns,
      removeAll,
      rowSelection,
      selectedRowKeys,
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