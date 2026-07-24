import {defineStore} from 'pinia';

export const useStore = defineStore('gant', {
    state: () => ({
        list: {},
        current: {},
    }),
    actions: {
        setCurrent(current) {
            this.current = current;
        },
        setList(list) {
            this.list = list;
        },
        add({id, table, key, item}) {
            if (!this.list[table]) this.list[table] = {};
            if (!this.list[table][id]) this.list[table][id] = {}
            if (!this.list[table][id][key]) this.list[table][id][key] = [];
            this.list[table][id][key].push(item);
        }

    }
})
