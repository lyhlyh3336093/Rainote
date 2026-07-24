<template>
  <a-list
      class="request-list"
      :loading="initLoading"
      item-layout="horizontal"
      :data-source="list"
  >

    <template #renderItem="{ item }">
      <a-list-item>
        <template #actions>
          <a-button @click="agree(item)" type="link">同意</a-button>
          <a-button @click="refuse(item)" danger type="link">拒绝</a-button>
        </template>
        <a-skeleton avatar :title="false" :loading="!!item.loading" active>
          <a-list-item-meta
          >
            <template #title>
              {{ item.requestName }}
            </template>
          </a-list-item-meta>
        </a-skeleton>
      </a-list-item>
    </template>
  </a-list>
</template>
<script lang="ts" setup>
import {onMounted, ref} from 'vue';
import useFetch from "@/hooks/useFetch";
import {message} from "ant-design-vue";

const emits = defineEmits(['agree']);
const props = defineProps(['userInfo']);
const initLoading = ref(true);
const list = ref([]);
const getAllUser = async () => {
  const {data}: any = await useFetch(`/system/request/list`).get().json();
  list.value = data.value.data;
  initLoading.value = false;
}
const agree = async item => {
  await useFetch(`/system/friend/add`).post({
    "friendId": props.userInfo.user.userId,
    "userId": item.userId,
  }).json();
  message.success('操作成功');
  getAllUser();
  emits('agree');

}
const refuse = async item => {
  await useFetch(`/system/request/refuse`).post(item).json();
  message.success('操作成功');
  getAllUser();
}
onMounted(() => {
  getAllUser();
});

</script>
<style scoped>
.request-list {
  min-height: 200px;
  max-height: 500px;
  overflow: auto;
}
</style>
