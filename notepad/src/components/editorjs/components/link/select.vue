<template>
  <div class="p-1 ">
    <a-select v-bind="$attrs" v-model:value="value" @change="link = null, desc = ''">
      <a-select-option value="外部链接">外部链接</a-select-option>
      <a-select-option value="内部链接">内部链接</a-select-option>
    </a-select>
    <a-input v-if="value === '外部链接'" class="my-1" placeholder="请输入链接" v-model:value="link" />
    <a-select show-search class="my-1" v-else placeholder="请选择内部链接" v-model:value="link">
      <a-select-option v-for="item in fullList" :key="item.id" :value="item.id">{{ item.title }}
      </a-select-option>
    </a-select>
    <a-input class="my-1" placeholder="请输入描述" v-model:value="desc" />
    <a-button class="w-full" type="primary" @click="save">确认</a-button>
  </div>
</template>
<script lang="tsx">
import { useStore } from "../../../../stores/menu";
import { defineComponent, ref } from "vue";
import { storeToRefs } from "pinia";
import { NoteType } from "@/enum";

export default defineComponent({
  name: 'link-select',
  emits: ["click"],
  setup(props, ctx) {
    const value = ref("外部链接");
    const link = ref(null);
    const desc = ref("");
    const { fullList } = storeToRefs(useStore());
    const save = () => {
      let data = null;
      if (value.value === "内部链接") {
        data = `${location.origin}/docx/${link.value}`;
      } else {
        data = link.value;
      }
      ctx.emit("click", {
        type: value.value,
        link: data,
        desc: desc.value,
      });
    };
    return {
      fullList: fullList.value.filter((item) =>
        [NoteType.笔记, NoteType.表格, NoteType.多维表格].includes(item.noteType)
      ),
      save,
      link,
      desc,
      value,
    };
  },
});
</script>
