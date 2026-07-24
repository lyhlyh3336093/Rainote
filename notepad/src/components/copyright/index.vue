<script setup lang="ts">
let year = new Date().getFullYear();
let record = '豫ICP备2023030607号';
</script>

<template>
  <div class="icp" v-bind="$attrs">备案号：{{ `© ${year} ` }}<a href="http://beian.miit.gov.cn/" target="_blank">{{ record }}</a></div>
</template>

<style scoped lang="scss">
.icp {
  height: 50px !important;
  text-align: center;
  line-height: 50px;
  font-size: 18px;
  position: absolute;
  bottom: 15px;
  left: 0;
  background: transparent !important;
}
</style>