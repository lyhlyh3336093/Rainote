import './index.scss';
import { render, createVNode, h } from 'vue';
import { message, Modal, Button } from 'ant-design-vue';
import { ExclamationCircleOutlined } from '@ant-design/icons-vue';
import SemanticSelectComponent from '../../../components/SemanticLink/index.vue';
import { getBolckId, singleClick } from '../../utils';
import BaseClass from '../../BaseClass';
import { useStore } from "../../../../../stores/editor";
import { useFetch } from '../../../../../hooks';
import { Bus } from '@/utils';

const store = useStore();
const { editor, id: pid } = store;

const ICON_LINK = '<svg viewBox="0 0 1024 1024" width="20" height="20"><path d="M512 64C264.6 64 64 264.6 64 512s200.6 448 448 448 448-200.6 448-448S759.4 64 512 64zm0 820c-205.4 0-372-166.6-372-372s166.6-372 372-372 372 166.6 372 372-166.6 372-372 372z"/><path d="M464 688a48 48 0 1 0 96 0 48 48 0 1 0-96 0zM352 400h320v64H352z"/></svg>';

export default class SemanticLink extends BaseClass {
    range: Range | null = null;
    nodes: { button: HTMLButtonElement };

    CSS = {
        button: 'ce-inline-tool--semantic-link',
        linkActiveClass: 'semantic-link-highlight'
    };

    constructor(params) {
        super(params);
        this.nodes = {
            button: document.createElement('button'),
        };
        this.nodes.button.type = 'button';
        this.nodes.button.classList.add(this.api.styles.inlineToolButton, this.CSS.button);
        this.nodes.button.innerHTML = ICON_LINK;
    }

    static get isInline() { return true; }
    static get title() { return '语义关联'; }

    static get sanitize() {
        return {
            a: {
                'data-type': true,
                'data-table-id': true,
                'data-record-id': true,
                'data-block-id': true,
                'data-target-id': true,
                'data-source-id': true,
                'data-link-id': true,
                'data-owner-note-id': true,
                'data-column-id': true,
                class: true,
                onclick: true,
                href: true
            }
        };
    }

    render() { return this.nodes.button; }

    // 状态检查：光标在链接上时高亮按钮
    checkState() {
        const selection = window.getSelection();
        if (!selection || selection.rangeCount === 0) return;
        const anchor = this.getAnchorFromRange(selection.getRangeAt(0));
        this.nodes.button.classList.toggle(this.api.styles.inlineToolButtonActive, !!anchor);
    }

    getAnchorFromRange(range: Range): HTMLAnchorElement | null {
        let node = range.commonAncestorContainer;
        if (node.nodeType !== Node.ELEMENT_NODE) node = node.parentNode!;
        return (node as HTMLElement).closest('a[data-type="semantic"]');
    }

    surround(range: Range) {
        if (!range) return;
        // 克隆选区，防止 Modal 弹出后焦点丢失
        this.range = range.cloneRange();
        this.showSemanticModal();
    }

    /**
     * 显示弹窗
     */
    showSemanticModal() {
        const anchor = this.getAnchorFromRange(this.range!);
        const container = document.createElement('div');
        document.body.appendChild(container);

        const destroyModal = () => {
            render(null, container);
            container.remove();
        };
        if (this.config.isModal) {
            this.addOrUpdateLink({
                ...this.config,
                noteId: this.config.id
            }, anchor);
            return;
        }
        const vnode = createVNode(Modal, {
            open: true,
            title: anchor ? '修改语义关联' : '新建语义关联',
            width: '50%',
            centered: true,
            destroyOnClose: true,
            onCancel: destroyModal,
            footer: anchor ? h('div', { style: { display: 'flex', justifyContent: 'space-between' } }, [
                h(Button, {
                    danger: true,
                    onClick: () => this.confirmUnlink(anchor, destroyModal)
                }, () => '取消关联'),
                h(Button, { onClick: destroyModal }, () => '取消')
            ]) : null
        }, {
            default: () => h(SemanticSelectComponent, {
                // 传值给组件，用于回显选中的记录
                currentValue: anchor?.getAttribute('data-record-id'),
                onSelect: (data) => {
                    this.addOrUpdateLink(data, anchor);
                    destroyModal();
                }
            })
        });

        render(vnode, container);
    }

    /**
     * 核心业务：添加或更新链接
     */
    async addOrUpdateLink(params, existingAnchor: HTMLAnchorElement | null) {
        const { tableId, recordId, noteId } = params;
        if (!this.range) return;

        const node = this.range.commonAncestorContainer;
        const blockWrapper = (node.nodeType === Node.ELEMENT_NODE ? node : node.parentNode) as HTMLElement;
        const blockElement = blockWrapper.closest('.ce-block');
        let blockId = blockElement?.getAttribute('data-id') || '';

        if (typeof blockId === 'string' && editor[pid]?.[blockId]) {
            blockId = editor[pid][blockId].blockId;
        }

        // === 反向模式:从 type=25 单元格发起,携带 linkColumnId + linkItemId ===
        // 顺序约束(与 F1 一致):先 POST 创建 NoteNotelink → 拿到 id → 再 setAnchorAttributes 包裹锚点。
        // 失败时 DOM 尚未包裹,无需回滚。禁止先包裹再 POST。
        if (params.linkColumnId != null && params.linkItemId != null) {
            const isNew = !existingAnchor;
            const anchor = existingAnchor || document.createElement('a');
            const contextText = this.range.toString().trim();
            const payload = {
                noteId,
                blockId,
                linkColumnId: params.linkColumnId,
                linkItemId: params.linkItemId,
                contextText,
                itemValue: contextText,
                linkDwTableId: tableId,
                linkRecordId: recordId,
                // linkNoteId: 关联多维表格的归属笔记 id(NoteDwtable.noteId)。
                // 用户提示"在关联信息里明确对应的多维表格id":显式存储到 NoteNotelink 记录,
                // 供后续查询/跳转使用,避免依赖 DOM 属性 data-owner-note-id(历史数据缺失)
                linkNoteId: this.config.tableNoteId,
            };
            try {
                const { data } = await useFetch('system/notelink').post(payload).json();
                const linkId = data.value?.data;
                if (linkId == null) {
                    message.error('后台同步失败:未返回链接 id');
                    this.api.inlineToolbar.close();
                    this.clear();
                    return;
                }
                // 拿到 id 后包裹锚点,写入 data-link-id
                // 反向模式:tableNoteId 来自 this.config(baseTable 注入的当前表格归属笔记 id)
                this.setAnchorAttributes(anchor, { tableId, recordId, blockId, noteId, linkId, tableNoteId: this.config.tableNoteId, columnId: params.linkColumnId });
                if (isNew) {
                    let textContent = contextText || '未命名关联';
                    if (!textContent.startsWith('[') || !textContent.endsWith(']')) {
                        textContent = `[${textContent}]`;
                    }
                    anchor.innerText = textContent;
                    this.range.deleteContents();
                    this.range.insertNode(anchor);
                }
                message.success(isNew ? '关联成功' : '修改成功');
                Bus.emit(JSON.stringify({
                    type: 'semantic-link-updated',
                    data: payload
                }));
            } catch (e) {
                console.error('反向语义关联创建失败:', e);
                message.error('后台同步失败');
            }
            this.api.inlineToolbar.close();
            this.clear();
            return;
        }

        // === 正向模式 ===
        // 顺序约束(与反向模式一致):先 POST linkToDwtable 拿 columnId → 再 setAnchorAttributes(含 columnId) → 再插入 DOM。
        // 失败时 DOM 尚未包裹,无需回滚。禁止先包裹再 POST。
        const isNew = !existingAnchor;
        const anchor = existingAnchor || document.createElement('a');

        // 1. 先计算显示文本(用于 API 的 noteTitle 和 DOM 插入)
        let textContent = '';
        if (isNew) {
            const selectedText = this.range.toString().trim();
            // 如果没选文字，用记录标题，否则加中括号
            textContent = selectedText || '未命名关联';
            if (!textContent.startsWith('[') || !textContent.endsWith(']')) {
                textContent = `[${textContent}]`;
            }
        } else {
            // 修改时保留原文字内容
            textContent = anchor.innerText;
        }

        // 2. 同步到后台(先调用拿 columnId)
        const data = {
            tableId,
            recordId,
            targetId: noteId,
            sourceId: pid,
            blockId,
            noteTitle: textContent.replace(/^\[|\]$/g, ''),
            property:{
                note_id:noteId,
            }
        }
        try {
            const { data: res } = await useFetch(`system/block/linkToDwtable`).post(data).json();
            const columnId = res.value?.data;
            if (columnId == null) {
                message.error('后台同步失败:未返回 columnId');
                this.api.inlineToolbar.close();
                this.clear();
                return;
            }

            // 3. 设置属性(含 columnId)
            this.setAnchorAttributes(anchor, { tableId, recordId, blockId, noteId, tableNoteId: params.tableNoteId, columnId });

            // 4. 插入 DOM
            if (isNew) {
                anchor.innerText = textContent;
                this.range.deleteContents();
                this.range.insertNode(anchor);
            }

            message.success(isNew ? '关联成功' : '修改成功');

            // 发送事件通知 baseTable 刷新
            Bus.emit(JSON.stringify({
                type: 'semantic-link-updated',
                data
            }));
        } catch (e) {
            console.error('语义关联同步失败:', e);
            message.error('后台同步失败');
        }

        this.api.inlineToolbar.close();
        this.clear();
    }

    /**
     * 设置 A 标签属性
     * @param linkId NoteNotelink 主键 id(反向关联);正向不传,归一化为空字符串
     * @param tableNoteId 多维表格归属笔记 id(NoteDwtable.noteId);用于点击锚点跳转 /base/{tableNoteId}/{dwtableId}。
     *                   与 data-target-id(语义:目标笔记 id,正向=源笔记 pid)不同。
     */
    private setAnchorAttributes(anchor: HTMLAnchorElement, { tableId, recordId, blockId, noteId, linkId, tableNoteId, columnId }: {
        tableId: any; recordId: any; blockId: any; noteId: any; linkId?: any; tableNoteId?: any; columnId?: any;
    }) {
        anchor.setAttribute('data-type', 'semantic');
        anchor.setAttribute('data-table-id', String(tableId));
        anchor.setAttribute('data-record-id', String(recordId));
        anchor.setAttribute('data-block-id', String(blockId));
        anchor.setAttribute('data-target-id', String(noteId));
        anchor.setAttribute('data-source-id', String(pid));
        // linkId 归一化:undefined/null → 空字符串,避免 String(undefined) 产生 'undefined' 字面量
        anchor.setAttribute('data-link-id', linkId != null ? String(linkId) : '');
        // data-owner-note-id: 多维表格归属笔记 id,供 editorjs/index.vue 锚点点击跳转使用
        anchor.setAttribute('data-owner-note-id', tableNoteId != null ? String(tableNoteId) : '');
        // data-column-id: 所属 type=25 列 id,用于级联删除时 FORWARD 锚点精确匹配(避免同 table+record 多列歧义)
        anchor.setAttribute('data-column-id', columnId != null ? String(columnId) : '');
        anchor.className = this.CSS.linkActiveClass;
        anchor.href = 'javascript:void(0)';
        anchor.onclick = singleClick(() => {
            this.api.events.emit('open-semantic-detail', { tableId, recordId });
        }, 300).bind(this);
    }

    /**
     * 取消关联确认
     *
     * U6 扩展:
     * - 反向锚点(data-link-id 非空):弹框含级联警告"此操作会同时删除多维表格中的对应关联"
     * - 正向锚点(data-link-id 为空):保持原提示文案
     * - onOk 返回 Promise,antd Modal.confirm 自动禁用"确定"按钮并显示 loading;
     *   失败时显式 throw error,弹框保持打开 + 按钮恢复可点,用户可重试或取消。
     */
    confirmUnlink(anchor: HTMLAnchorElement, onDone: Function) {
        const linkId = anchor.getAttribute('data-link-id') || '';
        const isReverse = linkId !== '';
        const content = isReverse
            ? '取消后将变为普通文本，不再具备跳转和反向追溯功能。此操作会同时删除多维表格中的对应关联。'
            : '取消后将变为普通文本，不再具备跳转和反向追溯功能。';
        Modal.confirm({
            title: '确定要取消此语义关联吗？',
            icon: createVNode(ExclamationCircleOutlined),
            content,
            okText: '确定取消',
            okType: 'danger',
            cancelText: '保留关联',
            // U6: onOk 返回 Promise → antd 自动禁用按钮 + loading
            // 失败时 throw error 阻止弹框关闭,让用户看到错误并选择重试或取消
            onOk: async () => {
                try {
                    await this.executeUnlink(anchor);
                    onDone();
                } catch (e) {
                    // 抛出错误以保持弹框打开,antd 会捕获并保持按钮可点
                    console.error('取消关联失败:', e);
                    message.error('取消关联失败,请重试或保留关联');
                    throw e;
                }
            }
        });
    }

    /**
     * 执行真正的 DOM 还原和后端解绑
     *
     * U6 linkId 处理约定:
     * - 反向锚点(data-link-id 非空):调 DELETE /system/notelink/{linkId} 删除该条关联记录
     *   + 移除 store.references 中的缓存条目(让 U5 角标与面板同步消失)
     *   + DOM 还原 + emit semantic-link-updated 通知 baseTable 重新查询刷新单元格(U4 双源合并)
     * - 正向历史锚点(data-link-id 为空):保持现有 removeLink 调用(走 stub,不删 NoteNotelink)
     * - last-anchor 处理:删除后聚合查询返回空 → 单元格展示空态(U4 最小状态机的 empty 态 "—"),
     *   不删除单元格本身(由 baseTable 自动处理,无需特殊逻辑)
     */
    async executeUnlink(anchor: HTMLAnchorElement) {
        const payload = {
            tableId: anchor.getAttribute('data-table-id'),
            recordId: anchor.getAttribute('data-record-id'),
            blockId: anchor.getAttribute('data-block-id'),
            noteId: anchor.getAttribute('data-target-id'),
            linkId: anchor.getAttribute('data-link-id') || '',
        };

        // 还原为普通文本节点（去掉中括号）
        const textNode = document.createTextNode(anchor.innerText.replace(/^\[|\]$/g, ''));

        const { tableId, recordId, blockId, noteId, linkId } = payload;

        // === U6: 反向锚点(linkId 非空)→ 走 NoteNotelink DELETE 路径 ===
        if (linkId !== '') {
            try {
                const { data } = await useFetch(`system/notelink/${linkId}`).delete().json();
                if (!data.value || data.value.code !== 200) {
                    throw new Error(data.value?.msg || '删除失败');
                }
                // 删除成功:DOM 还原
                anchor.parentNode?.replaceChild(textNode, anchor);
                // 移除 store.references 中的缓存条目(角标与面板同步消失)
                if (noteId) {
                    store.removeReference(noteId, linkId);
                }
                message.success('已取消关联');
                // emit semantic-link-updated 通知 baseTable 重新聚合查询刷新单元格
                Bus.emit(JSON.stringify({
                    type: 'semantic-link-updated',
                    data: { tableId, recordId, blockId, noteId, linkId, action: 'delete' }
                }));
            } catch (e) {
                console.error('反向关联删除失败:', e);
                // 重新抛出,让 onOk catch 处理(message.error + 阻止弹框关闭)
                throw e;
            }
            this.clear();
            return;
        }

        // === 正向锚点(linkId 为空)→ 保持现有 removeLink 调用 ===
        try {
            const { data } = await useFetch(`system/block/removeLink`).post({
                tableId,
                recordId,
                targetId: noteId,
                sourceId: pid,
                blockId,
                noteTitle: textNode.textContent.replace(/^\[|\]$/g, '')
            }).json();
            if (data.value) {
                anchor.parentNode?.replaceChild(textNode, anchor);
                message.success('已取消关联');
                // 正向锚点删除也通知 baseTable 刷新(虽然没有 NoteNotelink,但单元格展示值可能变化)
                Bus.emit(JSON.stringify({
                    type: 'semantic-link-updated',
                    data: { tableId, recordId, blockId, noteId, linkId: '', action: 'delete' }
                }));
            }
        } catch (e) {
            console.error('删除关联同步失败:', e);
            throw e;
        }
        this.clear();
    }

    clear() {
        // this.range = null;
        // // 清除选区信息，确保下次 surround 获取的是新鲜的
        window.getSelection()?.removeAllRanges();
    }
}
