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
        <template v-if="column.dataIndex === 'title'">
          <a-button primary size="small" style="margin: 0 10px" type="link" @click="toDoc(record)">{{ record.title }}
          </a-button>
        </template>
        <template v-if="column.dataIndex === 'operation'">
          <a-button danger size="small" type="link" @click="onDelete(record)">删除模板
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
import { useRouter } from "vue-router";

export default {
  setup() {
    const router = useRouter();

    const { state, getData } = usePagination({
      url: "/system/note/templateList",
    });
    const selectedRowKeys = ref([]);

    const onDelete = async (record) => {
      Modal.confirm({
        centered: true,
        icon: null,
        title: `是否删除?`,
        content: "删除模板不会删除笔记本身，只是笔记不再作为模板使用",
        okText: "确认",
        cancelText: "取消",
        okButtonProps: {
          type: "primary",
          danger: true,
        },
        async onOk() {
          record = Array.isArray(record) ? record.toString() : record.id;
          const { data } = await useFetch(`/system/note/removeTemplate/${record}`)
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
        title: "模板名称",
        dataIndex: "title",
        key: "title",
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

    const toDoc = (data) => {
      const { href } = router.resolve({
        name: 'docx',
        params: {
          id: data.id
        }
      });
      window.open(href, "_blank");
    }
    return {
      state,
      onDelete,
      toDoc,
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