<script setup lang="ts">
import {ref} from 'vue';

const search = ref();
const noText = ref(false);
const haveText = ref(false);

const change = () => {
}

const del = () => {
}
</script>

<template>
  <div class="wrapper">
    <div class="search-wrapper">
      <a-input-search allow-clear v-model:value="search" class="searchInput" @keyup="change" placeholder="搜索"/>
      <i class="icon iconfont icon-search" v-show="noText"></i>
      <div class="searchInput-delete" v-show="haveText" @click="del"></div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.wrapper {
  padding: 22px 12px 12px 12px;

  :deep(.ant-btn),
  :deep(.ant-input-group-addon),
  :deep(.ant-input-affix-wrapper),
  :deep(.ant-input) {
    background: transparent;
  }

  :deep(.ant-input-affix-wrapper) {
    background: transparent;

    .ant-input {
      color: inherit;
    }
  }
}
</style>
