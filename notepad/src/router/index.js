import {
    createWebHistory,
    createRouter
} from 'vue-router';
import auth from './authenticate';

export const health=[
    {
        name: 'health-user-info',
        path: '/health/user-info',
        meta: {
            title: '用户信息管理'
        },
        component: () => import('@/views/health/user-info/index.vue')
    },
    {
        name: 'health-examination',
        path: '/health/examination',
        meta: {
            title: '体检项目管理'
        },
        component: () => import('@/views/health/examination/index.vue')
    },
    {
        name: 'health-diet',
        path: '/health/diet',
        meta: {
            title: '饮食记录管理'
        },
        component: () => import('@/views/health/diet/index.vue')
    },
    {
        name: 'health-equipment',
        path: '/health/equipment',
        meta: {
            title: '设备管理'
        },
        component: () => import('@/views/health/equipment/index.vue')
    },
    {
        name: 'health-department',
        path: '/health/department',
        meta: {
            title: '科室管理'
        },
        component: () => import('@/views/health/department/index.vue')
    },
    {
        name: 'health-personnel',
        path: '/health/personnel',
        meta: {
            title: '检测人员管理'
        },
        component: () => import('@/views/health/personnel/index.vue')
    },
    {
        name: 'health-process',
        path: '/health/process',
        meta: {
            title: '检测流程管理'
        },
        component: () => import('@/views/health/process/index.vue')
    }
]
const routes = [{
        path: '/',
        redirect: '/home',
        component: () => import('@/layout/index.vue'),
        children: [{
                name: 'home',
                path: '/home',
                meta: {
                    title: '主页',
                },
                component: () => import('@/views/home/index.vue'),
            },
            {
                name: 'me',
                path: '/me',
                meta: {
                    title: '我的空间',
                },
                component: () => import('@/views/me/index.vue'),
            },
            {
                name: 'shared',
                path: '/shared',
                meta: {
                    title: '共享空间',
                },
                component: () => import('@/views/shared/index.vue'),
            }
        ],
    },
    {
        path: '/folder',
        component: () => import('@/layout/index.vue'),
        children: [{
            name: 'folder',
            path: '/folder/:id?',
            meta: {
                title: '文档列表',
            },
            component: () => import('@/views/folder/index.vue'),
        }],
    },

    {
        path: '/docx',
        meta: {
            title: '文档'
        },
        children: [{
            name: 'docx',
            path: '/docx/:id?/:blockId?',
            meta: {
                title: '文档',
            },
            component: () => import('@/views/doc/index.vue'),
        }]
    },
    {
        name: 'mindmap',
        path: '/mindmap',
        meta: {
            title: '思维导图',
        },
        component: () => import('@/views/mindmap/index.vue'),
    },
    {
        path: '/sheets',
        children: [{
                name: 'sheets',
                path: '/sheets/:id?',
                meta: {
                    title: '表格',
                },
                component: () => import('@/views/sheets/index.vue'),
            },

        ]
    },
    {
        name: 'base',
        path: '/base/:id?/:tableId?',
        meta: {
            title: '多维表格',
        },
        component: () => import('@/views/base/index.vue'),
    },
    {
        name: 'code',
        path: '/code/:blockId',
        meta: {
            title: '代码块',
        },
        component: () => import('@/views/code/index.vue'),
    },
    {
        name: 'image',
        path: '/image/:id',
        meta: {
            title: '代码块',
        },
        component: () => import('@/views/image/index.vue'),
    },
    {
        path: '/collect',
        redirect: 'collect',
        component: () => import('@/layout/index.vue'),
        children: [{
            name: 'collect',
            path: '/collect',
            meta: {
                title: '收藏'
            },
            component: () => import('@/views/collect/index.vue')
        }],
    },
    {
        path: '/trash',
        redirect: 'trash',
        component: () => import('@/layout/index.vue'),
        children: [{
            name: 'trash',
            path: '/trash',
            meta: {
                title: '回收站'
            },
            component: () => import('@/views/trash/index.vue')
        }]
    },
    {
        path: '/template',
        redirect: 'template',
        component: () => import('@/layout/index.vue'),
        children: [{
            name: 'template',
            path: '/template',
            meta: {
                title: '我的模板'
            },
            component: () => import('@/views/template/index.vue')
        }]
    },

    {
        name: 'signin',
        path: '/signin',
        meta: {
            title: '登录'
        },
        component: () => import('@/views/signin/index.vue')
    },
    {
        name: 'signup',
        path: '/signup',
        meta: {
            title: '注册'
        },
        component: () => import('@/views/signup/index.vue')
    },
    {
        name: 'chat',
        path: '/chat',
        meta: {
            title: '聊天室'
        },
        component: () => import('@/views/chat/index.vue')
    },
    {
        name:'application',
        path:'/application',
        meta: {
            title: '首页'
        },
        component:()=>import('@/views/application/index.vue')
    },

    // {
    //     path: '/health',
    //     redirect: '/health/user-info',
    //     component: () => import('@/views/health//index.vue'),
    //     meta: {
    //         title: '健康管理'
    //     },
    //     children: health
    // },
    {
        path: '/:pathMatch(.*)',
        component: () => import('@/views/error/index.vue')
    }
];
const root = createRouter({
    history: createWebHistory(),
    routes,
});
root.beforeEach((to, from, next) => {
    document.title = to?.meta?.title || '';
    auth(to, next);
});
export default root;
