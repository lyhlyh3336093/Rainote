import { API, BlockAPI } from "@editorjs/editorjs";
interface BaseClassProps {
    api: API;
    readOnly: boolean,
    block: BlockAPI,
    config: any;
    data: any;
}
export default class BaseClass {
    api: API;
    readOnly: boolean;
    block: BlockAPI;
    config: any;
    data: any;
    wrapper:HTMLElement;
    constructor({ api, readOnly, block, config, data }: BaseClassProps) {
        this.api = api;
        this.readOnly = readOnly;
        this.block = block;
        this.config = config;
        this.data = data;
        this.wrapper=document.createElement('div');
        this.wrapper.classList.add(this.api.styles.block);
    }
}