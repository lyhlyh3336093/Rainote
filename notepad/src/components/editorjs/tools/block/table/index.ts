import { storeToRefs } from "pinia";
import { useStore } from "../../../../../stores/editor";
import { make } from "../../../utils/dom"
import BaseClass from "../../BaseClass"
import './index.scss';
const store = useStore();
const { id } = storeToRefs(store);
export default class BaseTable extends BaseClass {
    CSS = {
        wrapper: ['ce-block-base-table-wrapper','py-1'],
    }
    iframe: HTMLIFrameElement;
    wrapper: HTMLElement;
    id: string;
    constructor(params) {
        super(params);
        this.iframe = make('iframe');
        this.wrapper = make('div', this.CSS.wrapper);
    }

    static get toolbox() {
        return {
            icon: '<svg t="1684640848372" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="19893" width="200" height="200"><path d="M901.12 164.864H123.392c-15.872 0-28.672 12.8-28.672 28.672v636.928c0 15.872 12.8 28.672 28.672 28.672H901.12c15.872 0 28.672-12.8 28.672-28.672V193.536c0-15.872-12.8-28.672-28.672-28.672z m-35.328 211.968H362.496V228.864h503.808v147.968zM362.496 433.664h503.808v156.16H362.496V433.664zM307.2 589.824H158.72V433.664h148.48v156.16z m0-360.96v148.48H158.72v-148.48h148.48z m-148.48 415.744h148.48v150.528H158.72v-150.528z m203.776 150.528v-150.528h503.808v150.528H362.496z" fill="#2C2C2C" p-id="19894"></path></svg>',
            title: '多维表格',
        }
    }
    static get enableLineBreaks() {
        return true;
    }

    render() {
        const { dwtableId } = this.data;
        if (dwtableId) {
            this.iframe.src = `/base/${dwtableId}?code=true&name=表格`;
        }
        const block = make('div', [this.api.styles.block]);
        this.wrapper.appendChild(this.iframe);
        block.appendChild(this.wrapper);
        return block;
    }
    save() {
        const { dwtableId } = this.data;
        return {
            dwtableId
        };
    }
}