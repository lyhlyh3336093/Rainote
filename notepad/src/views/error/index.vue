<template>
  <a-result status="404" sub-title="Sorry, the page you visited does not exist." title="404">
    <template #extra>
      <a-button type="primary" @click="back">返回首页</a-button>
    </template>
  </a-result>
</template>
<script setup>
import {useRouter} from 'vue-router';

const router = useRouter();
const back = () => {
  router.push('/home');
}
</script>
<style scoped>
.el-row {
  height: 100%;
  align-items: center;
  margin-top: -150px;
  justify-content: center;
}
</style>