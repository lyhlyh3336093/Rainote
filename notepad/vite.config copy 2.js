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
                "xe-utils"
            ],

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
                    manualChunks: {
                        'vendor': ['vue', 'vue-router', 'pinia'],
                        'ant-design': ['ant-design-vue', '@ant-design/icons-vue'],
                        'editor': ['@editorjs/editorjs', '@editorjs/list', '@editorjs/table', '@editorjs/underline'],
                        'utils': ['dayjs', 'sortablejs', 'xe-utils', 'dhtmlx-gantt', 'ace-builds', 'nprogress', 'sortablejs'],
                        'charts': ['@antv/g6', '@antv/hierarchy'],
                    },
                    entryFileNames: 'assets/js/[name]-[hash].js',
                    chunkFileNames: 'assets/js/[name]-[hash].js',
                    assetFileNames: 'assets/[ext]/[name]-[hash].[ext]'
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
