<template>
  <div class="app-title">
    <div>
      <a-breadcrumb :separator-icon="'ArrowRight'">
<!--        <transition-group name="breadcrumb">-->
        <a-breadcrumb-item
              v-for="item in list"
              :key="item.path"
              :to="{ path: item.path }"
          >
            {{ item.meta.title }}
          </a-breadcrumb-item>
<!--        </transition-group>-->
      </a-breadcrumb>
    </div>
    <div>
<!--      <Operations/>-->
    </div>
  </div>
</template>
<script setup>
import {watch, ref} from 'vue';
import {useRoute} from 'vue-router';
// import Operations from '../operations/index.vue';

let route = useRoute();
watch(route, () => {
  getBreadcrumb();
});
const list = ref([]);
const getBreadcrumb = () => {
  let matched = route.matched.filter(item => item.meta?.title);
  list.value = matched.filter(item => item.meta?.title && item.meta.breadcrumb !== false);
}
getBreadcrumb();


</script>
<style lang="scss" scoped>
.app-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>