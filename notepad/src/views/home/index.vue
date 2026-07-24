<template>
  <div style="height: 95%">
    <Calendar/>
  </div>
</template>

<script setup>
import Calendar from '../todo/calendar.vue';
import usePagination from "../../hooks/usePagination.ts";
import {onMounted, provide} from "vue";
import {useUserStore} from "../../stores/user/index.ts";
const {userInfo} = useUserStore();
const {state, getData} = usePagination({
  url: `/system/task/list`,
  param: {
    leader: userInfo.user.userId,
    delFlag:0
  }
});

provide('state', state);
provide('getData', getData);
onMounted(() => {
  getData();
})
</script>
