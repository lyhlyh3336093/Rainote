import { App } from "vue"

export default (app: App) => {
    app.directive('focus', {
        mounted(el:HTMLElement) {
            el?.focus();
        },

    })
}