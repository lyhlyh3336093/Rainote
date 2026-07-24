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
          <a-button @click="remove(item)" type="link">移除黑名单</a-button>
        </template>
        <a-skeleton avatar :title="false" :loading="!!item.loading" active>
          <a-list-item-meta
          >
            <template #title>
              {{ item.userName }}
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
import {FriendEnum} from "@/enum";

const emits = defineEmits(['remove']);
const props = defineProps(['userInfo']);
const initLoading = ref(true);
const list = ref([]);
const getList = async () => {
  const {data}: any = await useFetch(`/system/friend/selectFriendBlockList?userId=${props.userInfo.user.userId}`).get().json();
  list.value = data.value.data;
  initLoading.value = false;

}
const remove = async item => {
  const {data} = await useFetch(`/system/friend/edit`).post({
    "friendId": props.userInfo.user.userId,
    "userId": item.userId,
    status: FriendEnum.好友
  }).json();
  if (data?.value?.code === 200) {
    message.success('操作成功');
    getList();
  }
}
onMounted(() => {
  getList();
});

</script>
<style scoped>
.request-list {
  min-height: 200px;
  max-height: 500px;
  overflow: auto;
}
</style>
