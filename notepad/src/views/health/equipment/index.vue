<template>
  <div class="health-module">
    <a-card title="设备表">
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
      url: "health/equipment/list",
    });

    onBeforeMount(() => {
      getData();
    });

    const columns = ref([
      {
        title: "设备ID",
        dataIndex: "equipmentCode",
        key: "equipmentCode",
        width: 120,
      },
      {
        title: "设备名称",
        dataIndex: "equipmentName",
        key: "equipmentName",
        width: 200,
      }
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

