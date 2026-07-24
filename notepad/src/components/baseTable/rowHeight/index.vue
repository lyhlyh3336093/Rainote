<template>
  <a-menu @click="onClick">
    <a-menu-divider />
    <a-menu-item v-for="item in rowHeight.list" :key="item.value"
      :class="{ 'ant-menu-item-selected': rowHeight.active === item.value }">
      {{ item.label }}
    </a-menu-item>
  </a-menu>
</template>

<script lang="tsx">
import { defineComponent } from "vue";
import { useStore } from "../../../stores/table";
import { storeToRefs } from "pinia";

export default defineComponent({
  emits: ["action"],
  name: "RowHeight",
  setup(props, ctx) {
    const store = useStore();
    const { rowHeight } = storeToRefs(store);
    const onClick = ({ key }) => {
      if (rowHeight.value.active === key) return;
      store.rowHeightChange(key);
      ctx.emit("action", key);
    };
    return {
      rowHeight,
      onClick,
    };
  },
});
</script>

<style scoped></style>