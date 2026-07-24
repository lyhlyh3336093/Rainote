import BaseClass from '../../BaseClass';
import { useStore } from '../../../../../stores/editor';
import { v4 as uuidv4 } from 'uuid';
const store = useStore();
export default class Anchor extends BaseClass {
    icon: string;
    tag: string = 'C';

    constructor(params) {
        super(params);

        this.icon = '<svg t="1702700667271" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="1789" width="200" height="200"><path d="M512 896c-200.533333-145.066667-298.666667-285.866667-298.666667-426.666667 0-166.4 132.266667-298.666667 298.666667-298.666666s298.666667 132.266667 298.666667 298.666666c0 140.8-98.133333 281.6-298.666667 426.666667z m0-119.466667c140.8-106.666667 213.333333-209.066667 213.333333-307.2 0-119.466667-93.866667-213.333333-213.333333-213.333333s-213.333333 93.866667-213.333333 213.333333c0 98.133333 72.533333 200.533333 213.333333 307.2z m0-179.2c-72.533333 0-128-55.466667-128-128s55.466667-128 128-128 128 55.466667 128 128-55.466667 128-128 128z m0-85.333333c25.6 0 42.666667-17.066667 42.666667-42.666667s-17.066667-42.666667-42.666667-42.666666-42.666667 17.066667-42.666667 42.666666 17.066667 42.666667 42.666667 42.666667z" fill="#444444" p-id="1790"></path></svg>'

    }

    static get isInline() {
        return true
    }

    static get title() {
        return '锚点';
    }

    static get sanitize() {
        return {
            c: {
                id: true
            }
        };
    }

    render() {
        this.button = document.createElement('button');
        this.button.type = 'button';
        this.button.classList.add(...[this.api.styles.inlineToolButton]);

        this.button.innerHTML = this.icon;
        return this.button;
    }

    surround(range) {
        if (!range) {
            return;
        }
        const termWrapper = this.api.selection.findParentTag(this.tag);
        if (termWrapper) {
            this.unWrap(termWrapper);
        } else {
            this.wrap(range);
        }
    }

    wrap(range) {
        const u = document.createElement(this.tag);
        u.id = uuidv4();
        u.appendChild(range.extractContents());
        range.insertNode(u);
        this.api.selection.expandToTag(u);
        store.addAnchor({
            href: u.id,
            title: u.outerText
        });
    }

    unWrap(termWrapper) {
        store.removeAnchor(termWrapper.id)
        this.api.selection.expandToTag(termWrapper);

        const sel = window.getSelection();
        const range = sel.getRangeAt(0);

        const unwrappedContent = range.extractContents();

        termWrapper.parentNode.removeChild(termWrapper);

        range.insertNode(unwrappedContent);
        sel.removeAllRanges();
        sel.addRange(range);
    }

    checkState() {
    }

}

