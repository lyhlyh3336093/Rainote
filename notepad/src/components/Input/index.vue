<template>
  <a-input
    allowClear
    v-model:value="state"
    :ref="forwardRef"
    @change="onChange"
    placeholder="请输入"
    v-bind="$attrs"
  ></a-input>
</template>
<script lang="tsx">
import { defineComponent, ref } from "vue";

export default defineComponent({
  emits: ["update:value"],
  props: ["value", "forwardRef", "type"],
  name: "np-input",
  setup(props, ctx) {
    const value = Array.isArray(props.value)
      ? props.value.toString()
      : props.value;
    const state = ref(value);
    const onChange = () => {
      ctx.emit("update:value", state.value);
    };
    return {
      state,
      onChange,
    };
  },
});
</script>
