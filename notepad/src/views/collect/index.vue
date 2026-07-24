<template>
  <div class="np-collect-box">
    <div class="np-collect-action">
      <a-tooltip :title="mode === 'table' ? '切换至列表视图' : '切换至表格视图'" arrowPointAtCenter autoAdjustOverflow
        placement="topRight">
        <p @click="toggleMode">
          <appstore-outlined v-if="mode === 'table'" />
          <bars-outlined v-else />
        </p>
      </a-tooltip>
    </div>
    <div class="np-collect-content">
      <div class="h-10">
        <a-button size="small" type="primary" v-show="selectedRowKeys.length > 0" @click="removeAll">批量恢复
        </a-button>
      </div>
      <a-table v-if="mode === 'table'" rowKey="id" :columns="columns" :dataSource="state.list"
        :loading="!state.isFinished" :pagination="state.pagination" @resizeColumn="handleResizeColumn"
        :scroll="{ y: 600 }" :row-selection="rowSelection" :show-expand-column="false">
        <template #bodyCell="{ column, record, text }">
          <template v-if="column.dataIndex === 'operation'">
            <a-button primary size="small" style="margin: 0 10px" type="link" @click="onRestore(record)">已收藏
            </a-button>
          </template>
          <template v-else-if="column.dataIndex === 'title'">
            <a-button type="link" class="cursor-pointer" @click="click(record)">{{ text }}
            </a-button>
          </template>
        </template>
      </a-table>
      <np-card v-else :data="state.list" />
    </div>
  </div>
</template>
<script lang="tsx">
import { defineComponent, ref, onBeforeMount, watch } from "vue";
import { UseFetchReturn } from "@vueuse/core";
import { useFetch, usePagination } from "../../hooks/index";
import { Response } from "../../types/response";
import { message } from "ant-design-vue";
import Card from "../../components/Card/index.vue";
import { useRouter } from "vue-router";
import { NoteType } from "@/enum";

export default defineComponent({
  components: { NpCard: Card },
  setup() {
    const router = useRouter();
    const mode = ref("table");
    const selectedRowKeys = ref([]);
    const { state, getData } = usePagination({
      url: "/system/note/collectionList",
    });
  
    const toggleMode = () => {
      if (mode.value === "table") {
        mode.value = "card";
      } else {
        mode.value = "table";
      }
    };
    const onRestore = async (item: any) => {
      item = Array.isArray(item) ? item.toString() : item.id;
      const { data }: UseFetchReturn<Response> = await useFetch(
        `/system/note/cancelCollection/${item}`
      )
        .get()
        .json();
      if (data?.value) {
        if (data.value.code === 200) {
          message.success("已从收藏中移除");
          getData();
        }
      }
    };
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
    onBeforeMount(() => {
      getData();
    });
    const handleResizeColumn = (w: any, col: any) => {
      col.width = w;
    };
    const rowSelection = {
      onChange: (keys) => (selectedRowKeys.value = keys),
    };
    const removeAll = () => {
      if (selectedRowKeys.value.length === 0) {
        message.warning("请选择需要删除的数据");
        return;
      }
      onRestore(selectedRowKeys.value);
    };
    const click = (data) => {
      const type = {
        [NoteType.笔记]: "docx",
        [NoteType.表格]: "sheets",
        [NoteType.多维表格]: "base",
      };
      const { href } = router.resolve({
        name: type[data.noteType],
        params: {
          id: data.id,
        },
      });
      window.open(href, "_blank");
    };

    return {
      mode,
      click,
      state,
      columns,
      toggleMode,
      onRestore,
      removeAll,
      rowSelection,
      selectedRowKeys,
      handleResizeColumn,
    };
  },
});
</script>
<style lang="scss" scoped>
.np-collect-box {
  height: 100%;

  .np-collect-content {
    height: calc(100% - 50px);
    overflow: hidden;
  }

  .np-collect-action {
    text-align: right;

    >p {
      display: inline-block;
      cursor: pointer;
    }
  }
}

:deep(.ant-table) {
  .ant-table-body {
    height: 600px;
  }
}
</style>