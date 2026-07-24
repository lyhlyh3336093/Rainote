import { API, ReadOnly } from "@editorjs/editorjs";
import { render, createVNode } from 'vue';
import { Divider as antDivider } from 'ant-design-vue';
export default class Divider {

    api: API;

    config: any;

    data: any;

    readOnly: ReadOnly;

    nodes: {
        wrapper: HTMLDivElement;
    }


    CSS = {
        wrapper: ['ce-block-divider-wrapper', 'h-5','flex','items-center']
    }
    constructor({ data, config, api, readOnly }) {
        this.api = api;
        this.data = data;
        this.config = config;
        this.readOnly = readOnly;
        this.nodes = {
            wrapper: document.createElement('div')
        };
        this.nodes.wrapper.classList.add(...this.CSS.wrapper);

    }

    static get toolbox() {
        return {
            icon: '<svg t="1683774354452" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="13398" width="200" height="200"><path d="M153.6 153.6h716.8a51.2 51.2 0 0 1 0 102.4H153.6a51.2 51.2 0 1 1 0-102.4z m0 614.4h716.8a51.2 51.2 0 0 1 0 102.4H153.6a51.2 51.2 0 0 1 0-102.4z m0-307.2h131.6352a51.2 51.2 0 1 1 0 102.4H153.6a51.2 51.2 0 0 1 0-102.4z m292.5568 0h131.6864a51.2 51.2 0 0 1 0 102.4H446.1568a51.2 51.2 0 0 1 0-102.4z m292.608 0H870.4a51.2 51.2 0 0 1 0 102.4h-131.6352a51.2 51.2 0 0 1 0-102.4z" fill="#666666" p-id="13399"></path></svg>',
            title: '分割线',
        }
    }
    render() {
        render(createVNode(antDivider, { style: { border: 'none', height: '2px', margin: 0, background: 'rgba(0, 0, 0, 0.06)' } }), this.nodes.wrapper);

        const block = document.createElement('div');
        block.classList.add(this.api.styles.block);
        block.appendChild(this.nodes.wrapper);
        return block;
    }
    save() {
        return ''
    }

}