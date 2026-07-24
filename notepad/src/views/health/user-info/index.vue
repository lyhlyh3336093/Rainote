<template>
  <div class="health-module">
    <a-card title="用户信息">
      <a-table rowKey="id" :columns="columns" :dataSource="state.list" :loading="!state.isFinished"
        :pagination="state.pagination" :scroll="{ y: 650 }">
        <template #bodyCell="{ record, column }">
          <template v-if="column.key === 'userName'">
            <a-button type="link">
              {{ record.userName }}
            </a-button>
          </template>
          <template v-if="column.key === 'tag'">
            <a-tag v-for="item in getTag(record.tag)" :key="item">{{ item }}</a-tag>
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
  param: {}
});
const getTag = (tag: string = '') => {
  if (tag === '' || !tag) return [];
  return tag.split(',') || []
}


onBeforeMount(() => {
  getData();
});

const columns = ref([
  {
    title: "用户ID",
    dataIndex: "id",
    key: "id",
    width: 120,
  },
  {
    title: "姓名",
    dataIndex: "userName",
    key: "userName",
    width: 150,
  },
  {
    title: "性别",
    dataIndex: "gender",
    key: "gender",
    width: 100,
  },
  {
    title: "年龄",
    dataIndex: "age",
    key: "age",
    width: 100,
  },
  {
    title: "人物身体信息",
    dataIndex: "bodyInfo",
    key: "bodyInfo",
    width: 150,
  },
  {
    title: "人物3D扫描信息",
    dataIndex: "threedDataPath",
    key: "threedDataPath",
    width: 150,
  },
  {
    title: "技能证书",
    dataIndex: "skillCertificate",
    key: "skillCertificate",
    width: 150,
  },
  {
    title: "标签",
    dataIndex: "tag",
    key: "tag",
    width: 150,
  },
  {
    title: "知识图谱",
    dataIndex: "knowledgeMap",
    key: "knowledgeMap",
    width: 150,
  },
  {
    title: "待办事项",
    dataIndex: "todolist",
    key: "todolist",
    width: 150,
  },
  {
    title: "物品",
    dataIndex: "item",
    key: "item",
    width: 150,
  },
  {
    title: "通讯录",
    dataIndex: "directory",
    key: "directory",
    width: 180,
  },
  {
    title: "备注",
    dataIndex: "remark",
    key: "remark",
    width: 150,
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
