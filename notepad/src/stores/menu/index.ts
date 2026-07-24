import xeUtils from 'xe-utils';
import { defineStore } from 'pinia';
import useFetch from '../../hooks/useFetch';
import { Response } from '../../types/response.js';
import { session } from '../table';
import { NoteType } from '../../enum';

// 常量定义
const SPECIAL_ITEM_IDS = {
    TEMPLATE: -3,
    TRASH: -1,
    COLLECT: -2,
} as const;

interface MenuItem {
    id: number;
    title: string;
    parentId: number | null;
    path?: string;
    noteType: NoteType;
    children?: MenuItem[];
}

interface MenuState {
    list: MenuItem[];
    current: Partial<MenuItem>;
    folderList: MenuItem[];
    fullList: MenuItem[];
    openKeys: (string | number)[];
    selectedKeys: (string | number)[];
    noteList: MenuItem[];

}

export const useStore = defineStore('menu', {
    state: (): MenuState => ({
        list: [],
        current: {},
        folderList: [],
        fullList: [],
        openKeys: [],
        selectedKeys: [],
        noteList: [],
    }),

    actions: {
        async getMenu(title = '') {
            const { data } = await useFetch<Response>(
                `/system/note/list?pageNum=1&pageSize=10&title=${title}`
            ).get().json();

            if (!data?.value) return;

            const responseData = data.value.data;
            this.fullList = responseData;

            // 直接过滤，无需深拷贝
            this.folderList = xeUtils.toArrayTree(
                responseData.filter((item: MenuItem) => item.noteType === NoteType.文件夹),
                { key: 'id', parentKey: 'parentId' }
            );

            this.list = [
                ...xeUtils.toArrayTree(responseData, { key: 'id', parentKey: 'parentId' }),
                {
                    title: '我的模板',
                    id: SPECIAL_ITEM_IDS.TEMPLATE,
                    parentId: null,
                    path: 'template',
                    noteType: NoteType.我的模板,
                },
                {
                    title: '回收站',
                    id: SPECIAL_ITEM_IDS.TRASH,
                    parentId: null,
                    path: 'trash',
                    noteType: NoteType.回收站,
                },
                {
                    title: '收藏',
                    id: SPECIAL_ITEM_IDS.COLLECT,
                    parentId: null,
                    path: 'collect',
                    noteType: NoteType.收藏,
                },
            ];
            this.noteList = responseData.filter(item => item.noteType === NoteType.笔记);
        },
        change(data) {
            this.list = data;
        },
        clear() {
            delete session.value.datasheet;
            delete session.value.view;
        },

        setOpenKeys(openKeys: (string | number)[]) {
            this.openKeys = openKeys;
        },

        setSelectedKeys(id: string | number) {
            this.selectedKeys = [id];
        },
    },

    persist: {
        enabled: true,
        strategies: [{ storage: sessionStorage }],
    },
});
