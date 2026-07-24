<template>
  <div class="np-doc">
    <a-spin :spinning="!state.isFinished">
      <Header :data="state.data" />
      <div class="np-doc-content">
        <NpEditor :id="noteId" :isModal="id ? true : false" :recordId="recordId" :tableId="tableId" :linkColumnId="linkColumnId" :linkItemId="linkItemId" :linkId="linkId" />
      </div>
    </a-spin>
  </div>
</template>
<script lang="tsx" setup>
import { reactive, onMounted, computed } from "vue";
import { useFetch } from "../../hooks";
import { useRoute } from "vue-router";
import Header from "../header/index.vue";
const props = defineProps(['id','recordId','tableId','linkColumnId','linkItemId','linkId'])
const state = reactive({
  isFinished: false,
  title: '',
  data: {}
});
import NpEditor from '../../components/editorjs/index.vue';

const route = useRoute();
const noteId = computed(() => {
  return props?.id ? props.id : route.params.id;
})

const getDetail = async () => {
  const { data, isFinished }: any = await useFetch(
    `/system/note/${noteId.value}`
  )
    .get()
    .json();
  state.isFinished = isFinished;
  if (data?.value) {
    state.title = data.value.data.title;
    state.data = data.value.data;
  }
};
const update = async () => {
  const { data } = await useFetch("/system/note/user/update")
    .post({ ...state.data, title: state.title })
    .json();
  if (data?.value) {
    getDetail();
  }
};

onMounted(() => {
  getDetail();
});
</script>
<style lang="scss" scoped>
.np-doc {
  width: 100%;
  //overflow: hidden;

  .np-doc-content {
    height: calc(100% - 72px);
    //overflow: hidden;
    border-top: 1px solid var(--np-line-border);

    #editorjs {
      max-width: 1000px;
      margin: 0 auto;
    }

    >div {
      height: 100%;
      overflow: auto;
    }
  }

  .doc-title {
    position: relative;
    padding: 20px 0;
    border-bottom: 1px solid #e8e8e8;

    :deep(.ant-input) {
      font-size: 34px;
      min-height: 55.25px;
      line-height: 1.625;
      color: #1f2329;
      font-weight: 600;
      padding: 0;
      margin: 0;
      border: none;
      resize: none;
      outline: none;
      box-shadow: none;

      &:hover {
        border: none;
        resize: none;
        outline: none;
        box-shadow: none;
      }
    }
  }
}
</style>
