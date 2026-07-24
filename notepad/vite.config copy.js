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
                "bootstrap-icons"
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
                output: {
                    manualChunks: (id) => {
                        // 核心框架
                        if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) {
                            return 'vendor-core';
                        }
                        // Ant Design 相关
                        if (id.includes('ant-design-vue') || id.includes('@ant-design/icons-vue')) {
                            return 'ant-design';
                        }
                        // 编辑器相关
                        if (id.includes('@editorjs/') || id.includes('monaco-editor') || id.includes('ace-builds')) {
                            return 'editor';
                        }
                        // 图表相关
                        if (id.includes('@antv/') || id.includes('echarts') || id.includes('vue-echarts')) {
                            return 'charts';
                        }
                        // 表格相关
                        if (id.includes('vxe-table') || id.includes('@surely-vue/table')) {
                            return 'table';
                        }
                        // 日历相关
                        if (id.includes('@fullcalendar/')) {
                            return 'calendar';
                        }
                        // UI组件库
                        if (id.includes('bootstrap') || id.includes('v-viewer') || id.includes('vue-color')) {
                            return 'ui-components';
                        }
                        // 工具库
                        if (id.includes('dayjs') || id.includes('sortablejs') || id.includes('xe-utils') || 
                            id.includes('dhtmlx-gantt') || id.includes('nprogress') || id.includes('highlight.js') ||
                            id.includes('uuid') || id.includes('universal-cookie') || id.includes('websocket-heartbeat-js')) {
                            return 'utils';
                        }
                        // 布局组件
                        if (id.includes('vue-grid-layout') || id.includes('vue-waterfall')) {
                            return 'layout';
                        }
                        // 其他第三方库
                        if (id.includes('node_modules')) {
                            return 'vendor';
                        }
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
        }
    }
})
