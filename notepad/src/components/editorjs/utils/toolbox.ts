import Popover from './popover';
import * as $ from './dom';
import { API } from '@editorjs/editorjs';
const ToolboxIcon = `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18">
<rect width="18" height="18" fill="#F4F5F7" rx="2"/>
<circle cx="11.5" cy="6.5" r="1.5"/>
<circle cx="11.5" cy="11.5" r="1.5"/>
<circle cx="6.5" cy="6.5" r="1.5"/>
<circle cx="6.5" cy="11.5" r="1.5"/>
</svg>
`;

export interface Items {
  label: string;
  onClick?: () => void;
  icon?: string;
  children?: HTMLElement[]
}
interface ToolboxProps {
  api: API;
  items?: Items[];
  onOpen?: () => void;
  onClose?: () => void;
  classNames?: string[];
  toggler?: (target: HTMLElement) => void;
}

export default class Toolbox {
  api: any;
  items?: Items[];
  onOpen?: () => void;
  onClose?: () => void;
  popover: Popover;
  wrapper: HTMLElement;
  classNames: string[];
  opened: boolean = false;
  toggler?: (target: HTMLElement) => void;

  constructor({ api, items, onOpen, onClose, classNames = [], toggler }: ToolboxProps) {
    this.api = api;

    this.items = items;
    this.onOpen = onOpen;
    this.onClose = onClose;
    this.classNames = classNames;
    this.popover = null;
    this.toggler = toggler;
    this.wrapper = this.createToolbox();
  }


  static get CSS() {
    return {
      toolbox: 'tc-toolbox',
      toolboxShowed: 'tc-toolbox--showed',
      toggler: 'tc-toolbox__toggler'
    };
  }


  get element() {
    return this.wrapper;
  }


  createToolbox() {
    const wrapper = $.make('div', [
      ...this.classNames,
      Toolbox.CSS.toolbox,

    ]);
    const toggler = this.createToggler();
    wrapper.appendChild(toggler);
    if (this.items?.length>0) {
      const popover = this.createPopover();
      wrapper.appendChild(popover);
    }

    return wrapper;
  }


  createToggler() {
    const toggler = $.make('div', Toolbox.CSS.toggler);
    if (!this.toggler) {
      toggler.innerHTML = ToolboxIcon;
      toggler.addEventListener('click', (event) => {
        event.cancelBubble = true;
        this.togglerClicked();
      });
    } else {
      this.toggler(toggler);
    }

    return toggler;
  }

  createPopover() {
    this.popover = new Popover({
      items: this.items
    });

    return this.popover.render();
  }


  togglerClicked() {
    if (this.opened) {
      this.popover.close();
      this?.onClose?.();
    } else {
      this.popover.open();
      this?.onOpen?.();
    }
    this.opened = !this.opened;
  }


  show(computePositionMethod) {
    const position = computePositionMethod();
    Object.entries(position).forEach(([prop, value]) => {
      this.wrapper.style[prop] = value;
    });
    this.wrapper.classList.add(Toolbox.CSS.toolboxShowed);
  }


  hide() {
    this.opened = false;
    this.popover.close();
    this.wrapper.classList.remove(Toolbox.CSS.toolboxShowed);
  }
}