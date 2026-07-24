import './index.css';
import { render, createVNode } from 'vue';
import { message } from 'ant-design-vue';
import component from '../../../components/link/select.vue';
import { getBolckId, singleClick } from '../../utils';
import BaseClass from '../../BaseClass';
import { useStore } from "../../../../../stores/editor";
import { useFetch } from '../../../../../hooks';

const store = useStore();
const { editor, id: pid } = store;

export default class Link extends BaseClass {
    icon: { link: string; unlink: string };
    nodes: {
        wrapper: HTMLDivElement;
        button: HTMLButtonElement;
    };

    // 存储当前操作的 range
    range: Range | null = null;
    target: HTMLAnchorElement | null = null;

    CSS = {
        wrapper: ['ce-inline-tool-link-wrapper', 'p-3', 'flex-col', 'w-64', 'flex', 'hidden'],
        button: 'ce-inline-tool--link',
        wrapperShowed: 'hidden',
    };

    constructor(params) {
        super(params);
        this.icon = {
            link: '<svg t="1681116034924" class="icon" viewBox="0 0 1024 1024" width="20" height="20"><path d="M369.066667 594.773333l225.706666-225.706666a21.333333 21.333333 0 0 1 30.293334 0l29.866666 29.866666a21.333333 21.333333 0 0 1 0 30.293334l-225.706666 225.706666a21.333333 21.333333 0 0 1-30.293334 0l-29.866666-29.866666a21.333333 21.333333 0 0 1 0-30.293334zM896 326.826667V341.333333a170.666667 170.666667 0 0 1-50.346667 121.173334L725.333333 583.253333a57.6 57.6 0 0 1-81.066666 0l-4.266667-4.693333a21.333333 21.333333 0 0 1 0-29.866667l146.773333-146.773333A85.333333 85.333333 0 0 0 810.666667 341.333333v-14.506666a85.333333 85.333333 0 0 0-25.173334-60.586667l-27.733333-27.733333A85.333333 85.333333 0 0 0 697.173333 213.333333H682.666667a85.333333 85.333333 0 0 0-60.586667 25.173334L475.306667 384a21.333333 21.333333 0 0 1-29.866667 0l-4.693333-4.693333a57.6 57.6 0 0 1 0-81.066667l120.746666-121.173333A170.666667 170.666667 0 0 1 682.666667 128h14.506666a170.666667 170.666667 0 0 1 120.746667 49.92l28.16 28.16A170.666667 170.666667 0 0 1 896 326.826667zM548.693333 640a21.333333 21.333333 0 0 1 29.866667 0l4.693333 4.693333a57.6 57.6 0 0 1 0 81.066667l-121.6 121.6A170.666667 170.666667 0 0 1 341.333333 896h-14.506666a170.666667 170.666667 0 0 1-120.746667-49.92l-28.16-28.16A170.666667 170.666667 0 0 1 128 697.6V682.666667a170.666667 170.666667 0 0 1 50.346667-121.173334L298.666667 440.746667a57.6 57.6 0 0 1 81.066666 0l4.693334 4.693333a21.333333 21.333333 0 0 1 0 29.866667l-145.92 146.773333A85.333333 85.333333 0 0 0 213.333333 682.666667v14.506666a85.333333 85.333333 0 0 0 25.173334 60.586667l27.733333 27.733333a85.333333 85.333333 0 0 0 60.586667 25.173334H341.333333a85.333333 85.333333 0 0 0 61.013334-25.173334z" p-id="11872"></path></svg>',
            unlink: '<svg t="1681116045935" class="icon" viewBox="0 0 1024 1024" width="20" height="20"><path d="M186.88 134.4a20.48 20.48 0 0 0-29.866667 0l-22.613333 22.613333a20.48 20.48 0 0 0 0 29.866667l209.066667 209.493333-165.12 165.12A170.666667 170.666667 0 0 0 128 682.666667v14.506666a170.666667 170.666667 0 0 0 49.92 120.746667l28.16 28.16A170.666667 170.666667 0 0 0 326.826667 896H341.333333a170.666667 170.666667 0 0 0 120.746667-49.92l165.973333-165.973333 209.493334 209.066666a20.48 20.48 0 0 0 29.866666 0l22.613334-22.613333a20.48 20.48 0 0 0 0-29.866667z m213.333333 651.093333A85.333333 85.333333 0 0 1 341.333333 810.666667h-14.506666a85.333333 85.333333 0 0 1-60.586667-25.173334l-27.733333-27.733333A85.333333 85.333333 0 0 1 213.333333 697.173333V682.666667a85.333333 85.333333 0 0 1 25.173334-60.586667l165.546666-165.546667 51.626667 52.053334-85.333333 85.333333a21.333333 21.333333 0 0 0 0 30.293333l29.866666 29.866667a21.333333 21.333333 0 0 0 30.293334 0l85.333333-85.333333 51.626667 51.2zM846.08 206.08l-28.16-28.16A170.666667 170.666667 0 0 0 697.173333 128H682.666667a170.666667 170.666667 0 0 0-121.173334 50.346667l-112.213333 112.213333L512 351.146667l112.213333-112.64A85.333333 85.333333 0 0 1 682.666667 213.333333h14.506666a85.333333 85.333333 0 0 1 60.586667 25.173334l27.733333 27.733333A85.333333 85.333333 0 0 1 810.666667 326.826667V341.333333a85.333333 85.333333 0 0 1-25.173334 60.586667l-112.213333 112.64 60.16 60.16 112.213333-112.213333A170.666667 170.666667 0 0 0 896 341.333333v-14.506666a170.666667 170.666667 0 0 0-49.92-120.746667z m-191.146667 192.853333l-29.866666-29.866666a21.333333 21.333333 0 0 0-30.293334 0l-33.28 33.706666 60.16 60.16 33.28-33.706666a21.333333 21.333333 0 0 0 0-30.293334z" p-id="12014"></path></svg>',
        };
        this.nodes = {
            wrapper: document.createElement('div'),
            button: document.createElement('button'),
        };
        this.nodes.button.type = 'button';
        this.nodes.button.classList.add(this.api.styles.inlineToolButton, this.CSS.button);
        this.nodes.button.innerHTML = this.icon.link;
    }

    static get isInline() { return true; }
    static get title() { return '链接'; }
    static get sanitize() {
        return {
            a: { link: true, href: true, onclick: true, class: true }
        };
    }

    render() { return this.nodes.button; }

    renderActions() {
        this.nodes.wrapper.classList.add(...[...this.CSS.wrapper, this.api.styles.block]);
        return this.nodes.wrapper;
    }

    // 从 Range 获取父级 A 标签的辅助函数
    getAnchorFromRange(range: Range): HTMLAnchorElement | null {
        let node = range.commonAncestorContainer;
        if (node.nodeType !== Node.ELEMENT_NODE) {
            node = node.parentNode!;
        }
        return (node as HTMLElement).closest('a');
    }

    surround(range: Range) {
        if (!range) return;
        this.range = range.cloneRange(); // 克隆 Range 以防被系统重置
        this.target = this.getAnchorFromRange(this.range);

        this.toggleActions();
    }

    toggleActions() {
        this.nodes.wrapper.classList.toggle(this.CSS.wrapperShowed);
        if (this.nodes.wrapper.classList.contains(this.CSS.wrapperShowed)) {
        } else {
            // this.nodes.input.link.focus();
            render(createVNode(component, {
                style: {
                    margin: '10px 0'
                },
                onClick: (data) => {
                    this.addLink(data);
                }
            }), this.nodes.wrapper);
        }


    }

    addLink(params) {
        const { link: value, desc, type } = params;
        if (type === '外部链接' && (!value || !this.validateURL(value))) {
            return message.warning('请输入正确的链接');
        }
        if (!desc) return message.warning('请输入描述');

        if (!this.range) return;

        let anchor = this.getAnchorFromRange(this.range);
        let links = [];
        let blockId = getBolckId(this.api);

        if (typeof blockId === 'string' && editor[pid][blockId]) {
            blockId = editor[pid][blockId].blockId;
        }

        if (anchor) {
            try {
                links = JSON.parse(anchor.getAttribute('link') || '[]');
            } catch (e) { links = []; }
        } else {
            anchor = document.createElement('a');
        }

        const title = this.range.toString() || desc;
        links.push({ value, desc, title, id: blockId });

        // 设置属性
        anchor.setAttribute('link', JSON.stringify(links));
        anchor.href = 'javascript:void(0)';
        anchor.onclick = singleClick(() => {
            this.api.events.emit('click-link', links);
        }, 300).bind(this);

        if (!this.getAnchorFromRange(this.range)) {
            if (!this.range.collapsed) {
                anchor.appendChild(this.range.extractContents());
            } else {
                anchor.innerText = title;
            }
            this.range.insertNode(anchor);
        } else {
            if (this.range.toString()) {
                anchor.innerText = this.range.toString();
            }
        }
        const sel = window.getSelection();
        if (sel) {
            sel.removeAllRanges();
            const newRange = document.createRange();
            newRange.selectNodeContents(anchor);
            newRange.collapse(false); // 光标移至末尾
            sel.addRange(newRange);
        }

        this.api.inlineToolbar.close();

        // if (type === '内部链接') {
        //     const p = location.href.split('/');
        //     useFetch(`system/block/update`).post({
        //         id: blockId,
        //         parentId: p[p.length - 1].split('#')[0],
        //         property: JSON.stringify({ text: anchor.outerHTML }),
        //         blockType: 2
        //     }).json();
        // }
    }

    validateURL(str = '') {
        try {
            const url = new URL(str.startsWith('http') ? str : `https://${str}`);
            return !!url.host;
        } catch { return false; }
    }

    clear() {
        render(null, this.nodes.wrapper);
        this.range = null;
    }
}