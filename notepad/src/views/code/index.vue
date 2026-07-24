<template>
  <div class="h-full flex flex-col">
    <div class="flex h-5">
      <a-select
        allowClear
        showSearch
        v-model:value="state.mode"
        :options="modelist"
        placeholder="请选择语言"
        size="small"
      ></a-select>
      <a-select
        allowClear
        showSearch
        v-model:value="state.theme"
        :options="themelist"
        placeholder="请选择主题"
        size="small"
      ></a-select>
    </div>
    <div id="ace"></div>
  </div>
</template>
<script lang="ts">
import ace from "ace-builds";
import "ace-builds/esm-resolver";
import "ace-builds/src-noconflict/ext-language_tools";
import modelist from "ace-builds/src-noconflict/ext-modelist";
import themelist from "ace-builds/src-noconflict/ext-themelist";
import { defineComponent, onMounted, reactive, watch } from "vue";
import { useStore } from "../../stores/editor";
import { useDebounceFn } from "@vueuse/core";
import { useFetch } from "../../hooks";
import { useRoute } from "vue-router";
import { storeToRefs } from "pinia";
export default defineComponent({
  setup() {
    const route = useRoute();
    const store = useStore();
    const { code, id } = storeToRefs(store);
    const { blockId }: any = route.params;
    const { mode, theme, readOnly, value } = code.value[id.value][blockId];
    const state = reactive({
      mode,
      theme,
    });
    let editor: ace.Ace.Editor;
    const update = async () => {
      const value = editor.getValue();
      const param = {
        id: /^\d+$/gi.test(blockId)
          ? blockId
          : editorState.value.editor[id][blockId].id,
        parentId: id,
        property: JSON.stringify({
          mode: state.mode,
          theme: state.theme,
          value,
        }),
        blockType: 14,
      };
      useFetch(`system/block/update`).post(param).json();
    };
    onMounted(() => {
      editor = ace.edit("ace", {
        enableBasicAutocompletion: true,
        enableLiveAutocompletion: true,
        showPrintMargin: false,
        autoScrollEditorIntoView: true,
        tabSize: 8,
        readOnly,
        animatedScroll: true,
        wrap: true,
        fontSize: 16,
        theme,
        mode,
        enableSnippets: true,
        useWorker: false, // 禁用worker避免eval警告
        useSoftTabs: true,
        value: value,
      });
      editor.session.on(
        "change",
        useDebounceFn(async () => {
          update();
        }, 1000)
      );
    });

    watch(state, (value) => {
      update();
    });

    return {
      modelist: modelist.modes.map((item) => ({
        value: item.mode,
        label: item.name,
      })),
      themelist: themelist.themes.map((item) => ({
        value: item.theme,
        label: item.name,
      })),
      state,
    };
  },
});
</script>
<style scoped lang="scss">
#ace {
  height: calc(100% - 2rem);
  margin-top: 5px;
}
</style>
