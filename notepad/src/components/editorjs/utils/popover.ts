import * as $ from './dom';
import { onClickOutside } from '@vueuse/core'
import { Items } from './toolbox';

export default class Popover {
  items: Items[];
  wrapper: HTMLElement;
  itemEls: HTMLElement[];
  constructor({ items }) {
    this.items = items;
    this.wrapper = undefined;
    this.itemEls = [];
  }


  static get CSS() {
    return {
      popover: 'tc-popover',
      popoverOpened: 'tc-popover--opened',
      item: 'tc-popover__item',
      itemIcon: 'tc-popover__item-icon',
      itemLabel: 'tc-popover__item-label'
    };
  }

  render() {
    this.wrapper = $.make('div', Popover.CSS.popover);
    onClickOutside(this.wrapper, () => {
      this.close();
    })
    this.items.forEach((item, index) => {
      const itemEl = $.make('div', Popover.CSS.item);
      let label = $.make('div', Popover.CSS.itemLabel, {
        innerHTML: item.label
      });
      itemEl.appendChild(label);
      if (item?.children) {
        itemEl.classList.add('tc-popover__children');
        const div = $.make('div');
        div.classList.add(...['flex', 'flex-wrap','children']);
        itemEl.appendChild(div);
        item.children.map(child => div.appendChild(child));
      }

      itemEl.dataset.index = index;
      if (item?.icon) {
        const icon = $.make('div', Popover.CSS.itemIcon, {
          innerHTML: item.icon
        });
        itemEl.appendChild(icon);
      }


      this.wrapper.appendChild(itemEl);
      this.itemEls.push(itemEl);
    });


    this.wrapper.addEventListener('click', (event) => {
      this.popoverClicked(event);
    });

    return this.wrapper;
  }


  popoverClicked(event) {
    const clickedItem = event.target.closest(`.${Popover.CSS.item}`);

    if (!clickedItem) {
      return;
    }

    const clickedItemIndex = clickedItem.dataset.index;
    const item = this.items[clickedItemIndex];

    item?.onClick?.();
  }



  open() {
    this.wrapper.classList.add(Popover.CSS.popoverOpened);
  }

  close() {
    this.wrapper.classList.remove(Popover.CSS.popoverOpened);
  }
}