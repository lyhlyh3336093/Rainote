import NProgress from 'nprogress';
import { createFetch } from '@vueuse/core';
import { message } from 'ant-design-vue';
import useCookie from "./useCookie";
import { whiteList } from '../router/authenticate.js';

const cookie = useCookie
const errCodeList: any = [500];

export default createFetch({
    baseUrl: window.location.origin ===
        "http://www.rainote.cn" ?
        "http://www.rainote.cn:8089" : '/api',
    // baseUrl: window.location.origin ===
    // "http://www.rainote.cn" ?
    //     "http://www.rainote.cn:8089" : 'http://127.0.0.1:8090',
    options: {
        timeout: 50000,
        afterFetch(ctx): any {
            const { code, msg } = ctx.data;
            NProgress.done();
            if (code === 200) return ctx;
            if (code === 401) {
                window.location.href = '/signin';
                return;
            }
            if (errCodeList.includes(code)) {
                message.error(msg);
            }
            return Promise.resolve(false);
        },
        // @ts-ignore
        beforeFetch(ctx) {
            const { url, cancel, options } = ctx;
            if (options.method === 'POST') {
                const data = options.body;
                const requestObj = {
                    url: url,
                    data: typeof data === 'object' ? JSON.stringify(data) : data,
                    time: new Date().getTime()
                }
                let sessionObj: any = sessionStorage.getItem('sessionObj')
                if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
                    sessionStorage.setItem('sessionObj', JSON.stringify(requestObj))
                } else {
                    sessionObj = JSON.parse(sessionObj);
                    const s_url = sessionObj.url;                  // 请求地址
                    const s_data = sessionObj.data;                // 请求数据
                    const s_time = sessionObj.time;                // 请求时间
                    const interval = 1000;                         // 间隔时间(ms)，小于此时间视为重复提交
                    if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
                        const message = '数据正在处理，请勿重复提交';
                        console.warn(`[${s_url}]: ` + message);
                        cancel();
                        return Promise.reject(new Error(message))
                    } else {
                        sessionStorage.setItem('sessionObj', JSON.stringify(requestObj))
                    }
                }
            }

            if (!NProgress.isRendered()) {
                NProgress.start();
            }
            let token = cookie.get('token');
            let name = window.location.pathname.split('/')[1];
            if (!token && !whiteList.includes(name)) {
                cancel();
                cookie.remove('token');
                window.location.href = '/signin';
                return;
            }
            options.headers = {
                ...options.headers,
                Authorization: token
            }
            return options;
        },
        onFetchError(ctx) {
            NProgress.done();
            if (ctx.response.status === 502) {
                cookie.remove('token');
                return Promise.reject(false);
            }
            // message.error(`接口${ctx.response.url}=>${ctx.error.message}`);
            return new Promise((resolve) => {
                // @ts-ignore
                resolve(false);
            })
        },

    },
    fetchOptions: {
        mode: 'cors',
        credentials: 'include',
    },
})
