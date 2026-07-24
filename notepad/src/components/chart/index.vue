<template>
    <div class="chart-dashboard">
        <!-- 顶部工具栏 -->
        <div class="dashboard-header">
            <div class="header-content">

                <div class="header-actions">
                    <a-button type="primary" @click="showAddChartModal = true" class="action-btn primary">
                        <plus-outlined />
                        添加图表
                    </a-button>
                    <a-button @click="saveLayout" class="action-btn">
                        <save-outlined />
                        保存布局
                    </a-button>
                </div>
            </div>
        </div>

        <!-- 图表网格布局 -->
        <VueGridLayout :layout="layout" :col-num="12" :row-height="35" :is-draggable="true" :is-resizable="true"
            :is-mirrored="false" :vertical-compact="true" :margin="[10, 10]" :use-css-transforms="true"
            @layout-updated="onLayoutUpdated">
            <template #default="{ item }">
                <div class="chart-widget"
                    :class="[`chart-${getChartById(item.i)?.type}`, { selected: activeChartId === item.i }]"
                    @click="setActiveChart(item.i)">
                    <div class="chart-widget-header">
                        <div class="chart-title">
                            <h3>{{ getChartById(item.i)?.title || '未命名图表' }}</h3>
                        </div>
                        <div class="chart-widget-actions">
                            <a-button type="text" size="small" @click="editChart(getChartById(item.i))">
                                <setting-outlined />
                            </a-button>
                            <a-button type="text" size="small" @click="removeChart(item.i)">
                                <close-outlined />
                            </a-button>
                        </div>
                    </div>
                    <div class="chart-widget-content">
                        <v-chart 
                            :option="getChartOption(getChartById(item.i))"
                            :style="{ width: '100%', height: '100%' }" 
                            autoresize 
                            :key="`chart-${item.i}-${getChartById(item.i)?.layout?.w || 0}-${getChartById(item.i)?.layout?.h || 0}`"
                        />
                    </div>
                    <div class="chart-widget-footer">
                        <span>基于{{ recordCount }}条记录·{{ getChartById(item.i)?.aggregation || '计数' }}</span>
                    </div>
                </div>
            </template>
        </VueGridLayout>

        <!-- 添加图表模态框 -->
        <a-modal v-model:open="showAddChartModal" title="添加图表" :footer="null" width="500px"
            @cancel="showAddChartModal = false">
            <div class="add-chart-form">
                <div class="form-item">
                    <label>图表标题</label>
                    <a-input v-model:value="newChart.title" placeholder="输入图表标题" />
                </div>

                <div class="form-item">
                    <label>图表类型</label>
                    <a-select v-model:value="newChart.type" placeholder="选择图表类型" style="width: 100%">
                        <a-select-option value="bar">柱状图</a-select-option>
                        <a-select-option value="line">折线图</a-select-option>
                        <a-select-option value="pie">饼图</a-select-option>
                        <a-select-option value="area">面积图</a-select-option>
                    </a-select>
                </div>

                <div class="form-item">
                    <label>数据字段</label>
                    <a-select v-model:value="newChart.dataField" placeholder="选择数据字段" style="width: 100%">
                        <a-select-option v-for="column in availableColumns" :key="column.id" :value="column.id">
                            {{ column.name }}
                        </a-select-option>
                    </a-select>
                </div>

                <div class="form-item">
                    <label>聚合方式</label>
                    <a-select v-model:value="newChart.aggregation" placeholder="选择聚合方式" style="width: 100%">
                        <a-select-option value="count">计数</a-select-option>
                        <a-select-option value="sum">求和</a-select-option>
                        <a-select-option value="avg">平均值</a-select-option>
                        <a-select-option value="max">最大值</a-select-option>
                        <a-select-option value="min">最小值</a-select-option>
                    </a-select>
                </div>

                <div class="form-item">
                    <label>图表颜色</label>
                    <div class="color-picker-wrapper">
                        <input type="color" v-model="newChart.color" class="color-input" />
                        <a-input v-model:value="newChart.color" placeholder="#8884d8" style="width: 120px"
                            @input="updateColorFromInput('newChart')" />
                    </div>
                </div>

                <div class="form-actions">
                    <a-button @click="showAddChartModal = false">取消</a-button>
                    <a-button type="primary" @click="addChart">保存</a-button>
                </div>
            </div>
        </a-modal>


        <!-- 编辑图表模态框 -->
        <a-modal v-model:open="showEditChartModal" title="编辑图表" :footer="null" width="600px"
            @cancel="showEditChartModal = false">
            <div class="edit-chart-form">
                <div class="form-item">
                    <label>图表标题</label>
                    <a-input v-model:value="editingChartData.title" placeholder="输入图表标题" />
                </div>

                <div class="form-item">
                    <label>图表类型</label>
                    <a-select v-model:value="editingChartData.type" placeholder="选择图表类型" style="width: 100%">
                        <a-select-option value="bar">柱状图</a-select-option>
                        <a-select-option value="line">折线图</a-select-option>
                        <a-select-option value="pie">饼图</a-select-option>
                        <a-select-option value="area">面积图</a-select-option>
                    </a-select>
                </div>

                <div class="form-item">
                    <label>数据字段</label>
                    <a-select v-model:value="editingChartData.dataField" placeholder="选择数据字段" style="width: 100%">
                        <a-select-option v-for="column in availableColumns" :key="column.id" :value="column.id">
                            {{ column.name }}
                        </a-select-option>
                    </a-select>
                </div>

                <div class="form-item">
                    <label>聚合方式</label>
                    <a-select v-model:value="editingChartData.aggregation" placeholder="选择聚合方式" style="width: 100%">
                        <a-select-option value="count">计数</a-select-option>
                        <a-select-option value="sum">求和</a-select-option>
                        <a-select-option value="avg">平均值</a-select-option>
                        <a-select-option value="max">最大值</a-select-option>
                        <a-select-option value="min">最小值</a-select-option>
                    </a-select>
                </div>

                <div class="form-item">
                    <label>图表颜色</label>
                    <div class="color-picker-wrapper">
                        <input type="color" v-model="editingChartData.color" class="color-input" />
                        <a-input v-model:value="editingChartData.color" placeholder="#1890ff" style="width: 120px"
                            @input="updateColorFromInput('editingChartData')" />
                    </div>
                </div>

                <div class="form-item">
                    <label>图表尺寸</label>
                    <div class="size-inputs">
                        <a-input-number v-model:value="editingChartData.size.width" placeholder="宽度" :min="200"
                            :max="800" style="width: 120px" />
                        <span class="size-separator">×</span>
                        <a-input-number v-model:value="editingChartData.size.height" placeholder="高度" :min="200"
                            :max="600" style="width: 120px" />
                    </div>
                </div>

                <div class="form-actions">
                    <a-button @click="showEditChartModal = false">取消</a-button>
                    <a-button type="primary" @click="saveEditedChart">保存</a-button>
                </div>
            </div>
        </a-modal>
    </div>
</template>

<script lang="tsx">
import { defineComponent, reactive, ref, computed, watch, onMounted, inject, nextTick, defineAsyncComponent } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, LineChart, PieChart, ScatterChart, RadarChart } from 'echarts/charts';
import {
    TitleComponent,
    TooltipComponent,
    LegendComponent,
    GridComponent,
    DataZoomComponent,
    ToolboxComponent
} from 'echarts/components';
import VChart from 'vue-echarts';
import { storeToRefs } from 'pinia';
import { useStore } from '../../stores/table';
import { message } from 'ant-design-vue';
import {
    CloseOutlined,
    SettingOutlined,
    ReloadOutlined,
    DownloadOutlined,
    PlusOutlined,
    ThunderboltOutlined,
    SaveOutlined
} from '@ant-design/icons-vue';
import VueGridLayout from '../VueGridLayout/index.vue';

// 注册 ECharts 组件
use([
    CanvasRenderer,
    BarChart,
    LineChart,
    PieChart,
    ScatterChart,
    RadarChart,
    TitleComponent,
    TooltipComponent,
    LegendComponent,
    GridComponent,
    DataZoomComponent,
    ToolboxComponent
]);

export default defineComponent({
    name: 'ChartView',
    components: {
        VChart,
        VueGridLayout,
        CloseOutlined,
        SettingOutlined,
        ReloadOutlined,
        DownloadOutlined,
        PlusOutlined,
        ThunderboltOutlined,
        SaveOutlined
    },
    props: {
        height: {
            type: Number,
            default: 750
        }
    },
    setup(props) {
        const store = useStore();
        const { columns, table: tableData } = storeToRefs(store);
        // Vue Grid Layout 相关
        const layout = ref([]);
        const showAddChartModal = ref(false);


        // 显示编辑图表模态框
        const showEditChartModal = ref(false);
        const editingChartData = reactive({
            id: '',
            title: '',
            type: 'bar',
            dataField: '',
            aggregation: 'count',
            color: '#1890ff',
            size: { width: 300, height: 300 }
        });

        // 当前激活的图表ID
        const activeChartId = ref(null);
        
        // 图表容器准备状态
        const chartContainerReady = ref({});

        // 设置激活的图表
        const setActiveChart = (chartId) => {
            activeChartId.value = activeChartId.value === chartId ? null : chartId;
        };

        // 从输入框更新颜色
        const updateColorFromInput = (target) => {
            // 确保颜色值以 # 开头
            if (target === 'newChart') {
                if (!newChart.color.startsWith('#')) {
                    newChart.color = '#' + newChart.color;
                }
            } else if (target === 'editingChartData') {
                if (!editingChartData.color.startsWith('#')) {
                    editingChartData.color = '#' + editingChartData.color;
                }
            }
        };


        // 图表列表
        const charts = ref([
            {
                id: '1',
                title: '总记录数',
                type: 'bar',
                dataField: 'name',
                aggregation: 'count',
                color: '#8884d8',
                size: { width: 280, height: 280 },
                layout: { x: 0, y: 0, w: 4, h: 8 }
            },
            {
                id: '2',
                title: '进展分布',
                type: 'pie',
                dataField: 'status',
                aggregation: 'count',
                color: '#82ca9d',
                size: { width: 280, height: 280 },
                layout: { x: 4, y: 0, w: 4, h: 8 }
            },
            {
                id: '3',
                title: '时间趋势',
                type: 'line',
                dataField: 'date',
                aggregation: 'count',
                color: '#8884d8',
                size: { width: 280, height: 280 },
                layout: { x: 8, y: 0, w: 4, h: 8 }
            }
        ]);

        // 根据 ID 获取图表数据
        const getChartById = (id) => {
            return charts.value.find(chart => chart.id === id);
        };

        // Vue Grid Layout 事件处理
        const onLayoutUpdated = (newLayout) => {
            layout.value = newLayout;
            // 更新图表的位置信息
            newLayout.forEach(item => {
                const chart = charts.value.find(c => c.id === item.i);
                if (chart) {
                    chart.layout = {
                        x: item.x,
                        y: item.y,
                        w: item.w,
                        h: item.h
                    };
                }
            });
        };

        // 调整大小状态
        const resizeState = ref({
            isResizing: false,
            startX: 0,
            startY: 0,
            startWidth: 0,
            startHeight: 0,
            chart: null
        });

        // 新图表配置
        const newChart = reactive({
            title: '',
            type: 'bar',
            dataField: '',
            aggregation: 'count',
            color: '#8884d8'
        });

        // 记录数量
        const recordCount = computed(() => {
            return tableData.value?.tableData?.length || 0;
        });

        // 可用字段（所有字段）
        const availableColumns = computed(() => {
            return columns.value || [];
        });

        // 获取字段名称
        const getFieldName = (fieldId) => {
            const field = availableColumns.value.find(col => col.id === fieldId);
            return field ? field.name : '未知字段';
        };

        // 处理图表数据
        const processChartData = (chart) => {
            if (!tableData.value?.tableData || !chart.dataField) {
                return { categories: [], values: [], pieData: [] };
            }

            const data = tableData.value.tableData;
            const field = chart.dataField;
            const aggregation = chart.aggregation || 'count';

            if (chart.type === 'pie') {
                // 饼图数据处理
                const valueMap = new Map();

                data.forEach(row => {
                    const name = row[field] || getFieldName(field);
                    const value = aggregation === 'count' ? 1 : (parseFloat(row[field]) || 0);

                    if (valueMap.has(name)) {
                        valueMap.set(name, valueMap.get(name) + value);
                    } else {
                        valueMap.set(name, value);
                    }
                });

                const pieData = [];
                valueMap.forEach((value, name) => {
                    pieData.push({ name, value });
                });

                return { pieData };
            } else {
                // 柱状图、折线图等数据处理
                const valueMap = new Map();

                data.forEach(row => {
                    const category = row[field] || getFieldName(field);
                    let value = 0;

                    if (aggregation === 'count') {
                        value = 1;
                    } else if (aggregation === 'sum') {
                        value = parseFloat(row[field]) || 0;
                    } else if (aggregation === 'avg') {
                        value = parseFloat(row[field]) || 0;
                    } else if (aggregation === 'max') {
                        value = parseFloat(row[field]) || 0;
                    } else if (aggregation === 'min') {
                        value = parseFloat(row[field]) || 0;
                    }

                    if (valueMap.has(category)) {
                        if (aggregation === 'count' || aggregation === 'sum') {
                            valueMap.set(category, valueMap.get(category) + value);
                        } else if (aggregation === 'max') {
                            valueMap.set(category, Math.max(valueMap.get(category), value));
                        } else if (aggregation === 'min') {
                            valueMap.set(category, Math.min(valueMap.get(category), value));
                        }
                    } else {
                        valueMap.set(category, value);
                    }
                });

                // 处理平均值
                if (aggregation === 'avg') {
                    const countMap = new Map();
                    data.forEach(row => {
                        const category = row[field] || getFieldName(field);
                        countMap.set(category, (countMap.get(category) || 0) + 1);
                    });

                    valueMap.forEach((sum, category) => {
                        const count = countMap.get(category) || 1;
                        valueMap.set(category, sum / count);
                    });
                }

                const categories = [];
                const values = [];
                valueMap.forEach((value, category) => {
                    categories.push(category);
                    values.push(value);
                });

                return { categories, values };
            }
        };

        // 生成图表配置
        const getChartOption = (chart) => {
            const data = processChartData(chart);
            const colors = [chart.color, '#82ca9d', '#ffc658', '#ff7300', '#8884d8'];

            const baseOption = {
                color: colors,
                tooltip: {
                    trigger: 'item',
                    formatter: function (params) {
                        if (chart.type === 'pie') {
                            return `${params.name}: ${params.value} (${params.percent}%)`;
                        }
                        return `${params.name}: ${params.value}`;
                    }
                },
                legend: {
                    show: false
                }
            };

            switch (chart.type) {
                case 'bar':
                    return {
                        ...baseOption,
                        grid: {
                            left: '10%',
                            right: '10%',
                            bottom: '10%',
                            top: '10%',
                            containLabel: true,
                            backgroundColor: '#fff'
                        },
                        xAxis: {
                            type: 'category',
                            data: data.categories,
                            axisLine: {
                                show: false
                            },
                            axisTick: {
                                show: false
                            },
                            axisLabel: {
                                rotate: data.categories.length > 10 ? 45 : 0,
                                fontSize: 10,
                                color: '#6c757d'
                            }
                        },
                        yAxis: {
                            type: 'value',
                            axisLine: {
                                show: false
                            },
                            axisTick: {
                                show: false
                            },
                            axisLabel: {
                                fontSize: 10,
                                color: '#6c757d'
                            },
                            splitLine: {
                                lineStyle: {
                                    color: '#e9ecef',
                                    type: 'solid'
                                }
                            }
                        },
                        series: [{
                            name: chart.title,
                            type: 'bar',
                            data: data.values,
                            itemStyle: {
                                borderRadius: [4, 4, 0, 0],
                                color: '#1890ff'
                            },
                            barWidth: '60%'
                        }]
                    };

                case 'line':
                    return {
                        ...baseOption,
                        grid: {
                            left: '3%',
                            right: '4%',
                            bottom: '3%',
                            containLabel: true
                        },
                        xAxis: {
                            type: 'category',
                            data: data.categories,
                            axisLabel: {
                                rotate: data.categories.length > 10 ? 45 : 0,
                                fontSize: 10
                            }
                        },
                        yAxis: {
                            type: 'value'
                        },
                        series: [{
                            name: chart.title,
                            type: 'line',
                            data: data.values,
                            smooth: true,
                            lineStyle: {
                                color: chart.color
                            },
                            itemStyle: {
                                color: chart.color
                            }
                        }]
                    };

                case 'area':
                    return {
                        ...baseOption,
                        grid: {
                            left: '3%',
                            right: '4%',
                            bottom: '3%',
                            containLabel: true
                        },
                        xAxis: {
                            type: 'category',
                            data: data.categories,
                            axisLabel: {
                                rotate: data.categories.length > 10 ? 45 : 0,
                                fontSize: 10
                            }
                        },
                        yAxis: {
                            type: 'value'
                        },
                        series: [{
                            name: chart.title,
                            type: 'line',
                            data: data.values,
                            smooth: true,
                            areaStyle: {
                                opacity: 0.6,
                                color: chart.color
                            },
                            lineStyle: {
                                color: chart.color
                            },
                            itemStyle: {
                                color: chart.color
                            }
                        }]
                    };

                case 'pie':
                    return {
                        ...baseOption,
                        series: [{
                            name: chart.title,
                            type: 'pie',
                            radius: ['40%', '70%'],
                            data: data.pieData,
                            emphasis: {
                                itemStyle: {
                                    shadowBlur: 10,
                                    shadowOffsetX: 0,
                                    shadowColor: 'rgba(0, 0, 0, 0.5)'
                                }
                            }
                        }]
                    };

                default:
                    return baseOption;
            }
        };

        // 添加图表
        const addChart = () => {
            if (!newChart.title || !newChart.dataField) {
                message.warning('请填写图表标题和选择数据字段');
                return;
            }

            const newId = Date.now().toString();

            // 计算新图表的位置（放在下一行）
            const maxY = Math.max(...charts.value.map(c => c.layout.y), -1);
            const nextY = maxY + 10;
            const nextX = (charts.value.length % 3) * 4; // 每行3个，每个占4列

            const newChartItem = {
                id: newId,
                title: newChart.title,
                type: newChart.type,
                dataField: newChart.dataField,
                aggregation: newChart.aggregation,
                color: newChart.color,
                size: { width: 280, height: 280 },
                layout: { x: nextX, y: nextY, w: 4, h: 8 }
            };

            charts.value.push(newChartItem);

            // 更新布局数据
            layout.value = charts.value.map(chart => ({
                x: chart.layout.x,
                y: chart.layout.y,
                w: chart.layout.w,
                h: chart.layout.h,
                i: chart.id
            }));

            // 重置表单
            Object.assign(newChart, {
                title: '',
                type: 'bar',
                dataField: '',
                aggregation: 'count',
                color: '#8884d8'
            });

            showAddChartModal.value = false;
            message.success('图表添加成功');
        };


        // 删除图表
        const removeChart = (chartId) => {
            const index = charts.value.findIndex(chart => chart.id === chartId);
            if (index > -1) {
                charts.value.splice(index, 1);

                // 更新布局数据
                layout.value = charts.value.map(chart => ({
                    x: chart.layout.x,
                    y: chart.layout.y,
                    w: chart.layout.w,
                    h: chart.layout.h,
                    i: chart.id
                }));

                message.success('图表删除成功');
            }
        };

        // 智能生成图表
        const autoGenerateCharts = () => {
            if (availableColumns.value.length === 0) {
                message.warning('没有可用的数据字段');
                return;
            }

            // 清空现有图表
            charts.value = [];

            // 自动生成基础图表
            const autoCharts = [
                {
                    id: 'auto-1',
                    title: '总记录数',
                    type: 'bar',
                    dataField: availableColumns.value[0]?.id || '',
                    aggregation: 'count',
                    color: '#8884d8',
                    size: { width: 300, height: 300 }
                },
                {
                    id: 'auto-2',
                    title: '数据分布',
                    type: 'pie',
                    dataField: availableColumns.value[0]?.id || '',
                    aggregation: 'count',
                    color: '#82ca9d',
                    size: { width: 300, height: 300 }
                }
            ];

            // 如果有多个字段，添加更多图表
            if (availableColumns.value.length > 1) {
                autoCharts.push({
                    id: 'auto-3',
                    title: '趋势分析',
                    type: 'line',
                    dataField: availableColumns.value[1]?.id || '',
                    aggregation: 'count',
                    color: '#ffc658',
                    size: { width: 300, height: 300 }
                });
            }

            charts.value = autoCharts;
            message.success('智能生成图表完成');
        };

        // 保存布局
        const saveLayout = () => {
            // 这里可以实现保存布局的功能
            message.success('布局保存成功');
        };


        // 编辑图表
        const editChart = (chart) => {
            // 复制图表数据到编辑表单
            Object.assign(editingChartData, {
                id: chart.id,
                title: chart.title,
                type: chart.type,
                dataField: chart.dataField,
                aggregation: chart.aggregation,
                color: chart.color,
                size: { ...chart.size }
            });
            showEditChartModal.value = true;
        };

        // 保存编辑的图表
        const saveEditedChart = () => {
            if (!editingChartData.title || !editingChartData.dataField) {
                message.warning('请填写图表标题和选择数据字段');
                return;
            }

            // 找到要编辑的图表
            const chartIndex = charts.value.findIndex(chart => chart.id === editingChartData.id);
            if (chartIndex > -1) {
                // 更新图表数据
                Object.assign(charts.value[chartIndex], {
                    title: editingChartData.title,
                    type: editingChartData.type,
                    dataField: editingChartData.dataField,
                    aggregation: editingChartData.aggregation,
                    color: editingChartData.color,
                    size: { ...editingChartData.size }
                });

                message.success('图表更新成功');
                showEditChartModal.value = false;
            }
        };



        // 开始调整大小
        const startResize = (event, chart) => {
            event.preventDefault();
            event.stopPropagation();
            event.stopImmediatePropagation();

            const clientX = event.type === 'mousedown' ? event.clientX : event.touches[0].clientX;
            const clientY = event.type === 'mousedown' ? event.clientY : event.touches[0].clientY;

            resizeState.value = {
                isResizing: true,
                startX: clientX,
                startY: clientY,
                startWidth: chart.size.width,
                startHeight: chart.size.height,
                chart: chart
            };

            // 添加全局事件监听
            document.addEventListener('mousemove', handleResize, { passive: false });
            document.addEventListener('mouseup', endResize, { passive: false });
            document.addEventListener('touchmove', handleResize, { passive: false });
            document.addEventListener('touchend', endResize, { passive: false });
        };

        // 处理调整大小
        const handleResize = (event) => {
            if (!resizeState.value.isResizing) return;

            event.preventDefault();

            const clientX = event.type === 'mousemove' ? event.clientX : event.touches[0].clientX;
            const clientY = event.type === 'mousemove' ? event.clientY : event.touches[0].clientY;

            const deltaX = clientX - resizeState.value.startX;
            const deltaY = clientY - resizeState.value.startY;

            const newWidth = Math.max(200, resizeState.value.startWidth + deltaX);
            const newHeight = Math.max(200, resizeState.value.startHeight + deltaY);

            resizeState.value.chart.size.width = newWidth;
            resizeState.value.chart.size.height = newHeight;
        };

        // 结束调整大小
        const endResize = () => {
            if (resizeState.value.isResizing) {
                resizeState.value.isResizing = false;
                message.success('图表大小已调整');
            }

            // 移除全局事件监听
            document.removeEventListener('mousemove', handleResize);
            document.removeEventListener('mouseup', endResize);
            document.removeEventListener('touchmove', handleResize);
            document.removeEventListener('touchend', endResize);
        };


        onMounted(() => {
            // 初始化时自动生成图表
            if (charts.value.length === 0 && availableColumns.value.length > 0) {
                autoGenerateCharts();
            }

            // 初始化布局数据
            layout.value = charts.value.map(chart => ({
                x: chart.layout.x,
                y: chart.layout.y,
                w: chart.layout.w,
                h: chart.layout.h,
                i: chart.id
            }));
        });

        return {
            layout,
            showAddChartModal,
            showEditChartModal,
            editingChartData,
            activeChartId,
            setActiveChart,
            updateColorFromInput,
            charts,
            newChart,
            recordCount,
            availableColumns,
            getFieldName,
            getChartOption,
            getChartById,
            addChart,
            editChart,
            saveEditedChart,
            removeChart,
            autoGenerateCharts,
            saveLayout,
            onLayoutUpdated,
            startResize
        };
    }
});
</script>

<style lang="scss" scoped>
.chart-dashboard {
    height: 100%;
    overflow: auto;
}

.dashboard-header {
    margin-bottom: 10px;
    overflow: hidden;

    .header-content {
        display: flex;
        justify-content: space-between;
        align-items: center;
    }

    .dashboard-title {
        display: flex;
        align-items: center;
        gap: 16px;

        .title-icon {
            width: 48px;
            height: 48px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            border-radius: 12px;
            display: flex;
            align-items: center;
            justify-content: center;
            color: white;
            box-shadow: 0 4px 16px rgba(102, 126, 234, 0.3);
        }

        .title-text {
            h1 {
                margin: 0;
                font-size: 28px;
                font-weight: 700;
                color: #1a1a1a;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                -webkit-background-clip: text;
                -webkit-text-fill-color: transparent;
                background-clip: text;
            }

            .record-count {
                display: block;
                font-size: 14px;
                color: #666;
                margin-top: 4px;
                font-weight: 500;
            }
        }
    }

    .header-actions {
        display: flex;
        gap: 12px;

        .action-btn {
            height: 40px;
            border-radius: 8px;
            font-weight: 500;
            transition: all 0.5s ease;
            border: 1px solid #e8e8e8;

        }
    }
}


:deep(.vue-resizable-handle) {
    background: linear-gradient(135deg, #1890ff, #40a9ff) !important;
    border-radius: 50% !important;
    box-shadow: 0 2px 8px rgba(24, 144, 255, 0.3) !important;
    transition: all 0.3s ease !important;
    opacity: 0;
}

/* Vue Grid Layout 拖拽占位符样式 */
:deep(.vue-grid-placeholder) {
    background: linear-gradient(135deg, rgba(24, 144, 255, 0.1), rgba(64, 169, 255, 0.1)) !important;
    border: 2px dashed #1890ff !important;
    border-radius: 8px !important;
    opacity: 0.8 !important;
    transition: all 0.3s ease !important;
}

.chart-widget {
    background: #f8f9fa;
    border-radius: 8px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    position: relative;
    cursor: move;
    user-select: none;
    width: 100%;
    height: 100%;
    border: 2px solid #e9ecef;
    transition: all 0.3s ease;
    transition-property: transform;

    &:hover {
        border-color: rgba(24, 144, 255, 0.5);
        opacity: 0.8;

     
    }

    &.selected {
        border-color: #1890ff;

        
    }


    .chart-widget-header {
        cursor: move;
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 16px 20px;
        border-bottom: 1px solid #e9ecef;
        background: #fff;
        position: relative;
        transition: all 0.5s ease;

        .chart-title {
            display: flex;
            align-items: center;
            gap: 8px;
            flex: 1;

            h3 {
                margin: 0;
                font-size: 14px;
                font-weight: 500;
                color: #495057;
                transition: color 0.2s;
            }

            .edit-title-btn {
                opacity: 0;
                transition: opacity 0.2s;
            }

            &:hover .edit-title-btn {
                opacity: 1;
            }
        }

        .chart-widget-actions {
            display: flex;
            gap: 4px;
        }


    }


    .chart-widget-content {
        flex: 1;
        padding: 20px;
        min-height: 200px;
        background: #fff;
        transition: all 0.5s ease;
    }

    .chart-widget-footer {
        padding: 12px 20px;
        background: #f8f9fa;
        border-top: 1px solid #e9ecef;
        font-size: 12px;
        color: #6c757d;
        transition: all 0.5s ease;
    }

}

// 图表类型特殊样式
.chart-pie {
    .chart-widget-content {
        display: flex;
        align-items: center;
        justify-content: center;
    }

}

.chart-bar {
    .chart-widget-content {
        padding: 20px 16px;
    }
}

.chart-line {
    .chart-widget-content {
        padding: 20px 16px;
    }
}

.chart-area {
    .chart-widget-content {
        padding: 20px 16px;
    }
}

// 添加图表表单样式
.add-chart-form,
.edit-chart-form {
    .form-item {
        margin-bottom: 20px;

        label {
            display: block;
            margin-bottom: 8px;
            font-weight: 500;
            color: #262626;
        }
    }

    .color-picker-wrapper {
        display: flex;
        align-items: center;
        gap: 8px;

        .color-input {
            width: 32px;
            height: 32px;
            border: 1px solid #d9d9d9;
            border-radius: 4px;
            cursor: pointer;
            padding: 0;
            background: none;
            flex-shrink: 0;

            &::-webkit-color-swatch-wrapper {
                padding: 0;
                border: none;
                border-radius: 4px;
            }

            &::-webkit-color-swatch {
                border: none;
                border-radius: 4px;
            }

            &:hover {
                border-color: #1890ff;
            }
        }
    }

    .size-inputs {
        display: flex;
        align-items: center;
        gap: 12px;

        .size-separator {
            font-size: 16px;
            color: #666;
        }
    }

    .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 12px;
        margin-top: 24px;
        padding-top: 20px;
        border-top: 1px solid #f0f0f0;
    }
}

// 响应式设计
.chart-dashboard {
    padding: 12px;
}

.dashboard-header {
    flex-direction: column;
    gap: 16px;
    align-items: stretch;

    .header-actions {
        justify-content: center;
    }
}

.chart-widget {

    .chart-widget-header {
        padding: 12px 16px;

        h3 {
            font-size: 14px;
        }
    }

    .chart-widget-content {
        padding: 12px;
    }
}
</style>
