import App from './App.vue'
import Router from './router'
import {createApp} from 'vue'
import VXETable from 'vxe-table'
import 'vxe-table/lib/style.css'
import 'nprogress/nprogress.css'
import stores from './stores'
import NProgress from 'nprogress'
import 'ant-design-vue/dist/reset.css';
import withInstall from './components/index'
import Directives from './directives';
import './main.css';
import '@surely-vue/table/dist/index.min.css';
import "./assets/fonts/iconfont.css";
import 'bootstrap/dist/css/bootstrap.css';
import 'bootstrap-icons/font/bootstrap-icons.css';
import './assets/styles/index.scss'
import './assets/styles/common.scss'
import { GridLayout, GridItem } from 'vue-grid-layout-v3'
import {VxeLoading} from 'vxe-pc-ui'
import 'vxe-pc-ui/es/style.css'

NProgress.configure({showSpinner: false,});

function useTable(app) {
    VXETable.setup({
        table: {
            border: true,
        },
    })
    app.use(VXETable)
    app.config.globalProperties.$XModal = VXETable.modal
    app.config.globalProperties.$XPrint = VXETable.print
    app.config.globalProperties.$XSaveFile = VXETable.saveFile
    app.config.globalProperties.$XReadFile = VXETable.readFile
}

const app = createApp(App);
withInstall(app);
Directives(app);
app.use(VxeLoading)
app.component('GridLayout', GridLayout);
app.component('GridItem', GridItem);

app
    .use(Router)
    .use(stores)
    .use(useTable)
    .mount('#app');

