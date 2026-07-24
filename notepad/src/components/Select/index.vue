<template>
  <a-select :ref="forwardRef" v-model:value="state" :tagRender="tagRender"
    :allowClear="[FieldEnum.双向关联, FieldEnum.语义关联,FieldEnum.lookUp].includes(type) ? false : true" :showArrow="type !== FieldEnum.双向关联"
    :max-tag-count="10" @change="onChange" :options="props?.property?.select" placeholder="请选择"
    :class="[`type-${type}`]" :labelInValue="[FieldEnum.双向关联, FieldEnum.语义关联].includes(type) ? true : false"
    v-bind="{ ...$attrs, ...props }">
  </a-select>
</template>
<script lang="tsx">
import { defineComponent, ref } from "vue";
import { FieldEnum } from "@/enum";

export default defineComponent({
  name: "np-select",
  emits: ["tag-click", "update:value"],
  props: ["value", "type", "forwardRef", "props"],
  setup(props, ctx) {
    const { type, value } = props;
    const { list, columnId, record } = props.props;
    const data = list
      ?.find((item) => item.id === record.id)
      ?.linkRecordId[columnId]?.split(",");
    const _value = Array.isArray(value) ? value : value?.split(",") || [];
    const options = _value?.map((item, index) => {
      return {
        disable: true,
        label: item,
        value: type === FieldEnum.双向关联 ? data?.[index] || item : item,
      };
    });
    const state = ref(options || []);
    const onClick = (event, v) => {
      if (type === FieldEnum.双向关联) {
        event.cancelBubble = true;
        ctx.emit("tag-click", {
          ...props.props,
          label: v.label,
          value: v.value,
        });
      }
    };
    const tagRender = (value) => {
      return (
        <a-tag
          closable={value.closable}
          onClose={value.onClose}
          onClick={(event) => onClick(event, value)}
        >
          {value.label}
        </a-tag>
      );
    };
    const getPopupContainer = (el) => el.parentNode || el;
    const onChange = (value) => ctx.emit("update:value", value);
    return {
      FieldEnum,
      state,
      onChange,
      tagRender,
      getPopupContainer,
    };
  },
});
</script>
<style scoped lang="scss">
.ant-select {

  &.type-21,
  &.type-24 {
    :deep(.ant-select-dropdown) {
      display: none !important;
    }
  }
}
</style>