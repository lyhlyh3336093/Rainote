import {ExclamationCircleOutlined} from '@ant-design/icons-vue';
import {Modal} from 'ant-design-vue';
import {defineStore, StoreDefinition} from 'pinia';
import {createVNode} from 'vue';
import {useFetch} from '../../hooks';

export const useUserStore: StoreDefinition<"userStore", {
    name: string;
    avatar: string;
    roles: any[];
    permissions: any[];
    userInfo:any
}, {}, {
    getUserInfo(): Promise<unknown>;
    logout(): void;
}> = defineStore('userStore', {
    state: () => ({
        name: '',
        avatar: '',
        roles: [],
        permissions: [],
        userInfo: {}
    }),
    actions: {
        getUserInfo() {
            return new Promise(async resolve => {
                const {data}: any = await useFetch('getInfo').get().json();
                const res = data.value;
                if (res.roles && res.roles.length > 0) {
                    this.roles = res.roles;
                    this.permissions = res.permissions;
                } else {
                    this.roles = [];
                }
                this.name = res.userName;
                this.userInfo = res;
                resolve(data);
            })
        },
        logout() {
            Modal.confirm({
                title: '提示',
                icon: createVNode(ExclamationCircleOutlined),
                content: '确定注销并退出系统吗？                ',
                okText: '确认',
                cancelText: '取消',
                centered: true,
                async onOk() {
                    const {data} = await useFetch("/logout").get().json();
                    if (data.value) {
                        localStorage.clear();
                        window.location.href = '/signin';
                    }
                }
            });

        }
    },
    persist: {
        enabled: true,
        strategies: [
            {storage: localStorage},
        ],
    },


})