<template>
  <div class="health-module">
    <a-card title="科室表">
      <a-table
        rowKey="id"
        :columns="columns"
        :dataSource="state.list"
        :loading="!state.isFinished"
        :pagination="state.pagination"
        :scroll="{ y: 650 }"
      >
        
      </a-table>
    </a-card>
  </div>
</template>

<script lang="tsx" setup>
import { onBeforeMount, ref } from "vue";
import { usePagination } from "../../../hooks";

    const { state, getData } = usePagination({
      url: "health/department/list",
    });

    onBeforeMount(() => {
      getData();
    });

    const columns = ref([
      {
        title: "科室ID",
        dataIndex: "departmentCode",
        key: "departmentCode",
        width: 120,
      },
      {
        title: "科室名称",
        dataIndex: "departmentName",
        key: "departmentName",
        width: 200,
      },
    ]);


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

