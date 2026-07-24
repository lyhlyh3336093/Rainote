<template>
  <a-config-provider :locale="locale">
    <a-layout>
      <SideBar/>
      <Container/>
    </a-layout>
    <Copyright/>
  </a-config-provider>
</template>
<script setup>
import SideBar from './sidebar/index.vue';
import Container from './container/index.vue';
import locale from 'ant-design-vue/es/locale/zh_CN';
import Copyright from "@/components/copyright/index.vue";

// const getPopupContainer = el => el?.parentNode || el;

</script>

<style scoped>
.ant-layout{
  height: calc(100% - 50px);
}
</style>