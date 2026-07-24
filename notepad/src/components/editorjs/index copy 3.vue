<template>
  <div class="np-editor">
    <a-anchor class="absolute left-10 top-40" :items="anchorItems" />
    <a-skeleton :loading="!state.isFinished" active>
      <div id="np-editor-canvas" :style="{ width: config.page.width }"></div>
    </a-skeleton>
    <a-modal :open="modalState.visible" @update:open="(val) => modalState.visible = val" :title="modalState.title" width="1000px" :footer="null" centered
      destroyOnClose>
      <component :data="modalState.data" :is="modalState.component" :key="modalState.component"
        :target="modalState.target" @change-link="changeLink">
      </component>
    </a-modal>
  </div>
</template>

<script lang="tsx">
import EditorJS from "@editorjs/editorjs";
import { defineComponent, onMounted, onUnmounted, reactive, computed } from "vue";
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
import { debounce } from "../../utils/debounce";

interface BlockItemProps {
  property: string;
  id: string | number;
  blockType: string;
}

interface EventListenerRef {
  element: Element;
  event: string;
  handler: EventListener;
}

interface ModalState {
  title: string;
  data: any[];
  component: string;
  visible: boolean;
  target: HTMLElement | Element | null;
}

interface EditorState {
  data: any[];
  isFinished: boolean;
  blocks: any[];
}

export default defineComponent({
  name: "np-editor",
  props: ["id"],
  components: { Header, LinkPage },
  setup(props) {
    const store = useStore();
    const { config, editor: editorState, anchor } = storeToRefs(store);
    const modalState = reactive<ModalState>({
      title: "",
      data: [],
      component: "",
      visible: false,
      target: null,
    });
    const state = reactive<EditorState>({
      data: [],
      isFinished: false,
      blocks: [],
    });
    let editor: EditorJS = null;

    const changeLink = async (data, fetch) => {
      try {
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
          } else {
            message.error('修改失败，请重试');
          }
        }
      } catch (error) {
        console.error('修改链接失败:', error);
        message.error('修改失败，请重试');
      }
    }

    // 处理编辑器内容变化的函数
    const handleEditorChange = async (api, event) => {
      try {
        if (Array.isArray(event)) event = event[0];
        const { detail, type } = event;

        // 获取当前编辑器内容
        const { blocks } = await editor.save();

        // 保存旧的blocks数据用于比较
        const oldBlocks = [...state.blocks];
        state.blocks = blocks;

        // 获取事件类型和方法
        let method = BlockEvent[type];
        if (!method) return;

        // 获取块信息
        const { holder, name } = detail.target;
        let blockType = name;
        let id = detail.target.id;
        let block = blocks.find((item) => item.id === id);

        // 处理特殊块类型
        if (name === "list") {
          blockType = holder.querySelector(".cdx-list--unordered")
            ? "unordered"
            : "ordered";
        }
        if (name === "header" && block?.data) {
          const { level } = block.data;
          blockType = `${blockType}${level}`;
        }

        // 初始化编辑器状态
        if (!editorState.value[props.id]?.[id]) {
          editorState.value[props.id][id] = {};
        }

        // 准备请求参数
        const param = {
          id,
          parentId: props.id,
          property: JSON.stringify(block?.data || {}),
          blockType: BlockType[blockType],
        };

        // 处理ID和请求方法
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

        if (method === "update" && !param["id"]) {
          method = "add";
        }

        let res = null;

        // 处理删除操作
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
            await handleEmptyIdBlocks(blocks);
            return;
          }

          // 找出被删除的块的ID
          const deletedBlockIds = oldBlocks
            .map(block => block.id)
            .filter(id => !blocks.some(block => block.id === id));

          if (deletedBlockIds.length > 0) {
            // 批量删除
            await handleMultipleDeletions(deletedBlockIds, method);
          } else {
            await handleEmptyIdBlocks(blocks);
          }
        } else {
          // 处理添加和更新操作
          res = await useFetch(`system/block/${method}`).post(param).json();

          if (res?.data?.value?.code === 200 && method === "add") {
            await handleAddSuccess(res, id, name, holder, blockType);
          }
        }
      } catch (error) {
        console.error('Editor onChange error:', error);
      }
    };

    // 创建防抖的 onChange 处理函数，延迟 500ms
    const debouncedHandleEditorChange = debounce(handleEditorChange, 500);

    // 转换 anchor 数据为 items 格式
    const anchorItems = computed(() => {
      if (!anchor.value[props.id]) return [];
      return anchor.value[props.id].map((item: any) => ({
        key: item.href,
        href: `#${item.href}`,
        title: item.title,
      }));
    });

    // 缓存编辑器配置，避免重复创建
    const editorConfig = computed(() => ({
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
      onChange: debouncedHandleEditorChange,
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
    }));

    // 存储事件监听器引用，用于清理
    const eventListeners: EventListenerRef[] = [];

    const bindClick = () => {
      // 清理之前的事件监听器
      eventListeners.forEach(({ element, event, handler }) => {
        element.removeEventListener(event, handler);
      });
      eventListeners.length = 0;

      document.querySelectorAll(".ce-paragraph a").forEach((a) => {
        const handler = singleClick(() => {
          if (a.hasAttribute("link")) {
            modalState.visible = true;
            modalState.title = "链接";
            modalState.component = "LinkPage";
            modalState.data = JSON.parse(a.getAttribute("link"));
            modalState.target = a;
          }
        }, 300);
        
        a.addEventListener("click", handler);
        eventListeners.push({ element: a, event: "click", handler });
      });
    };
    const initEditor = () => {
      if (!editor) {
        editor = new EditorJS(editorConfig.value);
      } else {
        // editor.render({ blocks: state.data });
      }
    };
    const getBlockList = async () => {
      try {
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

        if (state.data.length <= 1) {
          state.data = [{
            type: 'checklist',
            data: {
              items: [
                {
                  text: '这些是帮助你熟悉 雨滴笔记 的基础步骤：',
                },
                {
                  text: '点击任何地方来开始工作'
                },
                {
                  text: '点击左侧+按钮查看你能创建的所有内容类型：标题，视频，子页面，多维表格，等等…'
                },
                {
                  text: '尝试将鼠标悬停在本行，看到待办事项方框左侧的 ⋮⋮ 了吗？长按以移动本行内容'
                },
                {
                  text: '点击你侧边栏顶部的 + 新页面 来添加页面 '
                },
              ],
            }
          },

          ]
        }

      }
      initEditor();
      } catch (error) {
        console.error('获取块列表失败:', error);
        message.error('加载内容失败，请刷新页面重试');
        state.isFinished = true;
      }
    };
    onMounted(() => {
      getBlockList();
    });

    onUnmounted(() => {
      // 清理事件监听器
      eventListeners.forEach(({ element, event, handler }) => {
        element.removeEventListener(event, handler);
      });
      eventListeners.length = 0;

      // 清理编辑器实例
      if (editor) {
        editor.destroy();
        editor = null;
      }
    });

    const handleEmptyIdBlocks = async (blocks) => {
      const list = blocks.filter(item => typeof item.id === 'string').map(item => item.data).map(item => item.text);
      const res = await useFetch(`system/block/add`).post({
        parentId: props.id,
        property: JSON.stringify({ text: list.join('<br/>') }),
        blockType: BlockType.paragraph,
      }).json();
      // for (const item of blocks.filter(item => typeof item.id === 'string')) {
      //   list.push(item?.data || {});
      //   const res = await useFetch(`system/block/add`).post({
      //     parentId: props.id,
      //     property: JSON.stringify(item?.data || {}),
      //     blockType: BlockType[item.type],
      //   }).json();

      const data = res.data.value.data;
      let saveData = {
        id: data,
      };

      if (typeof data === "object") {
        saveData = { ...data };
      }

      editorState.value[props.id][data.blockId] = saveData;
      // }

    };

    const handleMultipleDeletions = async (deletedBlockIds, method) => {
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
    };

    const handleAddSuccess = async (res, id, name, holder, blockType) => {
      const data = res.data.value.data;
      let saveData = {
        id: data,
      };

      if (typeof data === "object") {
        saveData = { ...data };
      }

      editorState.value[props.id][id] = saveData;

      if (name === "basetable") {
        const { dwtableId, blockId } = saveData;
        holder.querySelector("iframe").src = `/base/${dwtableId}?code=true&name=表格`;
        await useFetch(`system/block/update`).post({
          id: blockId,
          parentId: props.id,
          property: JSON.stringify({ dwtableId }),
          blockType: BlockType[blockType],
        });
      }
    };

    return { modalState, config, state, changeLink, anchor, anchorItems };
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
