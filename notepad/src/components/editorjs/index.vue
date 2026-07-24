<template>
  <div class="np-editor">
    <a-anchor class="absolute left-10 top-40" :items="anchorItems" />

    <!-- U5: 笔记编辑器右上角常驻图标按钮 + 浮层面板 -->
    <div class="np-reference-panel-wrapper">
      <ReferencePanel :note-id="noteIdValue" :initial-list="referenceState.list" :cache-version="referenceState.version" />
    </div>

    <a-skeleton :loading="!state.isFinished" active>
      <div id="np-editor-canvas" :style="{ width: config.page.width }"></div>
    </a-skeleton>

    <a-modal v-model:open="modalState.visible" :title="modalState.title" width="1000px" :footer="null" centered
      destroyOnClose>
      <component :is="modalState.component" :key="modalState.component" :data="modalState.data"
        :target="modalState.target" @change-link="changeLink" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { reactive, computed, onMounted, onUnmounted, ref, nextTick } from 'vue';
import EditorJS from "@editorjs/editorjs";
import { message } from "ant-design-vue";
import { storeToRefs } from "pinia";
import { useRoute } from 'vue-router'
// 基础工具
import EHeader from "@editorjs/header";
import List from "@editorjs/list";
import Table from "@editorjs/table";
import Checklist from "@editorjs/checklist";
import Underline from "@editorjs/underline";
// 自定义工具
import InlineLink from "./tools/inline/link";
import Anchor from "./tools/inline/anchor";
import SemanticLink from './tools/inline/SemanticLink';
import { Mindmap, Image, Divider, Callout, Code, BaseTable, File } from "./tools/block";
// 业务逻辑
import { useStore } from "../../stores/editor";
import { useUserStore } from "../../stores/user";

import { useFetch } from "../../hooks";
import { BlockEvent, BlockType } from "./utils/blockType";
import { debounce } from "../../utils/debounce";
import { Bus, jumpToTableCell } from "@/utils";
import LinkPage from "./components/link/index.vue";
// U5: 笔记侧"被引用"追溯面板
import ReferencePanel from "../noteLink/ReferencePanel.vue";

const userStore = useUserStore();
const { userInfo } = storeToRefs(userStore);
const isLocked = ref(true);

const route = useRoute();
const props = defineProps<{ id: string | string[] | number | number[], isModal: boolean, tableId: string | number, recordId: string | number, linkColumnId?: string | number, linkItemId?: string | number, linkId?: string | number, tableNoteId?: string | number }>();

// --- 状态管理 ---
const store = useStore();
const { config, editor: editorRef, anchor: anchorStore } = storeToRefs(store);
const editorInstance = ref<EditorJS | null>(null);

const modalState = reactive({
  title: "",
  data: [] as any[],
  component: null as any,
  visible: false,
  target: null as HTMLElement | null,
});

const state = reactive({
  isFinished: false,
  blocks: [] as any[],
});

const editorI18n = {
  messages: {
    ui: {
      blockTunes: {
        toggler: { "Click to tune": "点击" },
      },
      toolbar: {
        toolbox: { Add: "添加" },
      },
      popover: {
        Filter: "查询",
        "Nothing found": "暂无数据",
      },
      inlineToolbar: {
        converter: { "Convert to": "转换为" },
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
        "Heading 1": "一级标题", "Heading 2": "二级标题", "Heading 3": "三级标题",
        "Heading 4": "四级标题", "Heading 5": "五级标题", "Heading 6": "六级标题",
      },
    },
    toolNames: {
      Text: "文本", Heading: "标题", List: "列表", Table: "表格",
      Bold: "粗体", Italic: "斜体", Link: "链接", Underline: "下划线", Checklist: "待办任务",
    },
    blockTunes: {
      delete: { Delete: "删除", "Click to delete": "点击删除" },
      moveUp: { "Move up": "向上移动" },
      moveDown: { "Move down": "向下移动" },
    },
  },
};

// --- 计算属性 ---
const anchorItems = computed(() => {
  const pageAnchors = anchorStore.value[props.id] || [];
  return pageAnchors.map((item: any) => ({
    key: item.href,
    href: `#${item.href}`,
    title: item.title,
  }));
});

// U5: 笔记 id(用于按 noteId 拉取引用列表缓存)
const noteIdValue = computed(() => props.id);

// U5: 引用列表缓存(供 ReferencePanel 读取);version 用于强制子组件重新读取
const referenceState = reactive({
  list: [] as any[],
  version: 0,
});

// U5: 拉取该笔记被表格引用的列表,缓存到 editor store + 本地 state
// 时机:onReady 之后(editorjs 实例化 + DOM 渲染完成)
// 共享:内联角标(SemanticLink 通过 useStore().isReferenced 读取)+ ReferencePanel(通过 props.initialList)
const fetchReferenceList = async () => {
  try {
    const { data } = await useFetch(`system/notelink/byNote/${props.id}`).get().json();
    const list = data.value?.data || [];
    // 写入 store,供 SemanticLink 渲染角标时查询
    store.setReferences(props.id, list);
    // 同步到本地 state,触发 ReferencePanel 重新读取缓存
    referenceState.list = list;
    referenceState.version += 1;
    // U5: 渲染内联角标(扫描 DOM 中所有语义锚点,根据引用列表添加/移除角标)
    renderReferenceBadges();
  } catch (e) {
    console.error('拉取笔记引用列表失败:', e);
    // 失败时不清空已有缓存,ReferencePanel 自己有错误态 + 重试按钮
  }
};

// U5: 扫描 DOM 中所有语义锚点 a[data-type="semantic"][data-link-id],
// 根据 store.references 给被引用的锚点添加右上角角标;同时给已有角标做去重/同步。
// 角标点击触发 jumpToTableCell(新标签页打开目标表格),payload 来自 store.references 中对应条目。
let renderBadgesScheduled = false;
const renderReferenceBadges = () => {
  if (renderBadgesScheduled) return;
  renderBadgesScheduled = true;
  // 用 nextTick + 微任务节流,避免短时间内多次调用导致重复扫描
  nextTick(() => {
    renderBadgesScheduled = false;
    const canvas = document.getElementById('np-editor-canvas');
    if (!canvas) return;
    const refs = store.getReferences(props.id) || [];
    // 用 linkId 作为索引便于查询
    const refMap = new Map<string, any>();
    for (const r of refs) {
      if (r.id != null) refMap.set(String(r.id), r);
    }
    const anchors = canvas.querySelectorAll<HTMLElement>(
      'a[data-type="semantic"][data-link-id]'
    );
    anchors.forEach((anchor) => {
      const linkId = anchor.getAttribute('data-link-id') || '';
      // 清理旧角标
      const oldBadge = anchor.querySelector('.semantic-ref-badge');
      if (linkId === '' || !refMap.has(linkId)) {
        if (oldBadge) oldBadge.remove();
        anchor.classList.remove('semantic-ref-anchor');
        return;
      }
      const ref = refMap.get(linkId);
      // 添加/更新角标
      anchor.classList.add('semantic-ref-anchor');
      let badge = oldBadge as HTMLElement | null;
      if (!badge) {
        badge = document.createElement('span');
        badge.className = 'semantic-ref-badge';
        badge.setAttribute('contenteditable', 'false');
        badge.title = '该词被表格引用,点击跳转';
        anchor.appendChild(badge);
      }
      // 角标内容 = 该 noteId 被引用的次数(本期简化为 1,未来可计数)
      badge.textContent = '1';
      // 绑定点击(避免重复绑定,先清后加)
      badge.onclick = (e: MouseEvent) => {
        e.preventDefault();
        e.stopPropagation();
        if (!ref) return;
        jumpToTableCell({
          noteId: props.id,
          linkDwTableId: ref.linkDwTableId,
          linkColumnId: ref.linkColumnId,
          linkItemId: ref.linkItemId,
          linkRecordId: ref.linkRecordId,
        });
      };
    });
  });
};

// U5: 监听 semantic-link-updated 事件(创建/删除锚点时触发),刷新引用列表缓存
const onSemanticLinkUpdated = (event: string) => {
  try {
    const data = JSON.parse(event);
    if (data.type === 'semantic-link-updated') {
      // 数据可能变化,重新拉取缓存
      fetchReferenceList();
    }
  } catch (e) {
    // ignore parse error
  }
};

// --- 逻辑处理 ---

const changeLink = async (data: any, shouldFetch: boolean) => {
  modalState.data = data;
  if (shouldFetch && modalState.target) {
    try {
      const param = {
        id: data[0]?.id,
        parentId: props.id,
        property: JSON.stringify({ text: modalState.target.outerHTML }),
        blockType: 2,
      };
      const { data: res } = await useFetch(`system/block/update`).post(param).json();
      if (res.value?.code === 200) message.success('修改成功');
    } catch (e) {
      message.error('链接同步失败');
    }
  }
};

const handleEditorChange = async (api: any, event: any) => {
  const ev = Array.isArray(event) ? event[0] : event;
  const { detail, type } = ev;
  const method = BlockEvent[type];
  if (!method) return;

  const savedData = await editorInstance.value?.save();
  const blocks = savedData?.blocks || [];
  // U5 副作用修复:清理 save 数据中的运行时 UI 元素
  // renderReferenceBadges 添加的 semantic-ref-anchor class 和 .semantic-ref-badge 子元素
  // 不应被持久化到 note_block.property,否则数据膨胀且加载时产生脏 DOM
  for (const block of blocks) {
    if (block.data?.text && typeof block.data.text === 'string'
        && block.data.text.includes('semantic-ref-')) {
      const tempDiv = document.createElement('div');
      tempDiv.innerHTML = block.data.text;
      tempDiv.querySelectorAll('.semantic-ref-badge').forEach(el => el.remove());
      tempDiv.querySelectorAll('.semantic-ref-anchor').forEach(el => el.classList.remove('semantic-ref-anchor'));
      block.data.text = tempDiv.innerHTML;
    }
  }
  if (JSON.stringify(blocks) === JSON.stringify(state.blocks)) {
    return;
  }
  const oldBlocks = [...state.blocks];
  state.blocks = blocks;
  if (isLocked.value) {
    return;
  }
  const targetBlock = detail.target;
  const blockId = targetBlock.id;
  const blockData = blocks.find(b => b.id === blockId);
  // 映射 BlockType
  let typeKey = targetBlock.name;
  if (typeKey === "list") {
    typeKey = targetBlock.holder.querySelector(".cdx-list--unordered") ? "unordered" : "ordered";
  } else if (typeKey === "header" && blockData?.data) {
    typeKey = `header${blockData.data.level}`;
  }

  // 删除逻辑
  if (method === "remove") {
    const deletedIds = oldBlocks
      .filter(ob => !blocks.some(nb => nb.id === ob.id))
      .map(ob => editorRef.value[props.id]?.[ob.id]?.id || ob.id);

    if (deletedIds.length) {
      await Promise.allSettled(deletedIds.map(id => useFetch(`system/block/remove/${id}`).get()));
    }
    return;
  }
  // 更新/新增逻辑
  const param: any = {
    parentId: props.id,
    property: JSON.stringify(blockData?.data || {}),
    blockType: BlockType[typeKey],
    sort: detail.index
  };

  const existing = editorRef.value[props.id]?.[blockId];
  if (existing?.id || existing?.blockId) {
    param.id = existing.id || existing.blockId;
  }

  if (method === 'update') {
    if (typeKey === "basetable") {
      return;
    }
  }
  const { data: res } = await useFetch(`system/block/${param.id ? 'update' : 'add'}`).post(param).json();

  if (res.value?.code === 200 && !param.id) {
    if (!editorRef.value[props.id]) editorRef.value[props.id] = {};
    editorRef.value[props.id][blockId] = res.value.data;

    // 特殊处理表格 iframe
    if (typeKey === "basetable" && res.value.data.dwtableId) {
      const iframe = targetBlock.holder.querySelector("iframe");
      const { dwtableId, blockId } = res.value.data;
      if (iframe) iframe.src = `/base/${dwtableId}?code=true&name=表格`;
      useFetch(`system/block/update`).post({
        id: blockId,
        parentId: props.id,
        property: JSON.stringify(res.value.data),
        blockType: BlockType[typeKey],
      });
    }
  }
};

const debouncedChange = debounce(handleEditorChange, 500);

// 查找最近的可滚动祖先节点(用于"滚动至笔记顶部")
const findScrollableParent = (el: HTMLElement | null): HTMLElement | null => {
  if (!el) return null;
  let node: HTMLElement | null = el.parentElement;
  while (node) {
    const style = window.getComputedStyle(node);
    if (/(auto|scroll)/.test(style.overflowY)) return node;
    node = node.parentElement;
  }
  return null;
};

// 滚动至笔记顶部(用于 R7 兜底分支:正向历史锚点 / 陈旧锚点)
const scrollToNoteTop = () => {
  const editorRoot = document.querySelector('.np-editor') as HTMLElement | null;
  const scrollContainer = findScrollableParent(editorRoot) || editorRoot;
  scrollContainer?.scrollTo({ top: 0, behavior: 'smooth' });
};

// 使用事件委托处理链接点击
const setupEventDelegation = async () => {
  const container = document.getElementById("np-editor-canvas");
  if (!container) return;
  container.addEventListener("click", async (e: MouseEvent) => {
    const el = (e.target as HTMLElement).closest("a");
    if (el && el.hasAttribute("link")) {
      e.preventDefault();
      try {
        modalState.data = JSON.parse(el.getAttribute("link") || "[]");
        modalState.target = el;
        modalState.title = "编辑链接";
        modalState.component = LinkPage;
        modalState.visible = true;
      } catch (err) {
        console.error("Link data error", err);
      }
    }
    if (el && el.hasAttribute('data-table-id')) {
      e.preventDefault();
      const tableId = el.getAttribute('data-table-id');
      let noteId = el.getAttribute('data-owner-note-id');
      // 历史锚点无 data-owner-note-id → 通过 data-table-id 查 NoteDwtable.noteId(归属笔记 id)
      // 用户提示:"要在关联信息里明确对应的多维表格id" — data-table-id 始终存在(含历史数据),
      // 用它查后端拿归属笔记 id,比依赖 DOM 属性 data-owner-note-id 更可靠
      if (!noteId) {
        try {
          const { data } = await useFetch(`/system/dwtable/${tableId}`).get().json();
          noteId = data.value?.data?.noteId;
        } catch (err) {
          console.error('查询多维表格归属笔记失败:', err);
        }
      }
      // 最终兜底:查询失败回退 data-target-id(旧行为兼容)
      if (!noteId) {
        noteId = el.getAttribute('data-target-id');
      }
      if (noteId) {
        window.open(`/base/${noteId}/${tableId}`);
      }
    }
  });
  const blockId = route.params.blockId;
  if (blockId) {
    await nextTick();

    const selector = `a[data-block-id="${blockId}"]`;
    const targetElement = document.querySelector(selector) as HTMLElement;

    if (targetElement) {
      targetElement.scrollIntoView({
        behavior: 'smooth',
        block: 'center'
      });

      targetElement.style.outline = '2px solid #1890ff';
      targetElement.style.transition = 'outline 0.5s';
      setTimeout(() => {
        targetElement.style.outline = 'none';
      }, 2000);
    } else {
      console.warn('未找到对应的关联块:', blockId);
    }
  }
  // U4: 词级 data-link-id 跳转定位 + R7 四类兜底分支
  // 仅当 linkId 被显式传入时执行(空字符串=正向历史词;非空=反向词)
  if (props.linkId != null) {
    await nextTick();
    // modal 场景等待打开动画完成,确保容器可见后再定位
    if (props.isModal) {
      await new Promise(resolve => setTimeout(resolve, 300));
    }
    const linkIdStr = String(props.linkId);
    if (linkIdStr === '') {
      // R7 分支2: data-link-id 为空(正向历史锚点,从未分配 id)→ 滚动至顶部,不提示失效
      scrollToNoteTop();
    } else {
      const selector = `a[data-link-id="${linkIdStr}"]`;
      const targetElement = document.querySelector(selector) as HTMLElement | null;
      if (targetElement) {
        // R7 分支1: 锚点存在 → scrollIntoView + 临时高亮(outline 模式,与 U5 共用高亮 class 后续替换)
        targetElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
        targetElement.style.outline = '2px solid #1890ff';
        targetElement.style.transition = 'outline 0.5s';
        setTimeout(() => {
          targetElement.style.outline = 'none';
        }, 2000);
      } else {
        // R7 分支3: data-link-id 陈旧(锚点已删除)→ 滚动至顶部 + 提示失效
        scrollToNoteTop();
        message.warning('关联已失效');
      }
    }
  }
};

const initEditor = (blocks: any[]) => {
  editorInstance.value = new EditorJS({
    holder: "np-editor-canvas",
    autofocus: blocks.length === 0,
    data: { blocks },
    tools: {
      semanticLink: { class: SemanticLink, config: props },
      link: { class: InlineLink, shortcut: "Ctrl+K" },
      anchor: Anchor,
      header: { class: EHeader, inlineToolbar: true, config: { defaultLevel: 1 } },
      table: { class: Table, inlineToolbar: true },
      list: { class: List, inlineToolbar: true },
      mindmap: Mindmap,
      image: Image,
      file: File,
      divider: Divider,
      callout: Callout,
      code: Code,
      basetable: BaseTable,
      underline: { class: Underline, shortcut: "Ctrl+U" },
      checklist: { class: Checklist, inlineToolbar: true },
    },
    i18n: editorI18n, // 注入 i18n 配置
    onChange: debouncedChange,
    onReady: () => {
      setupEventDelegation();
      // U5: onReady 后拉取引用列表缓存(editorjs 实例化 + DOM 渲染完成)
      fetchReferenceList();
    }
  });
};

const loadData = async () => {
  try {
    state.isFinished = false;
    const { data } = await useFetch(`system/block/list?parentId=${props.id}`).get().json();

    let blocks = (data.value?.data || []).map((item: any) => {
      let property = {};
      try {
        // 安全处理 property 反序列化
        property = typeof item.property === 'string' ? JSON.parse(item.property) : item.property;
      } catch (e) {
        property = {};
      }
      let type = BlockType[item.blockType] || 'paragraph';
      if (/header/i.test(type)) type = "header";
      if (/unordered|ordered/i.test(type)) type = "list";
      // 提取锚点到 store
      if (property.text?.includes('<c id=')) {
        const temp = document.createElement('div');
        temp.innerHTML = property.text;
        temp.querySelectorAll('c').forEach(c => {
          store.addAnchor({ href: c.id, title: c.textContent });
        });
      }
      // 修复:加载时填充 editorRef 映射(EditorJS block id → 后端 block id)。
      // handleEditorChange 依据此映射决定调用 system/block/update(有 id)还是 add(无 id)。
      // 若不填充,首次编辑已加载 block 会走 add 分支,在 DB 中创建重复 block,
      // 表现为"划词关联后新增一行同样文本"——原 block(原始文本)与新 block(锚点 HTML)并存。
      if (!editorRef.value[props.id]) editorRef.value[props.id] = {};
      editorRef.value[props.id][item.id] = { id: item.id };
      return { id: item.id, type, data: property };
    });

    if (blocks.length === 0 && userInfo.value.user.firstLogin === '0') {
      blocks = [{
        type: 'checklist', data: {
          items: [{
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
          },]
        }
      }];
    }

    state.blocks = blocks;
    nextTick(() => initEditor(blocks));
  } catch (e) {
    message.error('加载失败');
  } finally {
    setTimeout(() => {
      isLocked.value = false
    }, 2000)
    state.isFinished = true;
  }
};

onMounted(() => {
  loadData();
  // U5: 监听 semantic-link-updated 事件,锚点创建/删除后刷新引用列表缓存
  // 角标渲染与 ReferencePanel 数据共用此缓存
  Bus.on(onSemanticLinkUpdated);
});

onUnmounted(() => {
  if (editorInstance.value) {
    editorInstance.value.destroy();
    editorInstance.value = null;
  }
  // U5: 取消 Bus 监听(避免重复回调)
  Bus.off(onSemanticLinkUpdated);
});
</script>

<style lang="scss" scoped>
.np-editor {
  background: var(--np-bg-color-overlay);
  min-height: 100vh;
  padding: 20px 0;
}

#np-editor-canvas {
  margin: 0 auto;
  height: 100%;

  :deep(.codex-editor__redactor) {
    padding-bottom: 500px !important;
  }
}

:deep(.ce-block__content),
:deep(.ce-toolbar__content) {
  max-width: unset;
}

:deep(.ce-toolbar) {
  z-index: 10;
}

/* U5: ReferencePanel 入口按钮浮在右上角 */
.np-reference-panel-wrapper {
  position: fixed;
  top: 12px;
  right: 24px;
  z-index: 100;
}

/* U5: 被引用锚点的角标样式 */
:deep(.semantic-ref-anchor) {
  position: relative;
}

:deep(.semantic-ref-badge) {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 14px;
  height: 14px;
  padding: 0 4px;
  border-radius: 7px;
  background: #ff4d4f;
  color: #fff;
  font-size: 10px;
  line-height: 14px;
  text-align: center;
  user-select: none;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  z-index: 1;

  &:hover {
    background: #ff7875;
    transform: scale(1.1);
  }
}
</style>
