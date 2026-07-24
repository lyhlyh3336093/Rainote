import {API, ReadOnly, BlockAPI} from "@editorjs/editorjs";
import color from 'material-colors';
import './index.min.css';
import Toolbox from '../../../utils/toolbox';

const convertField = {
    'font': 'color',
    'border': 'border-color',
    'background': 'background-color'
}
export default class Callout {

    api: API;

    config: any = {
        font: '',
        border: '',
        background: '',
    };

    data: any;

    readOnly: ReadOnly;
    block: BlockAPI;

    nodes: {
        wrapper: HTMLDivElement;
        input: HTMLDivElement;
    }


    CSS = {
        wrapper: ['ce-block-callout-wrapper', 'tc-wrap']
    }

    style = {
        borderWidth: '2px',
        borderStyle: 'solid',
        borderRadius: '5px',
        padding: '4px',
        borderColor: '#eee',
    }
    colors: any[];
    toolbox: Toolbox;


    constructor({data, config, api, readOnly, block}) {
        this.api = api;
        this.data = data;
        this.config = config;
        this.readOnly = readOnly;
        this.nodes = {
            wrapper: document.createElement('div'),
            input: document.createElement('div')
        };
        this.nodes.wrapper.classList.add(...this.CSS.wrapper);
        this.colors = Object.values(color).map(item => {
            if (item?.['100']) return item;
            if (item?.['primary']) return {
                '100': item['dividers'],
                '500': item['primary']
            }
            return false;
        }).filter(Boolean).reverse();
        if (this.data) {
            const {font, border, background} = this.data;
            this.config.font = font;
            this.config.border = border;
            this.config.background = background;
        } else {
            this.config.font = this.colors[0]['500'];
            this.config.border = this.colors[1]['500'];
            this.config.background = this.colors[2]['100'];
        }
        this.toolbox = this.createToolbox();
    }

    static get enableLineBreaks() {
        return true;
    }

    static get toolbox() {
        return {
            icon: '<svg t="1683858063893" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="16574" width="200" height="200"><path d="M398.848 474.624h-256a81.92 81.92 0 0 1-81.92-81.92v-256a81.92 81.92 0 0 1 81.92-81.92h256a81.92 81.92 0 0 1 81.92 81.92v256a81.92 81.92 0 0 1-81.92 81.92z m-256-358.4a20.48 20.48 0 0 0-20.48 20.48v256a20.48 20.48 0 0 0 20.48 20.48h256a20.48 20.48 0 0 0 20.48-20.48v-256a20.48 20.48 0 0 0-20.48-20.48zM881.152 604.672h-256a81.92 81.92 0 0 1-81.92-81.92V136.704a81.92 81.92 0 0 1 81.92-81.92h256a81.92 81.92 0 0 1 81.92 81.92v386.048a81.92 81.92 0 0 1-81.92 81.92z m-256-488.448a20.48 20.48 0 0 0-20.48 20.48v386.048a20.48 20.48 0 0 0 20.48 20.48h256a20.48 20.48 0 0 0 20.48-20.48V136.704a20.48 20.48 0 0 0-20.48-20.48zM881.152 963.072h-256a81.92 81.92 0 0 1-81.92-81.92v-124.16a81.92 81.92 0 0 1 81.92-81.92h256a81.92 81.92 0 0 1 81.92 81.92v124.16a81.92 81.92 0 0 1-81.92 81.92z m-256-226.56a20.48 20.48 0 0 0-20.48 20.48v124.16a20.48 20.48 0 0 0 20.48 20.48h256a20.48 20.48 0 0 0 20.48-20.48v-124.16a20.48 20.48 0 0 0-20.48-20.48zM398.848 963.072h-256a81.92 81.92 0 0 1-81.92-81.92v-256a81.92 81.92 0 0 1 81.92-81.92h256a81.92 81.92 0 0 1 81.92 81.92v256a81.92 81.92 0 0 1-81.92 81.92z m-256-358.4a20.48 20.48 0 0 0-20.48 20.48v256a20.48 20.48 0 0 0 20.48 20.48h256a20.48 20.48 0 0 0 20.48-20.48v-256a20.48 20.48 0 0 0-20.48-20.48z" fill="" p-id="16575"></path></svg>',
            title: '高亮块',
        }
    }

    render() {
        this.nodes.wrapper.appendChild(this.toolbox.element);
        this.nodes.input.contentEditable = 'true';
        for (const key in this.style) {
            this.nodes.input.style[key] = this.style[key];
        }
        for (const key in this.config) {
            this.nodes.input.style[convertField[key]] = this.config[key];
        }
        if (this.data) {
            this.nodes.input.textContent = this.data.text;
        }
        this.nodes.input.style.minHeight = '100px';
        this.nodes.wrapper.appendChild(this.nodes.input);
        const block = document.createElement('div');
        block.classList.add(this.api.styles.block);
        block.appendChild(this.nodes.wrapper);
        return block;
    }

    save(toolsContent) {
        return {
            ...this.config,
            text: this.nodes.input.textContent
        }
    }

    createColor(type) {
        return this.colors.map(item => {
            const div = document.createElement('div');
            div.classList.add(...['w-6', 'h-6', 'flex', 'justify-center', 'm-1', 'items-center']);
            for (const key in this.style) {
                div.style[key] = this.style[key];
            }
            const color = type === 'background' ? item['100'] : item['500'];

            if (type === 'font') {
                div.style.color = item['500'];
            } else {
                div.style.background = item['500'];
            }
            div.style.borderColor = '#eee';
            if (this.config[type] === color) {
                div.style.borderColor = '#1890ff';
            }
            div.textContent = type === 'font' ? 'A' : '';
            div.onclick = () => {
                this.config[type] = color;
                div.parentNode.childNodes.forEach((el: HTMLElement) => el.style.borderColor = '#eee');
                div.style.borderColor = '#1890ff';
                this.nodes.input.style[convertField[type]] = color;
            }
            return div;
        })
    }

    createToolbox() {
        return new Toolbox({
            api: this.api,
            items: [
                {
                    label: '字体颜色',
                    children: this.createColor('font'),
                },
                {
                    label: '背景颜色',
                    children: this.createColor('background'),
                },
                {
                    label: '边框颜色',
                    children: this.createColor('border'),
                }
            ],
            classNames: ['ce-block-toolbox']
        });
    }
}