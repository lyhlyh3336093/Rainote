<template>
  <a-modal
    v-model:open="visible1"
    :footer="null"
    title="名称"
    width="400px"
    @ok="close"
    centered
  >
    <a-form
      ref="formRef"
      :model="formState"
      :wrapper-col="{ span: 24 }"
      autocomplete="off"
      label-align="right"
      label-position="top"
      label-width="120px"
      name="basic"
      @finish="onFinish"
    >
      <a-form-item
        :rules="[{ required: true, message: '请输入文件名称' }]"
        name="title"
      >
        <a-input v-model:value="formState.title" placeholder="请输入文件名称" />
      </a-form-item>
      <a-form-item :wrapper-col="{ span: 24 }" class="submit">
        <a-button @click="close">取消</a-button>
        <a-button
          :disabled="node.title === formState.title"
          html-type="submit"
          type="primary"
          >确定</a-button
        >
      </a-form-item>
    </a-form>
  </a-modal>
</template>
<script lang="tsx">
import { useFetch } from "../../hooks";
import { reactive, ref, defineComponent, inject, watch } from "vue";
import { message } from "ant-design-vue";

export default defineComponent({
  props: ["visible", "node"],
  emits: ["update:visible"],
  setup(props, ctx) {
    const { emit } = ctx;
    const formRef = ref();
    const store: any = inject("store");
    const formState = reactive({
      title:'',
    });
    watch(()=>props.node,val=>{
      formState.title=val.title
    })

    const close = () => emit("update:visible", false);
    const visible1 = ref(false);
    watch(
      () => props.visible,
      (val) => {
        visible1.value = val;
      }
    );
    watch(
      () => visible1.value,
      (val) => {
        if (!val) {
          close();
        }
      }
    );
    const onFinish = async (values: any) => {
      const { parentId, id, noteType } = props.node;
      const { data } = await useFetch("/system/note/user/update")
        .post({
          ...values,
          id,
          delFlag: 0,
          noteType,
          parentId,
        })
        .json();
      if (data?.value) {
        close();
        store?.getMenu();
        message.success("重命名成功");
      }

      // const {data} = await useFetch('/system/note/add').post({
      //   ...values,
      //   delFlag: 0,
      //   noteType: 1,
      //   parentId: attrs.node.id
      // }).json();
      // console.log(data,'')
    };
    return {
      visible1,
      formState,
      close,
      formRef,
      onFinish,
    };
  },
});
</script>
<style lang="scss" scoped>
.submit {
  text-align: right;

  .ant-btn {
    margin-left: 10px;
  }
}
</style>