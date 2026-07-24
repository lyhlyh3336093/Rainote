<template>
  <a-menu @click="onClick" v-bind="$attrs">
    <a-menu-item v-for="item in columns" :key="item.id" :class="{ 'ant-menu-item-selected': group.active === item.id }">
      {{ item.name }}
    </a-menu-item>
  </a-menu>
</template>

<script lang="tsx">
import { defineComponent, inject } from 'vue';
import { useStore } from "../../../stores/table";
import { storeToRefs } from "pinia";

export default defineComponent({
  name: "Group",
  setup() {
    const store = useStore();
    const { group } = storeToRefs(store);
    const { columns } = inject<any>('store');
    const onClick = ({ key }) => {
      if (key === group.value.active) return;
      store.gourpChange(key);
   
    }
    return {
      columns,
      group,
      onClick,
    }
  }
})
</script>

<style scoped></style>