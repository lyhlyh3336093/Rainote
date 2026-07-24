<template>
  <div class="health-module">
    <a-card title="检测流程表">
      <a-table
        rowKey="id"
        :columns="columns"
        :dataSource="state.list"
        :loading="!state.isFinished"
        :pagination="state.pagination"
        :scroll="{ y: 650 }"
      >
        <template #bodyCell="{ column, record, text }">
          <template v-if="column.dataIndex === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="handleEdit(record)">编辑</a-button>
              <a-button type="link" size="small" danger @click="handleDelete(record)">删除</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script lang="tsx" setup>
import { onBeforeMount, ref } from "vue";
import { usePagination } from "../../../hooks";

    const { state, getData } = usePagination({
      url: "system/userinfo/list",
    });

    onBeforeMount(() => {
      getData();
    });

    const columns = ref([
      {
        title: "流程编号",
        dataIndex: "processCode",
        key: "processCode",
        width: 120,
      },
      {
        title: "流程名称",
        dataIndex: "processName",
        key: "processName",
        width: 200,
      },
      {
        title: "所属科室",
        dataIndex: "departmentName",
        key: "departmentName",
        width: 150,
      },
      {
        title: "负责人",
        dataIndex: "director",
        key: "director",
        width: 120,
      },
      {
        title: "流程步骤",
        dataIndex: "steps",
        key: "steps",
        width: 200,
      },
      {
        title: "状态",
        dataIndex: "status",
        key: "status",
        width: 100,
      },
      {
        title: "创建时间",
        dataIndex: "createTime",
        key: "createTime",
        width: 180,
      },
      {
        title: "操作",
        dataIndex: "action",
        key: "action",
        width: 150,
        fixed: "right",
      },
    ]);

    const handleEdit = (record) => {
      console.log("编辑", record);
    };

    const handleDelete = (record) => {
      console.log("删除", record);
    };

</script>

<style scoped lang="scss">
.health-module {
  padding: 20px;
  height: 100%;
  
  :deep(.ant-card) {
    height: 100%;
    
    .ant-card-body {
      height: calc(100% - 57px);
    }
  }
  
  :deep(.ant-table) {
    .ant-table-body {
      height: 650px;
    }
  }
}
</style>

