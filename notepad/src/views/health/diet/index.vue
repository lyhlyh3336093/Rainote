<template>
  <div class="health-module">
    <a-card title="饮食记录">
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

<script lang="tsx"  setup>
import { onBeforeMount, ref } from "vue";
import { usePagination } from "../../../hooks";

    const { state, getData } = usePagination({
      url: "health/diet/list",
    });

    onBeforeMount(() => {
      getData();
    });

    const columns = ref([
      {
        title: "记录ID",
        dataIndex: "recordId",
        key: "recordId",
        width: 120,
      },
      {
        title: "用户ID",
        dataIndex: "userId",
        key: "userId",
        width: 120,
      },
      {
        title: "用餐时间",
        dataIndex: "mealTime",
        key: "mealTime",
        width: 180,
      },
      {
        title: "餐次",
        dataIndex: "mealType",
        key: "mealType",
        width: 120,
      },
      {
        title: "食物名称",
        dataIndex: "foodName",
        key: "foodName",
        width: 200,
      },
      {
        title: "卡路里",
        dataIndex: "calories",
        key: "calories",
        width: 120,
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

