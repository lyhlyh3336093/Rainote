import {
    resolve
} from 'path';
import vue from '@vitejs/plugin-vue'
import Autoprefixer from 'autoprefixer'
import {
    defineConfig,
    loadEnv,
    splitVendorChunkPlugin
} from 'vite'
import vueJsx from '@vitejs/plugin-vue-jsx'
import AutoImport from 'unplugin-auto-import/vite'
import viteCompression from 'vite-plugin-compression'
import Components from 'unplugin-vue-components/vite'
import {
    AntDesignVueResolver
} from 'unplugin-vue-components/resolvers'
import tailwindcss from 'tailwindcss';
import {
    visualizer
} from "rollup-plugin-visualizer";
// import postcssPxToViewport from 'postcss-px-to-viewport'

export default defineConfig(({
    mode
}) => {
    const env = loadEnv(mode, './');
    return {
        plugins: [
            vue(),
            vueJsx(),
            AutoImport({
                dirs: ['src/composables', 'src/store'],
                imports: ['vue', 'vue-router', 'pinia', '@vueuse/core'],
            }),
            Components({
                resolvers: [AntDesignVueResolver({
                    importStyle: false, // css in js
                })],
                dirs: ['src/components'],
                extensions: ['vue', 'tsx'],
            }),
            viteCompression({
                algorithm: 'gzip',
                threshold: 10240,
            }),
            splitVendorChunkPlugin(),
            visualizer({
                open: true,
                filename: "stats.html",
                gzipSize: true,
                brotliSize: true,
            }),
        ],
        optimizeDeps: {
            include: [
                "vue",
                "pinia",
                "dayjs",
                "sortablejs",
                "dhtmlx-gantt",
                "@antv/g6",
                "@ant-design/icons-vue",
                "@antv/hierarchy",
                "@editorjs/editorjs",
                "@editorjs/list",
                "@editorjs/table",
                "@editorjs/underline",
                "@editorjs/header",
                "@editorjs/checklist",
                "@vitejs/plugin-vue-jsx",
                "@vue/shared",
                "@vueuse/components",
                "@vueuse/integrations",
                "ace-builds",
                "ant-design-vue",
                "autoprefixer",
                "material-colors",
                "nprogress",
                "vue-router",
                "vxe-table",
                "xe-utils",
                "echarts",
                "vue-echarts",
                "highlight.js",
                "monaco-editor",
                "@monaco-editor/loader",
                "v-viewer",
                "vue-color",
                "vue-grid-layout-v3",
                "vue-waterfall",
                "websocket-heartbeat-js",
                "universal-cookie",
                "uuid",
                "@surely-vue/table",
                "@fullcalendar/core",
                "@fullcalendar/daygrid",
                "@fullcalendar/timegrid",
                "@fullcalendar/interaction",
                "@fullcalendar/list",
                "@fullcalendar/multimonth",
                "@fullcalendar/vue3",
                "@fullcalendar/bootstrap5",
                "bootstrap",
            ],
            exclude: ['vite-plugin-cdn-import']
        },
        resolve: {
            alias: {
                "@": resolve(__dirname, './src')
            }
        },
        css: {
            scss: {
                // additionalData: `@import "@/assets/styles.mixin.scss"`
            },
            postcss: {
                plugins: [
                    // postcssPxToViewport({
                    //     // 要转化的单位
                    //     unitToConvert: 'px',
                    //     // UI设计稿的大小
                    //     viewportWidth: 1920,
                    //     // 转换后的精度
                    //     unitPrecision: 4,
                    //     // 转换后的单位
                    //     viewportUnit: 'vw',
                    //     // 字体转换后的单位
                    //     fontViewportUnit: 'vw',
                    //     // 能转换的属性，*表示所有属性，!border表示border不转
                    //     propList: ['*'],
                    //     // 指定不转换为视窗单位的类名，
                    //     selectorBlackList: ['ignore-'],
                    //     // 最小转换的值，小于等于1不转
                    //     minPixelValue: 0,
                    //     // 是否在媒体查询的css代码中也进行转换，默认false
                    //     mediaQuery: true,
                    //     // 是否转换后直接更换属性值
                    //     replace: true,
                    //     // 忽略某些文件夹下的文件或特定文件，例如 'node_modules' 下的文件
                    //     exclude: null,
                    //     // 包含那些文件或者特定文件
                    //     include: [/node_modules\/ant-design-vue/], // 明确包含 ant-design-vue
                    //     // 是否处理横屏情况
                    //     landscape: false
                    // }),
                    Autoprefixer({
                        overrideBrowserslist: [
                            'Android 4.1',
                            'iOS 7.1',
                            'Chrome > 31',
                            'ff > 31',
                            'ie >= 8',
                            '> 1%'
                        ],
                        grid: true
                    }),
                    tailwindcss()
                ]
            }
        },
        build: {
            rollupOptions: {
                external: (id) => {
                    // 排除ace-builds的worker文件，避免eval警告
                    if (id.includes('ace-builds/src-noconflict/worker-')) {
                        return true;
                    }
                    return false;
                },
                output: {
                    manualChunks: {
                        // 核心框架
                        'vendor-core': ['vue', 'vue-router', 'pinia'],
                        // Ant Design 相关
                        'ant-design': ['ant-design-vue', '@ant-design/icons-vue'],
                        // 编辑器相关
                        'editor': ['@editorjs/editorjs', '@editorjs/list', '@editorjs/table', '@editorjs/underline', '@editorjs/header', '@editorjs/checklist', 'ace-builds'],
                        // 图表相关
                        'charts': ['@antv/g6', '@antv/hierarchy', 'echarts', 'vue-echarts'],
                        // 表格相关
                        'table': ['vxe-table', '@surely-vue/table'],
                        // 日历相关
                        'calendar': ['@fullcalendar/core', '@fullcalendar/daygrid', '@fullcalendar/timegrid', '@fullcalendar/interaction', '@fullcalendar/list', '@fullcalendar/multimonth', '@fullcalendar/vue3', '@fullcalendar/bootstrap5'],
                        // UI组件库
                        'ui-components': ['bootstrap',  'v-viewer'],
                        // 工具库
                        'utils': ['dayjs', 'sortablejs', 'xe-utils', 'dhtmlx-gantt', 'nprogress', 'highlight.js', 'uuid', 'websocket-heartbeat-js'],
                        // 布局组件
                        'layout': ['vue-grid-layout-v3'],
                        // 其他第三方库
                        'vendor': ['@vueuse/core', '@vueuse/components', '@vueuse/integrations']
                    },
                    entryFileNames: 'assets/js/[name]-[hash].js',
                    chunkFileNames: 'assets/js/[name]-[hash].js',
                    assetFileNames: (assetInfo) => {
                        const info = assetInfo.name.split('.');
                        const ext = info[info.length - 1];
                        if (/\.(mp4|webm|ogg|mp3|wav|flac|aac)(\?.*)?$/i.test(assetInfo.name)) {
                            return `assets/media/[name]-[hash].${ext}`;
                        }
                        if (/\.(png|jpe?g|gif|svg)(\?.*)?$/i.test(assetInfo.name)) {
                            return `assets/images/[name]-[hash].${ext}`;
                        }
                        if (/\.(woff2?|eot|ttf|otf)(\?.*)?$/i.test(assetInfo.name)) {
                            return `assets/fonts/[name]-[hash].${ext}`;
                        }
                        return `assets/[ext]/[name]-[hash].${ext}`;
                    }
                },
            },
            reportCompressedSize: false,
            sourcemap: false,
            minify: 'terser',
            terserOptions: {
                compress: {
                    drop_console: true,
                    drop_debugger: true,
                    pure_funcs: ['console.log', 'console.info'],
                    passes: 2,
                    // 避免eval相关的问题
                    unsafe: false,
                    unsafe_comps: false,
                    unsafe_Function: false,
                    unsafe_math: false,
                    unsafe_proto: false,
                    unsafe_regexp: false,
                    unsafe_undefined: false,
                },
                mangle: {
                    // 避免mangle导致的问题
                    keep_fnames: true,
                }
            },
            chunkSizeWarningLimit: 2000,
            target: 'es2015',
            cssCodeSplit: true,
            assetsInlineLimit: 4096,
        },
        server: {
            cors: true,
            port: 5173,
            open: true,
            proxy: {
                [env.VITE_PROXY_URL]: {
                    changeOrigin: true,
                    target: env.VITE_PROXY_TARGET,
                    rewrite: path => path.replace(/^\/api/, '')
                }
            }
        },
        // 处理ace-builds的静态资源
        assetsInclude: ['**/*.worker.js'],
    }
})
