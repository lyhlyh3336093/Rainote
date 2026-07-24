import {defineStore} from 'pinia'

export const useConfig = defineStore('config', {
    state: () => ({
        // 是否展开
        collapse: false,
        // 菜单展开宽度
        menuWidth: 280
    }),
    actions: {
        setMenuWidth(width) {
            this.menuWidth = width;
        },
        toogleCollapse() {
            this.collapse = !this.collapse;
        }
    }
});
