<template>
  <a-modal
    v-model:open="visible1"
    :footer="null"
    title="新建文件夹"
    width="500px"
    @ok="close"
    centered
  >
    <a-form
      ref="formRef"
      :label-col="{ span: 6 }"
      :model="formState"
      :wrapper-col="{ span: 18 }"
      autocomplete="off"
      label-align="right"
      label-position="top"
      label-width="120px"
      name="basic"
      @finish="onFinish"
    >
      <a-form-item
        :rules="[{ required: true, message: '请输入文件名称' }]"
        label="文件名称"
        name="title"
      >
        <a-input v-model:value="formState.title" placeholder="请输入文件名称" />
      </a-form-item>

      <a-form-item label="描述" name="desc">
        <a-input v-model:value="formState.desc" />
      </a-form-item>
      <a-form-item :wrapper-col="{ span: 24 }" class="submit">
        <a-button @click="close">取消</a-button>
        <a-button html-type="submit" type="primary">创建</a-button>
      </a-form-item>
    </a-form>
  </a-modal>
</template>
<script lang="tsx">
import { FormInstance, message } from "ant-design-vue";
import { reactive, ref, defineComponent, inject, watch } from "vue";
import {  useFetch } from "../../hooks";
import {NoteType} from "@/enum";

export default defineComponent({
  props: ['visible', 'node'],
  emits: ['update:visible'],
  setup(props, ctx) {
    const { emit } = ctx;
    const {node}=props;
    const formRef = ref<FormInstance>()
    const formState = reactive({
      title: '',
      desc: '',
    });
    const store = inject<any>('store');

    const visible1 = ref(false);
    watch(() => props.visible, val => {
      visible1.value = val;
    });
    const close = () => emit('update:visible', false);
    watch(() => visible1.value, val => {
      if (!val) {
        close();
      }
    })
    const onFinish = async (values: any) => {
      const parentId=node?.data?.id||1;
      const { data } = await useFetch('/system/note/add').post({
        ...values,
        delFlag: 0,
        noteType: NoteType.文件夹,
        parentId
      }).json();
      if (data?.value) {
        message.success('文件夹创建成功');
        close();
        store.getMenu();
        formRef.value.resetFields();
      }
    }
    return {
      visible1,
      formState,
      close,
      formRef,
      onFinish
    }
  }
})
</script>
<style lang="scss" scoped>
.submit {
  text-align: right;

  .ant-btn {
    margin-left: 10px;
  }
}
</style>