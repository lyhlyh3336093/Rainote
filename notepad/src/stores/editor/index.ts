import { defineStore } from 'pinia';

const id: string = parent.location.pathname.split('/').at(-1);


export const useStore = defineStore('editor', {
    state: () => ({
        currentLanguage: null,
        languages: [],
        id,
        baseTable: {},
        link: {
            data: {},
        },
        config: {
            page: {
                width: '820px',
            }
        },
        code: {
            [id]: {}
        },
        editor: {
            [id]: {}
        },
        image: {
            [id]: {}
        },
        file: {
            [id]: {}
        },
        anchor: {},
        // U5: 笔记被表格引用列表缓存,按 noteId 索引。
        // onReady 时一次性拉取 GET /system/notelink/byNote/{noteId},内联角标与 ReferencePanel 共用此缓存。
        references: {} as Record<string | number, any[]>
    }),
    actions: {
        addLink(id: string = '', value: any) {
            if (!this.link.data[id]) this.link.data[id] = [];
            this.link.data[id].push(value);
        },
        updatePage(type, value) {
            this.config.page[type] = value;
        },
        setLanguages(languages) {
            this.languages = languages;
        },
        setMonaco(monaco) {
            this.monaco = monaco;
        },
        addAnchor(anchor) {
            if (!this.anchor[id]) {
                this.anchor[id] = [];
            }
            this.anchor[id].push(anchor);
        },
        removeAnchor(anchor) {
            this.anchor[id] = this.anchor[id].filter(item => item.href !== anchor);
        },
        // U5: 设置某 noteId 的引用列表缓存
        setReferences(noteId: string | number, list: any[]) {
            this.references[noteId] = list;
        },
        // U5: 读取某 noteId 的引用列表缓存(无缓存返回空数组)
        getReferences(noteId: string | number): any[] {
            return this.references[noteId] || [];
        },
        // U5: 按 data-link-id(NoteNotelink.id)判断锚点是否被引用
        isReferenced(noteId: string | number, linkId: string | number | null | undefined): boolean {
            if (linkId == null || linkId === '') return false;
            const list = this.references[noteId] || [];
            return list.some((item: any) => String(item.id) === String(linkId));
        },
        // U5: 删除某 noteId 引用列表中指定 linkId 的条目(本地同步,删除锚点时调用)
        removeReference(noteId: string | number, linkId: string | number) {
            if (!this.references[noteId]) return;
            this.references[noteId] = this.references[noteId].filter(
                (item: any) => String(item.id) !== String(linkId)
            );
        },
    },
    persist: {
        enabled: true,
        strategies: [
            {
                storage: sessionStorage, paths: [
                    'currentLanguage',
                    'languages',
                    'id',
                    'config',
                    'link',
                    'code',
                    'editor',
                    'image',
                    'file',
                    'baseTable'
                ]
            },
        ],
    },


});
