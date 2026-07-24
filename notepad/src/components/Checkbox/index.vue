<template>
  <a-checkbox
    allowClear
    v-model:checked="state"
    :ref="forwardRef"
    @change="onChange"
    v-bind="$attrs"
  ></a-checkbox>
</template>
<script lang="tsx">
import { defineComponent, ref } from "vue";

export default defineComponent({
  emits: ["update:value"],
  props: ["value", "forwardRef", "type"],
  name: "np-checkbox",
  setup(props, ctx) {
    const state = ref(`${props.value}` === "0" ? true : false);
    const onChange = () => {
      ctx.emit("update:value", state.value ? '0' : '1');
    };
    return {
      state,
      onChange,
    };
  },
});
</script>
  