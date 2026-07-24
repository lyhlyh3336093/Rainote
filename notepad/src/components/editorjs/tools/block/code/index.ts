import './index.min.css';
import ace, { Ace } from 'ace-builds/src-noconflict/ace.js';
// 使用本地路径而不是CDN，避免eval警告
ace.config.set('basePath', '/node_modules/ace-builds/src-noconflict');
import BaseClass from '../../BaseClass';
import { make } from '../../../utils/dom';
import 'ace-builds/src-noconflict/ext-language_tools';
import modelist from 'ace-builds/src-noconflict/ext-modelist';
import themelist from 'ace-builds/src-noconflict/ext-themelist';
import Toolbox from '../../../utils/toolbox';
const theme = themelist.themes.map(item => ({ value: item.theme, label: item.name }));
const mode = modelist.modes.map(item => ({ value: item.mode, label: item.name }));
export default class Code extends BaseClass {
    CSS = {
        wrapper: ['ce-block-code-wrapper', this.api.styles.block, 'tc-wrap'],
        node: ['h-full'],
        body: ['ce-block-code-body', 'h-60', 'my-1']
    }
    wrapper: HTMLElement;
    body: HTMLElement;
    node: HTMLElement;
    editor: Ace.Editor;
    language: string = 'javascript';
    theme: string = 'monokai';
    toolbox: Toolbox;
    constructor(config) {
        super(config);
        if (this.data?.mode) {
            this.language = this.data.mode;
            this.theme = this.data.theme;
        }
        this.createNodes();
    }

    static get enableLineBreaks() {
        return true;
    }
    createNodes() {
        this.wrapper = make('div', this.CSS.wrapper);
        this.node = make('div', this.CSS.node, {
            id: `ace-${this.block.id}`,
        });
        this.body = make('div', this.CSS.body);
        this.wrapper.appendChild(this.body);
        this.toolbox = this.createToolbox();
        this.body.appendChild(this.node);
        this.wrapper.appendChild(this.toolbox.element);
        this.editor = ace.edit(this.node, {
            enableBasicAutocompletion: true,
            enableLiveAutocompletion: true,
            showPrintMargin: false,
            autoScrollEditorIntoView: false,
            tabSize: 8,
            readOnly: this.readOnly,
            animatedScroll: true,
            wrap: true,
            fontSize: 16,
            theme: theme.find(item => item.label === this.theme)?.value,
            mode: mode.find(item => item.label === this.language)?.value,
            enableSnippets: true,
            useWorker: false, // 禁用worker避免eval警告
            useSoftTabs: true,
            value: this.data.value,
        });
    }

    createSelect(data = [], selected = '', onchange = (any) => { }) {
        const select = make('select');
        select.onchange = onchange;
        data.forEach(item => {
            const option = make('option');
            if (item.label === selected) {
                option.selected = true;
            }
            option.label = item.label;
            option.value = item.value;
            select.appendChild(option);
        });
        select.style.width = '300px';
        return [select]
    }

    render() {
        const block = make('div', [this.api.styles.block]);
        block.appendChild(this.wrapper);
        return block;
    }

    static get toolbox() {
        return {
            icon: '<svg t="1684214402924" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="2543" width="200" height="200"><path d="M153.770667 517.558857l200.387047-197.241905L302.86019 268.190476 48.761905 518.290286l254.439619 243.614476 50.590476-52.833524-200.021333-191.512381zM658.285714 320.316952L709.583238 268.190476l254.098286 250.09981L709.241905 761.904762l-50.590476-52.833524 200.021333-191.512381L658.285714 320.316952z m-112.981333-86.186666L393.99619 785.554286l70.534096 19.358476 151.30819-551.399619-70.534095-19.358476z" p-id="2544"></path></svg>',
            title: '代码块',
        }
    }

    createToolbox() {

        return new Toolbox({
            api: this.api,
            items: [
                {
                    label: '主题',
                    children: this.createSelect(theme, this.theme, event => {
                        const value = event.target.value;
                        this.editor.setTheme(value);
                        this.theme = theme.find(item => item.value === value).label;
                        this.block.dispatchChange();
                    }),

                },
                {
                    label: '语言',
                    children: this.createSelect(mode, this.language, event => {
                        const value = event.target.value;
                        this.editor.session.setMode(value);
                        this.language = mode.find(item => item.value === value).label;
                        this.block.dispatchChange();
                    }),

                },
            ],
            classNames: ['ce-block-toolbox']
        });
    }
    save() {
        if (!this.editor) return {};
        return {
            mode: this.language,
            theme: this.theme,
            value: this.editor.getValue()
        }
    }

}
