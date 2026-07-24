<template>
  <div class="health-module">
    <a-card title="体检项目">
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
      url: "health/examination/list",
    });

    onBeforeMount(() => {
      getData();
    });

    const columns = ref([
      {
        title: "数据",
        dataIndex: "projectCode",
        key: "projectCode",
        width: 120,
      },
      {
        title: "机构",
        dataIndex: "projectName",
        key: "projectName",
        width: 200,
      },
      {
        title: "ID",
        dataIndex: "projectCode",
        key: "projectCode",
        width: 120,
      },
      {
        title: "体检项目",
        dataIndex: "projectName",
        key: "projectName",
        width: 200,
      },
      {
        title: "重要程度",
        dataIndex: "projectType",
        key: "projectType",
        width: 150,
      },
      {
        title: "报告链接",
        dataIndex: "price",
        key: "price",
        width: 120,
      },
      {
        title: "链接类型",
        dataIndex: "description",
        key: "description",
        width: 300,
      },
      {
        title: "机构",
        dataIndex: "createTime",
        key: "createTime",
        width: 180,
      },
      {
        title: "费用",
        dataIndex: "createTime",
        key: "createTime",
        width: 180,
      },
      {
        title: "评分",
        dataIndex: "createTime",
        key: "createTime",
        width: 180,
      },
      {
        title: "治疗方法",
        dataIndex: "createTime",
        key: "createTime",
        width: 180,
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

