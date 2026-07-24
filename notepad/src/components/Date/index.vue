<template>
  <a-date-picker :getPopupContainer="getPopupContainer" v-model:value="state" :ref="forwardRef" :locale="locale" valueFormat="YYYY-MM-DD" @change="onChange"
    v-bind="$attrs">
  </a-date-picker>
</template>
<script lang="tsx">
import { defineComponent, ref } from "vue";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import locale from "ant-design-vue/es/date-picker/locale/zh_CN";
export default defineComponent({
  emits: ["update:value"],
  props: ["value", "type", "forwardRef"],
  name: "np-picker",
  setup(props, ctx) {
    dayjs.locale("zh-cn");
    const value = Array.isArray(props.value)
      ? props.value.toString()
      : props.value;
    const state = ref(value);
    const onChange = (value) =>{
       ctx.emit("update:value", value)
    };
    const getPopupContainer = (el) => el.parentNode || el;

    return {
      state,
      locale,
      onChange,
      getPopupContainer,
    };
  },
});
</script>
