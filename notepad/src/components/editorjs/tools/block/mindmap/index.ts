import { API, ReadOnly } from "@editorjs/editorjs";
export default class Mindmap {

    api: API;

    config: any;

    data: any;

    readOnly: ReadOnly;

    nodes: {
        wrapper: HTMLDivElement;
    }


    CSS = {
        wrapper: ['ce-block-mindmap-wrapper', 'h-96', 'border', 'border-slate-300', 'border-solid', 'cursor-pointer']
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
            icon: '<svg t="1681633771071" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="11871" width="200" height="200"><path d="M896 597.333333h-67.413333l-34.133334-170.666666H810.666667a42.666667 42.666667 0 0 0 42.666666-42.666667V213.333333a42.666667 42.666667 0 0 0-42.666666-42.666666h-170.666667a42.666667 42.666667 0 0 0-42.666667 42.666666v42.666667h-170.666666V213.333333a42.666667 42.666667 0 0 0-42.666667-42.666666H213.333333a42.666667 42.666667 0 0 0-42.666666 42.666666v170.666667a42.666667 42.666667 0 0 0 42.666666 42.666667h16.213334l-34.133334 170.666666H128a42.666667 42.666667 0 0 0-42.666667 42.666667v170.666667a42.666667 42.666667 0 0 0 42.666667 42.666666h170.666667a42.666667 42.666667 0 0 0 42.666666-42.666666v-42.666667h341.333334v42.666667a42.666667 42.666667 0 0 0 42.666666 42.666666h170.666667a42.666667 42.666667 0 0 0 42.666667-42.666666v-170.666667a42.666667 42.666667 0 0 0-42.666667-42.666667z m-213.333333-341.333333h85.333333v85.333333h-85.333333zM256 256h85.333333v85.333333H256z m0 512H170.666667v-85.333333h85.333333z m426.666667-128v42.666667H341.333333v-42.666667a42.666667 42.666667 0 0 0-42.666666-42.666667h-16.213334l34.133334-170.666666H384a42.666667 42.666667 0 0 0 42.666667-42.666667V341.333333h170.666666v42.666667a42.666667 42.666667 0 0 0 42.666667 42.666667h67.413333l34.133334 170.666666H725.333333a42.666667 42.666667 0 0 0-42.666666 42.666667z m170.666666 128h-85.333333v-85.333333h85.333333z" p-id="11872"></path></svg>',
            title: '思维导图',
        }
    }
    render() {
        const iframe=document.createElement('iframe');
        iframe.src='/mindmap';
        this.nodes.wrapper.appendChild(iframe);
        const block=document.createElement('div');
        block.classList.add(this.api.styles.block);
        block.appendChild(this.nodes.wrapper);
        return block;
    }
    save(toolsContent){
        return {

        }
    }
}