<template>
  <div class="np-editor">
    <a-anchor class="absolute left-10 top-40">
      <a-anchor-link v-for="item in anchor[id]" :href="`#${item.href}`" :title="item.title" />
    </a-anchor>
    <a-skeleton :loading="!state.isFinished" active>
      <div id="np-editor-canvas" :style="{ width: config.page.width }"></div>
    </a-skeleton>
    <a-modal v-model:open="modalState.visible" :title="modalState.title" width="1000px" :footer="null" centered
      destroyOnClose>
      <component :data="modalState.data" :is="modalState.component" :key="modalState.component"
        :target="modalState.target" @change-link="changeLink">
      </component>
    </a-modal>
  </div>
</template>

<script lang="tsx">
import EditorJS from "@editorjs/editorjs";
import { defineComponent, onMounted, reactive } from "vue";
import Header from "../../views/header/index.vue";
import InlineLink from "./tools/inline/link";
import Anchor from "./tools/inline/anchor";
import Underline from "@editorjs/underline";
import List from "@editorjs/list";
import Table from "@editorjs/table";
import Checklist from "@editorjs/checklist";
import LinkPage from "./components/link/index.vue";
import EHeader from "@editorjs/header";
import { useStore } from "../../stores/editor";
import { storeToRefs } from "pinia";
import { useFetch } from "../../hooks";
import { BlockEvent, BlockType } from "./utils/blockType";
import {
  Mindmap,
  Image,
  Divider,
  Callout,
  Code,
  BaseTable,
  File,
} from "./tools/block";
import { singleClick } from "./tools/utils";
import { message } from "ant-design-vue";

interface BlockItemProps {
  property: string;
  id: string | number;
  blockType: string;
}

export default defineComponent({
  name: "np-editor",
  props: ["id"],
  components: { Header, LinkPage },
  setup(props) {
    const store = useStore();
    const { config, editor: editorState, anchor } = storeToRefs(store);
    const modalState = reactive({
      title: "",
      data: [],
      component: "",
      visible: false,
      target: null as HTMLElement | Element,
    });
    const state = reactive({
      data: [],
      isFinished: false,
      blocks: [],
    });
    let editor: EditorJS = null;

    const changeLink = async (data, fetch) => {
      modalState.data = data;
      if (fetch) {
        const param = {
          id: data[0].id,
          parentId: props.id,
          property: JSON.stringify({
            text: modalState.target.outerHTML
          }),
          blockType: 2,
        };
        const res = await useFetch(`system/block/update`).post(param).json();
        if (res?.data?.value?.code === 200) {
          message.success('修改成功');
        }
      }
    }

    const bindClick = () => {
      document.querySelectorAll(".ce-paragraph a").forEach((a) => {
        a.addEventListener(
          "click",
          singleClick(() => {
            if (a.hasAttribute("link")) {
              modalState.visible = true;
              modalState.title = "链接";
              modalState.component = "LinkPage";
              modalState.data = JSON.parse(a.getAttribute("link"));
              modalState.target = a;
            }
          }, 300)
        );
      });
    };
    const initEditor = () => {
      if (!editor) {
        editor = new EditorJS({
          autofocus: state.data.length === 0,
          holder: "np-editor-canvas",
          placeholder: "请输入",
          tools: {
            link: {
              class: InlineLink,
              shortcut: "Ctrl+K",
            },
            anchor: {
              class: Anchor
            },
            header: {
              class: EHeader,
              inlineToolbar: true,
              config: {
                defaultLevel: 1,
              },
            },
            table: {
              class: Table,
              inlineToolbar: true,
            },
            list: {
              class: List,
              inlineToolbar: true,
              config: {
                defaultStyle: "unordered",
              },
            },
            mindmap: {
              class: Mindmap,
            },
            image: {
              class: Image,
            },
            file: {
              class: File,
            },
            divider: {
              class: Divider,
            },
            callout: {
              class: Callout,
            },
            code: {
              class: Code,
            },
            basetable: {
              class: BaseTable,
            },
            underline: {
              class: Underline,
              shortcut: "Ctrl+U",
            },
            checklist: {
              class: Checklist,
              inlineToolbar: true,
            },

          },
          i18n: {
            messages: {
              ui: {
                blockTunes: {
                  toggler: {
                    "Click to tune": "点击",
                  },
                },
                toolbar: {
                  toolbox: {
                    Add: "添加",
                  },
                },
                popover: {
                  Filter: "查询",
                  "Nothing found": "暂无数据",
                },
                inlineToolbar: {
                  converter: {
                    "Convert to": "转换为",
                  },
                },
              },
              tools: {
                table: {
                  "With headings": "添加表头",
                  "Without headings": "删除表头",
                  Heading: "标题",
                  "Delete row": "删除当前行",
                  "Add row above": "插入到当前行上方",
                  "Add row below": "插入到当前行下方",
                  "Add column to left": "插入到当前列左侧",
                  "Add column to right": "插入到当前列右侧",
                  "Delete column": "删除当前列",
                },
                list: {
                  Unordered: "无序列表",
                  Ordered: "有序列表",
                },
                header: {
                  "Heading 1": "一级标题",
                  "Heading 2": "二级标题",
                  "Heading 3": "三级标题",
                  "Heading 4": "四级标题",
                  "Heading 5": "五级标题",
                  "Heading 6": "六级标题",
                },
              },
              toolNames: {
                Text: "文本",
                Heading: "标题",
                List: "列表",
                Table: "表格",
                Bold: "粗体",
                Italic: "斜体",
                Link: "链接",
                Underline: "下划线",
                Checklist: "待办任务",
              },
              blockTunes: {
                delete: {
                  Delete: "删除",
                  "Click to delete": "点击删除",
                },
                moveUp: {
                  "Move up": "向上移动",
                },
                moveDown: {
                  "Move down": "向下移动",
                },
              },
            },
          },
          data: {
            blocks: state.data,
          },
          async onChange(api, event) {
            if (Array.isArray(event)) event = event[0];
            const { detail, type } = event;
            if (detail.index < 0) return;
            const { blocks } = await editor.save();

            const { holder, name, isEmpty } = detail.target;
            // if (JSON.stringify(blocks) === JSON.stringify(state.blocks)) return;
            // if (
            //     JSON.stringify(blocks) === JSON.stringify(state.blocks) &&
            //     !["header", "paragraph"].includes(name)
            // ) {
            //   return;
            // }
            // 保存旧的blocks数据
            const oldBlocks = [...state.blocks];
            // 更新state.blocks
            state.blocks = blocks;

            let method = BlockEvent[type];
            let blockType = name;
            let id = detail.target.id;
            let block = blocks.find((item) => item.id === id);
            if (name === "list") {
              blockType = holder.querySelector(".cdx-list--unordered")
                ? "unordered"
                : "ordered";
            }
            if (name === "header") {
              const { level } = block.data;
              blockType = `${blockType}${level}`;
            }
            // if (isEmpty) {
            //   method = 'remove';
            // }
            // if (["add", "update"].includes(method) && !block) return;
            // if (["add", "update"].includes(method) ) return;
            if (!editorState.value[props.id]?.[id]) {
              editorState.value[props.id][id] = {};
            }

            const param = {
              id,
              parentId: props.id,
              property: JSON.stringify(block?.data || {}),
              blockType: BlockType[blockType],
            };

            if (typeof id === "string" && id.length > 0) {
              if (editorState.value[props.id][id]) {
                param["id"] =
                  editorState.value[props.id][id]["id"] ||
                  editorState.value[props.id][id]["blockId"];
              } else {
                method = "add";
                delete param["id"];
              }
            }
            if (!method) return;

            if (method === "update" && !param["id"]) {
              method = "add";
            }
            let res = null;
            if (method === "remove") {

              if (typeof id === "string") {
                if (
                  !editorState.value[props.id][id]["id"] &&
                  typeof editorState.value[props.id][id]["id"] === "string"
                ) {
                  return;
                }
                id = editorState.value[props.id][id]["id"] || editorState.value[props.id][id]["blockId"];
              }
              if (!id) {
                for (const item of blocks.filter(item => typeof item.id === 'string')) {
                  res = await useFetch(`system/block/add`).post({
                    parentId: props.id,
                    property: JSON.stringify(item?.data || {}),
                    blockType: BlockType[item.type],
                  }).json();
                  const data = res.data.value.data;
                  let saveData: any = {
                    id: data,
                  };
                  if (typeof data === "object") {
                    saveData = {
                      ...data,
                    };
                  }
                  editorState.value[props.id][data.blockId] = saveData;
                }
                return;
              }

              // 找出被删除的块的ID
              const deletedBlockIds = oldBlocks
                .map(block => block.id)
                .filter(id => !blocks.some(block => block.id === id));

              // res = await useFetch(`system/block/${method}/${id}`).get().json();
              // for (const key in editorState.value) {
              //   if (typeof editorState.value[key] === "object") {
              //     editorState.value[key][store.id] = {};
              //   }
              // }

              if (deletedBlockIds.length > 0) {
                // 批量删除
                const deletePromises = deletedBlockIds.map(async (blockId) => {

                  return useFetch(`system/block/${method}/${blockId}`).get().json();
                });

                await Promise.allSettled(deletePromises);

                // 清理状态
                for (const key in editorState.value) {
                  if (typeof editorState.value[key] === "object") {
                    editorState.value[key][store.id] = {};
                  }
                }
              } else {
                for (const item of blocks.filter(item => typeof item.id === 'string')) {
                  res = await useFetch(`system/block/add`).post({
                    parentId: props.id,
                    property: JSON.stringify(item?.data || {}),
                    blockType: BlockType[item.type],
                  }).json();
                  const data = res.data.value.data;
                  let saveData: any = {
                    id: data,
                  };
                  if (typeof data === "object") {
                    saveData = {
                      ...data,
                    };
                  }
                  editorState.value[props.id][data.blockId] = saveData;
                }
              }
            } else {
              res = await useFetch(`system/block/${method}`).post(param).json();
            }
            if (res?.data?.value?.code === 200 && method === "add") {
              const data = res.data.value.data;
              let saveData: any = {
                id: data,
              };
              if (typeof data === "object") {
                saveData = {
                  ...data,
                };
              }
              editorState.value[props.id][id] = saveData;
              if (name === "basetable") {
                const { dwtableId, blockId } = saveData;
                holder.querySelector(
                  "iframe"
                ).src = `/base/${dwtableId}?code=true&name=表格`;
                useFetch(`system/block/update`).post({
                  id: blockId,
                  parentId: props.id,
                  property: JSON.stringify({ dwtableId }),
                  blockType: BlockType[blockType],
                });
              }
            }
          },
          onReady() {
            if (state.data.length === 0) {
              editor.clear();
            }
            bindClick();

            editor.on("click-link", (data) => {
              modalState.visible = true;
              modalState.title = "链接";
              modalState.component = "LinkPage";
              modalState.data = data;
            });
          },
        });
      } else {
        // editor.render({ blocks: state.data });
      }
    };
    const getBlockList = async () => {
      state.data = [];
      const { data, isFinished } = await useFetch(
        `system/block/list?parentId=${props.id}`
      )
        .get()
        .json();
      state.isFinished = isFinished.value;
      if (data.value?.data) {
        state.data = data.value.data.map((item: BlockItemProps) => {
          let data = {};
          if (item?.property?.length > 0) {
            data = eval("(" + JSON.parse(JSON.stringify(item.property)) + ")");
          }
          let type = BlockType[item.blockType];
          if (/header/gi.test(type)) {
            type = "header";
          }
          if (/unordered|ordered/gi.test(type)) {
            type = "list";
          }
          if (data?.text) {
            if (data.text.indexOf('<c id=') > -1) {
              let div = document.createElement('div');
              div.innerHTML = data.text;
              div.querySelectorAll('c').forEach(c => {
                store.addAnchor({
                  href: c.id,
                  title: c.innerText
                });
              })

              div.remove();
              div = null;
            }
          }
          return {
            id: item.id,
            type,
            data,
          };
        });
        state.blocks = state.data;
      }
      initEditor();
    };
    onMounted(() => {
      getBlockList();
    });

    return { modalState, config, state, changeLink, anchor };
  },
});
</script>

<style lang="scss" scoped>
.np-editor {
  background: var(--np-bg-color-overlay);
}

#np-editor-canvas {
  width: 100%;
  height: 100%;
  margin: 0 auto;
}

:deep(.ce-block__content),
:deep(.ce-toolbar__content) {
  max-width: unset;
}

:deep(.ce-toolbar) {
  z-index: 5;
}
</style>
