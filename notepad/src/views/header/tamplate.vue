<script name="AddTemplate" setup lang="ts">
import {reactive} from "vue";
import {useFetch} from "@/hooks";
import {NoteType} from "@/enum";
import {message} from "ant-design-vue";

const props = defineProps(['id']);
const emits = defineEmits(['update:visible']);
const formState = reactive({
  title: ''
});
const onFinish = async ({title}) => {
  const {data} = await useFetch('/system/note/saveAsNewNote').post({
    delFlag: 0,
    id: props.id,
    noteType: NoteType.笔记,
    title,
  }).json();
  if (data?.value) {
    window.open(data.value.data);
    message.success('添加模板成功');
    cancel();
  }

}
const cancel = () => {
  emits('update:visible', false);
}
</script>

<template>
  <a-form
      :model="formState"
      name="basic"
      autocomplete="off"
      @finish="onFinish"
  >
    <a-form-item
        label="新标题"
        name="title"
        :rules="[{ required: true, message: '请输入新标题' }]"
    >
      <a-input v-model:value="formState.title"/>
    </a-form-item>

    <a-form-item class="text-right">
      <a-button class="mr-2" @click="cancel">取消</a-button>
      <a-button html-type="submit" type="primary">确认</a-button>
    </a-form-item>
  </a-form>
</template>

<style scoped lang="scss">

</style>