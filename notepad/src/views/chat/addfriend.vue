<template>
  <a-list
      class="friend-list"
      :loading="initLoading"
      item-layout="horizontal"
      :data-source="list"
  >

    <template #renderItem="{ item }">
      <a-list-item>
        <template #actions>
          <a-popover v-model:visible="item.visible" placement="right" title="发送添加好友申请" trigger="click">
            <template #content>
              <div class="flex flex-col">
                <a-textarea v-model:value="item.description" :bordered="false" :rows="3"/>
                <a-button @click="addFriend(item)" type="primary">发送</a-button>
              </div>
            </template>
            <a-button type="link">申请添加好友</a-button>
          </a-popover>
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

const props = defineProps(['userInfo']);
const initLoading = ref(true);
const list = ref([]);
const getAllUser = async () => {
  const {data}: any = await useFetch(`/system/user/list`).get().json();
  list.value = data.value.rows.map(item => {
    return {
      ...item,
      visible: false,
      description: `我是${props.userInfo.user.userName}`,
    }
  });
  initLoading.value = false;

}
const addFriend = async (item) => {
  await useFetch(`/system/request/add`).post({
    "requestId": props.userInfo.user.userId,
    "userId": item.userId,
    "requestName": item.userName,
    "description": item.description
  }).json();
  message.success('发送成功');
  item.visible = false;
}
onMounted(() => {
  getAllUser();
});

</script>
<style scoped>
.friend-list {
  min-height: 200px;
  max-height: 500px;
  overflow: auto;
}
</style>
