import useCookie from "@/hooks/useCookie";

const cookie = useCookie;
// 允许通过的白名单列表
export const whiteList = ['signin', 'signup', 'system', 'system.user','system.role','system.menu',];
// 路由鉴权
export default (route, next) => {
    if (whiteList.includes(route.name)) {
        next();
    } else {
        const token = cookie.get('token');
        if (!token) {
            window.location.href = '/signin';
        } else {
            next();
        }
    }
}
