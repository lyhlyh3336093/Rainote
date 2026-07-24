<template>
  <div class="relation-page">
    <!-- 顶部工具栏 -->
    <header class="toolbar">
      <div class="title">
        <span class="main-title">多表关联设计器</span>
        <span class="sub-title ml-4 text-xs text-gray-400">
          <span class="icon-tip">ℹ️</span> 1. 连接字段建立关联 2. 点击连线标签可切换关联类型
        </span>
      </div>
      <a-space>
        请选择数据表
        <a-divider type="vertical" />
        <a-select v-model:value="selectedTableKeys" mode="multiple" style="width: 100px" placeholder="请选择参与分析的数据表..."
          :options="tableOptions" @change="handleTableChange" :max-tag-count="2" />
      </a-space>
    </header>

    <div class="workspace">
      <!-- 左右布局容器 -->
      <section class="graph-section">
        <div class="section-header">
          <span class="label">实体关系图 </span>
          <div class="legend-container">
            <div class="legend-item"><span class="line inner"></span>Inner</div>
            <div class="legend-item"><span class="line left"></span>Left</div>
            <div class="legend-item"><span class="line right"></span>Right</div>
            <div class="legend-item"><span class="line full"></span>Full</div>
          </div>
        </div>

        <div class="er-wrapper">
          <transition name="fade">
            <div v-if="connectingSource" class="connecting-tip">
              <span class="step-badge">1</span> 已选中: <b class="highlight">{{ connectingSource.tableName }}.{{
                connectingSource.fieldName
              }}</b>
              <span class="arrow">→</span>
              <span class="step-badge">2</span> 请点击目标字段
              <a-button size="small" type="text" danger @click="cancelConnection" class="ml-2">取消</a-button>
            </div>
          </transition>

          <div v-if="selectedTableKeys.length === 0" class="empty-placeholder">
            <div class="text">请从顶部选择数据表</div>
          </div>

          <div class="er-container" ref="erContainerRef"></div>
        </div>
      </section>

      <section class="table-section">
        <div class="section-header">
          <div class="flex items-center">
            <span class="label">数据预览 </span>
            <span class="sub-label ml-2" v-if="relations.length > 0">
              驱动表: <b>{{ getMainTableName() }}</b>
              <span class="text-gray-400 mx-1">|</span>
              总行数: <b>{{ previewData.length }}</b>
            </span>
          </div>
        </div>

        <div class="table-wrapper">
          <div v-if="relations.length === 0" class="no-relation-placeholder">
            <div class="icon">🔗</div>
            <div class="text">暂无数据</div>
            <div class="sub-text">请在上方 ER 图中拖拽或点击字段建立关联关系</div>
          </div>

          <a-table v-else :dataSource="previewData" :columns="previewColumns" :pagination="false" size="small"
            :scroll="{ x: 'max-content', y: 300 }" row-key="__rowKey" bordered>
            <template #headerCell="{ column }">
              <div class="custom-header">
                <a-tag :color="getTableColor(column.tableKey)" class="table-tag">{{ column.customTableName }}</a-tag>
                <span class="field-name">{{ column.customFieldName }}</span>
                <span v-if="column.isJoinKey" class="join-icon" title="关联键">🔗</span>
              </div>
            </template>
            <template #bodyCell="{ column, text }">
              <span v-if="text === null || text === undefined" class="text-gray-300 italic text-xs">NULL</span>
              <span v-else>{{ text }}</span>
            </template>
          </a-table>
        </div>
      </section>
    </div>

    <!-- 关联配置弹窗 -->
    <a-modal v-model:open="showFieldModal" :title="currentRelationId ? '编辑关联' : '新建关联'" width="600px" centered>
      <div class="join-config-container">
        <div class="field-match-row">
          <div class="field-card source">
            <div class="table-label">{{ fieldModal.leftTable }}</div>
            <div class="field-val">{{ fieldModal.leftField }}</div>
          </div>
          <div class="connector"><span class="link-icon">🔗</span></div>
          <div class="field-card target">
            <div class="table-label">{{ fieldModal.rightTable }}</div>
            <div class="field-val">{{ fieldModal.rightField }}</div>
          </div>
        </div>

        <div class="join-type-selector">
          <div class="type-grid">
            <div v-for="type in joinTypes" :key="type.value" class="type-card"
              :class="{ active: fieldModal.type === type.value }" @click="fieldModal.type = type.value">
              <div class="venn-icon" :class="type.value">
                <div class="circle left"></div>
                <div class="circle right"></div>
                <div class="intersection"></div>
              </div>
              <div class="name" :style="{ color: getTypeColor(type.value) }">{{ type.label }}</div>
              <div class="desc">{{ type.desc }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 弹窗底部按钮 -->
      <template #footer>
        <div class="flex justify-between items-center w-full">
          <div>
            <a-button v-if="currentRelationId" danger ghost @click="handleDeleteFromModal">解除关联</a-button>
          </div>
          <a-space>
            <a-button @click="showFieldModal = false">取消</a-button>
            <a-button type="primary" @click="handleSaveRelation">确定</a-button>
          </a-space>
        </div>
      </template>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick, watch } from 'vue';
import * as d3 from 'd3';

const props = defineProps(['table']);
import { useStore } from '../../stores/table';
import { useFetch } from "@/hooks";
import { FieldEnum } from "@/enum";

const store = useStore();

// --- 类型定义 ---
type JoinType = 'left' | 'right' | 'inner' | 'full';

interface FieldDef {
  id: string;
  name: string;
  type: string;
}

interface TableDef {
  key: string;
  name: string;
  fields: FieldDef[];
  rows: any[];
}

interface Relation {
  id: string;
  leftTable: string;
  leftField: string;
  rightTable: string;
  rightField: string;
  type: JoinType;
}

interface Node extends d3.SimulationNodeDatum {
  id: string;
  table: TableDef;
  w: number;
  h: number;
}

interface Link extends d3.SimulationLinkDatum<Node> {
  relation: Relation;
  source: Node;
  target: Node;
}

// 颜色常量
const COLORS = { inner: '#52c41a', left: '#2f54eb', right: '#722ed1', full: '#faad14' };
const getTableColor = (key: string) => {
  const colors = ['blue', 'cyan', 'purple', 'geekblue', 'magenta', 'volcano', 'gold'];
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = key.charCodeAt(i) + ((hash << 5) - hash);
  return colors[Math.abs(hash) % colors.length];
}

const joinTypes = [
  { label: 'Left Join', value: 'left', desc: '左表全部，右表匹配' },
  { label: 'Inner Join', value: 'inner', desc: '仅保留匹配数据' },
  { label: 'Right Join', value: 'right', desc: '右表全部，左表匹配' },
  { label: 'Full Join', value: 'full', desc: '并集，未匹配补 NULL' },
];

const getTypeColor = (type: string) => COLORS[type as keyof typeof COLORS];

// --- 状态 ---
const erContainerRef = ref<HTMLDivElement>();
const selectedTableKeys = ref<string[]>([]);
const relations = ref<Relation[]>([]);
const showFieldModal = ref(false);
const currentRelationId = ref<string | null>(null); // 新增：用于标记当前正在编辑的关系ID
const d3Nodes = ref<Node[]>([]);
const connectingSource = ref<{ tableKey: string; tableName: string; fieldName: string } | null>(null);
const checkedFields = reactive<Record<string, Set<string>>>({});

const fieldModal = reactive({
  leftTable: '', leftField: '',
  rightTable: '', rightField: '',
  type: 'left' as JoinType
});

const SETTINGS = { nodeWidth: 240, headerHeight: 36, rowHeight: 28 };

// --- 数据 ---
const allTables = ref<TableDef[]>([]);

watch(() => props.table, val => {
  allTables.value = val.map(item => {
    return {
      ...item,
      key: item.id,
      fields: [],
      rows: [],
    }
  })
}, {
  deep: true,
  immediate: true
});

const tableOptions = computed(() => allTables.value.map(t => ({ label: t.name, value: t.key })));
const selectedTables = computed(() => selectedTableKeys.value.map(k => allTables.value.find(t => t.key === k)!).filter(Boolean));
const getMainTableName = () => selectedTables.value.length > 0 ? selectedTables.value[0].name : '-';

const activeTableKeys = computed(() => {
  if (relations.value.length === 0) return new Set<string>();
  const active = new Set<string>();
  relations.value.forEach(r => {
    active.add(r.leftTable);
    active.add(r.rightTable);
  });
  return active;
});

const isFieldChecked = (tKey: string, fName: string) => checkedFields[tKey]?.has(fName) ?? false;
const toggleField = (tKey: string, fName: string) => {
  if (!checkedFields[tKey]) checkedFields[tKey] = new Set();
  const set = checkedFields[tKey];
  set.has(fName) ? set.delete(fName) : set.add(fName);
  updateGraph();
};

// --- 表格列 ---
const previewColumns = computed(() => {
  if (relations.value.length === 0) return [];
  const cols: any[] = [];
  selectedTables.value.forEach(table => {
    if (!activeTableKeys.value.has(table.key)) return;
    table.fields.forEach(field => {
      if (!isFieldChecked(table.key, field.name)) return;
      const isKey = relations.value.some(r =>
        (r.leftTable === table.key && r.leftField === field.name) ||
        (r.rightTable === table.key && r.rightField === field.name)
      );
      cols.push({
        customTableName: table.name,
        customFieldName: field.name,
        tableKey: table.key,
        dataIndex: `${table.key}__${field.id}`,
        key: `${table.key}__${field.id}`,
        width: 130,
        isJoinKey: isKey
      });
    });
  });
  return cols;
});

// --- 数据预览逻辑 ---
const previewData = computed(() => {
  if (selectedTables.value.length === 0 || relations.value.length === 0) return [];

  const visibleKeys = new Set<string>();
  selectedTables.value.forEach(table => {
    if (!activeTableKeys.value.has(table.key)) return;
    table.fields.forEach(field => {
      if (isFieldChecked(table.key, field.name)) visibleKeys.add(`${table.key}__${field.id}`);
    });
  });

  let result = selectedTables.value[0].rows.map(row => {
    const newRow: any = {};
    selectedTables.value[0].fields.forEach(f => {
      newRow[`${selectedTables.value[0].key}__${f.id}`] = row[f.name];
    });
    return newRow;
  });

  for (let i = 1; i < selectedTables.value.length; i++) {
    const targetTable = selectedTables.value[i];
    if (!activeTableKeys.value.has(targetTable.key)) continue;

    const rel = relations.value.find(r => (r.rightTable === targetTable.key) || (r.leftTable === targetTable.key));
    if (!rel) continue;

    const isTargetRight = rel.rightTable === targetTable.key;
    const sourceTableKey = isTargetRight ? rel.leftTable : rel.rightTable;
    const sourceFieldName = isTargetRight ? rel.leftField : rel.rightField;
    const targetFieldName = isTargetRight ? rel.rightField : rel.leftField;

    const sourceTableDef = selectedTables.value.find(t => t.key === sourceTableKey);
    const sourceFieldDef = sourceTableDef?.fields.find(f => f.name === sourceFieldName);
    const sourceDataKey = sourceFieldDef ? `${sourceTableKey}__${sourceFieldDef.id}` : `${sourceTableKey}__${sourceFieldName}`;

    const nextResult: any[] = [];
    const matchedTargetIndices = new Set<number>();

    result.forEach(row => {
      const joinVal = row[sourceDataKey];
      const matches = targetTable.rows.filter((r, idx) => {
        const isMatch = (r[targetFieldName] == joinVal) && (joinVal !== null) && (joinVal !== undefined);
        if (isMatch) matchedTargetIndices.add(idx);
        return isMatch;
      });

      if (matches.length > 0) {
        matches.forEach(match => {
          const newRow = { ...row };
          targetTable.fields.forEach(f => newRow[`${targetTable.key}__${f.id}`] = match[f.name]);
          nextResult.push(newRow);
        });
      } else {
        if (rel.type === 'left' || rel.type === 'full') {
          const newRow = { ...row };
          targetTable.fields.forEach(f => newRow[`${targetTable.key}__${f.id}`] = null);
          nextResult.push(newRow);
        }
      }
    });

    if (rel.type === 'right' || rel.type === 'full') {
      targetTable.rows.forEach((targetRow, idx) => {
        if (!matchedTargetIndices.has(idx)) {
          const newRow: any = {};
          targetTable.fields.forEach(f => newRow[`${targetTable.key}__${f.id}`] = targetRow[f.name]);
          for (let j = 0; j < i; j++) {
            const prevTable = selectedTables.value[j];
            if (activeTableKeys.value.has(prevTable.key)) {
              prevTable.fields.forEach(f => newRow[`${prevTable.key}__${f.id}`] = null);
            }
          }
          nextResult.push(newRow);
        }
      });
    }
    result = nextResult;
  }

  return result.map((fullRow, index) => {
    const cleanRow: any = {
      __rowKey: index,
    };
    visibleKeys.forEach(key => {
      if (fullRow[key] !== undefined) cleanRow[key] = fullRow[key];
    });
    return cleanRow;
  });
});


// --- D3 Render ---
let simulation: d3.Simulation<Node, Link> | null = null;
let svg: d3.Selection<SVGSVGElement, any, any, any> | null = null;

const initD3 = () => {
  if (!erContainerRef.value) return;
  const container = erContainerRef.value;
  container.innerHTML = '';
  const width = container.clientWidth;
  const height = container.clientHeight;

  svg = d3.select(container).append('svg')
    .attr('width', width).attr('height', height)
    .style('background-color', '#fcfcfc');

  const defs = svg.append('defs');
  defs.append('marker').attr('id', 'arrow').attr('viewBox', '0 -5 10 10').attr('refX', 8).attr('markerWidth', 6).attr('markerHeight', 6).attr('orient', 'auto').append('path').attr('d', 'M0,-5L10,0L0,5').attr('fill', '#999');

  const g = svg.append('g').attr('class', 'content-layer');
  svg.call(d3.zoom<SVGSVGElement, unknown>().scaleExtent([0.4, 2]).on('zoom', (e) => g.attr('transform', e.transform)));
  updateGraph();
};

const updateGraph = () => {
  if (!svg) return;
  const g = svg.select('.content-layer');
  const width = erContainerRef.value?.clientWidth || 800;
  const height = erContainerRef.value?.clientHeight || 500;

  const currentMap = new Map(d3Nodes.value.map(n => [n.id, n]));
  d3Nodes.value = selectedTables.value.map((t, i) => {
    const existing = currentMap.get(t.key);
    const h = SETTINGS.headerHeight + t.fields.length * SETTINGS.rowHeight + 8;
    return existing ? { ...existing, table: t, h } : {
      id: t.key, table: t, w: SETTINGS.nodeWidth, h,
      x: width / 2 + (i - selectedTables.value.length / 2) * 280 + 280, y: height / 2
    };
  });

  const links: Link[] = relations.value.map(r => {
    const s = d3Nodes.value.find(n => n.id === r.leftTable);
    const t = d3Nodes.value.find(n => n.id === r.rightTable);
    return s && t ? { id: r.id, source: s, target: t, relation: r } : null;
  }).filter(Boolean) as any;

  simulation = d3.forceSimulation<Node>(d3Nodes.value)
    .force('center', d3.forceCenter(width / 2, height / 2).strength(0.1))
    .on('tick', ticked);
  simulation.nodes(d3Nodes.value);
  simulation.alpha(0.3).restart();
  const linkGroup = g.selectAll('.links').data([0]).join('g').attr('class', 'links');
  const path = linkGroup.selectAll('path').data(links, (d: any) => d.relation.id).join('path')
    .attr('stroke', (d: any) => COLORS[d.relation.type])
    .attr('stroke-width', 2)
    .attr('stroke-dasharray', (d: any) => d.relation.type === 'inner' ? '0' : '4,2')
    .attr('fill', 'none')
    .attr('marker-end', 'url(#arrow)')
    .style('cursor', 'pointer') // 鼠标变成手型
    .on('mouseenter', function () {
      // 悬停时加粗，提示可点击
      d3.select(this).attr('stroke-width', 4).attr('opacity', 0.8);
    })
    .on('mouseleave', function () {
      // 移开恢复
      d3.select(this).attr('stroke-width', 2).attr('opacity', 1);
    })
    .on('click', (e, d: any) => {
      e.stopPropagation();
      openEditModal(d.relation); // 点击线打开弹窗
    });

  const labels = linkGroup.selectAll('g.lbl').data(links, (d: any) => d.relation.id).join('g').attr('class', 'lbl')
    .style('cursor', 'pointer')
    .on('click', (e, d) => openEditModal(d.relation)); // 点击标签打开编辑

  // 1. 标签背景 (带圆角的矩形)
  labels.selectAll('rect').data(d => [d]).join('rect')
    .attr('width', 50)
    .attr('height', 16)
    .attr('x', -25)
    .attr('y', -8)
    .attr('rx', 4)
    .attr('fill', d => COLORS[d.relation.type]) // 根据关联类型填充颜色
    .attr('stroke', '#fff')
    .attr('stroke-width', 1);

  // 2. 标签文字 (显示 Join 类型)
  labels.selectAll('text').data(d => [d]).join('text')
    .attr('text-anchor', 'middle')
    .attr('dy', 3.5)
    .attr('font-size', 9)
    .attr('font-weight', 'bold')
    .attr('fill', '#fff') // 白色文字
    .text(d => d.relation.type.toUpperCase()); // 显示 INNER / LEFT 等

  const nodeGroup = g.selectAll('.nodes').data([0]).join('g').attr('class', 'nodes');
  const nodes = nodeGroup.selectAll('g.node').data(d3Nodes.value, (d: any) => d.id).join('g').attr('class', 'node')
    .call(d3.drag<any, Node>().on('start', dragStart).on('drag', dragging).on('end', dragEnd));

  nodes.selectAll('rect.bg').data(d => [d]).join('rect').attr('class', 'bg').attr('width', d => d.w).attr('height', d => d.h).attr('rx', 4).attr('fill', '#fff')
    .attr('stroke', '#d9d9d9').style('filter', 'drop-shadow(0 2px 6px rgba(0,0,0,0.08))');
  nodes.selectAll('rect.head').data(d => [d]).join('rect').attr('class', 'head').attr('width', d => d.w).attr('height', SETTINGS.headerHeight).attr('rx', 4).attr('fill', '#333');
  nodes.selectAll('rect.fix').data(d => [d]).join('rect').attr('y', SETTINGS.headerHeight - 2).attr('width', d => d.w).attr('height', 2).attr('fill', '#333');
  nodes.selectAll('text.t').data(d => [d]).join('text').attr('x', 10).attr('y', 22).attr('fill', '#fff').attr('font-weight', 'bold').text(d => d.table.name);

  const fields = nodes.selectAll('g.f').data(d => d.table.fields.map((f, i) => ({
    ...f,
    i,
    tId: d.id,
    w: d.w
  }))).join('g').attr('class', 'f').attr('transform', d => `translate(0,${SETTINGS.headerHeight + d.i * SETTINGS.rowHeight})`);
  fields.selectAll('rect.hov').data(d => [d]).join('rect').attr('width', d => d.w).attr('height', SETTINGS.rowHeight).attr('fill', 'transparent').on('mouseenter', function () {
    d3.select(this).attr('fill', '#f0f7ff')
  }).on('mouseleave', function () {
    d3.select(this).attr('fill', 'transparent')
  });


  fields.selectAll('text.n').data(d => [d]).join('text').attr('x', 26).attr('y', 19).attr('font-size', 12).text(d => d.name);
  fields.selectAll('circle.a').data(d => [d]).join('circle').attr('cx', d => d.w - 10).attr('cy', 14).attr('r', 4.5).attr('fill', (d: any) => (connectingSource.value?.tableKey === d.tId && connectingSource.value?.fieldName === d.name) ? '#faad14' : '#fff').attr('stroke', (d: any) => (connectingSource.value?.tableKey === d.tId && connectingSource.value?.fieldName === d.name) ? '#faad14' : '#ccc').attr('cursor', 'pointer').on('click', (e, d) => handleFieldClick(d.tId, d.name));

  function ticked() {
    nodes.attr('transform', d => `translate(${d.x},${d.y})`);

    labels.attr('transform', (d: any) => {
      const sx = d.source.x + d.source.w, tx = d.target.x;
      return `translate(${(sx + tx) / 2}, ${(d.source.y + d.target.y + 150) / 2})`;
    });
    // 定义获取字段 Y 坐标的内部工具函数
    const getFieldY = (n: Node, fName: string) => {
      const fIdx = n.table.fields.findIndex(f => f.name === fName);
      // 基础 Y + 头部高度 + (索引 * 行高) + 半行高(居中)
      return n.y + SETTINGS.headerHeight + fIdx * SETTINGS.rowHeight + SETTINGS.rowHeight / 2;
    };

    // 1. 更新路径
    path.attr('d', (d: any) => {
      // 自动判定锚点 X 坐标
      // 逻辑：如果源表在目标表左侧，则源表用右边(x+w)，目标表用左边(x)
      // 反之，源表用左边(x)，目标表用右边(x+w)
      const isSourceLeft = d.source.x + d.source.w / 2 < d.target.x + d.target.w / 2;

      const sx = isSourceLeft ? d.source.x + d.source.w : d.source.x;
      const tx = isSourceLeft ? d.target.x : d.target.x + d.target.w;

      const sy = getFieldY(d.source, d.relation.leftField);
      const ty = getFieldY(d.target, d.relation.rightField);

      // 控制点偏移量（决定曲线弧度）
      const dx = Math.abs(tx - sx) * 0.5;
      const cx1 = isSourceLeft ? sx + dx : sx - dx;
      const cx2 = isSourceLeft ? tx - dx : tx + dx;

      return `M ${sx} ${sy} C ${cx1} ${sy}, ${cx2} ${ty}, ${tx} ${ty}`;
    })
      .attr('marker-end', (d: any) => `url(#arrow-${d.relation.type})`); // 动态匹配颜色的箭头

    // 1. 更新线条位置
    path.attr('d', (d: any) => {
      const sx = d.source.x + d.source.w;
      const sy = getFieldY(d.source, d.relation.leftField);
      const tx = d.target.x;
      const ty = getFieldY(d.target, d.relation.rightField);
      // 绘制三次贝塞尔曲线
      return `M ${sx} ${sy} C ${(sx + tx) / 2} ${sy}, ${(sx + tx) / 2} ${ty}, ${tx} ${ty}`;
    });

    // 2. 更新标签位置 (让它始终在连线的中心)
    labels.attr('transform', (d: any) => {
      const sx = d.source.x + d.source.w;
      const sy = getFieldY(d.source, d.relation.leftField);
      const tx = d.target.x;
      const ty = getFieldY(d.target, d.relation.rightField);

      // 计算连线的中点坐标
      const midX = (sx + tx) / 2;
      const midY = (sy + ty) / 2;
      return `translate(${midX}, ${midY})`;
    });
  }
};

function dragStart(e: any, d: Node) {
  if (!e.active) simulation?.alphaTarget(0.3).restart();
  d.fx = d.x;
  d.fy = d.y;
}

function dragging(e: any, d: Node) {
  d.fx = e.x;
  d.fy = e.y;
}

function dragEnd(e: any, d: Node) {
  if (!e.active) simulation?.alphaTarget(0);
  d.fx = d.x;
  d.fy = d.y;
}

const handleFieldClick = (tKey: string, fName: string) => {
  // 获取当前已经选中的起始点
  const current = connectingSource.value;

  // 场景 1: 当前没有选中任何字段 -> 直接选中
  if (!current) {
    connectingSource.value = {
      tableKey: tKey,
      fieldName: fName,
      tableName: allTables.value.find(t => t.key === tKey)?.name || ''
    };
  }
  // 场景 2: 点击的是已经选中的同一个字段 -> 取消选中
  else if (current.tableKey === tKey && current.fieldName === fName) {
    connectingSource.value = null;
  }
  // 场景 3: 点击的是【同一张表】的其他字段 -> 立即切换
  else if (current.tableKey === tKey) {
    connectingSource.value = {
      tableKey: tKey,
      fieldName: fName,
      tableName: allTables.value.find(t => t.key === tKey)?.name || ''
    };
  }
  // 场景 4: 点击的是【不同表】的字段 -> 弹出关联配置
  else {
    currentRelationId.value = null; // 标记为新建
    fieldModal.leftTable = current.tableKey;
    fieldModal.leftField = current.fieldName;
    fieldModal.rightTable = tKey;
    fieldModal.rightField = fName;
    fieldModal.type = 'left';
    showFieldModal.value = true;

    // 触发弹窗后，清除连接状态
    connectingSource.value = null;
  }

  // 关键：修改完状态后，必须手动触发 D3 的更新重绘，否则圆点颜色不会变
  updateGraph();
};

// 打开编辑弹窗
const openEditModal = (rel: Relation) => {
  currentRelationId.value = rel.id; // 标记为编辑
  fieldModal.leftTable = rel.leftTable;
  fieldModal.leftField = rel.leftField;
  fieldModal.rightTable = rel.rightTable;
  fieldModal.rightField = rel.rightField;
  fieldModal.type = rel.type;
  showFieldModal.value = true;
};

const cancelConnection = () => {
  connectingSource.value = null;
  updateGraph();
};

// 保存关联 (新增 或 编辑)
const handleSaveRelation = () => {
  if (currentRelationId.value) {
    // 编辑模式：更新现有的
    const index = relations.value.findIndex(r => r.id === currentRelationId.value);
    if (index !== -1) {
      relations.value[index] = { ...relations.value[index], type: fieldModal.type };
    }
  } else {
    // 新增模式
    relations.value.push({ id: Date.now().toString(), ...fieldModal });
    // 自动勾选逻辑
    if (!checkedFields[fieldModal.leftTable]) checkedFields[fieldModal.leftTable] = new Set();
    checkedFields[fieldModal.leftTable].add(fieldModal.leftField);
    if (!checkedFields[fieldModal.rightTable]) checkedFields[fieldModal.rightTable] = new Set();
    checkedFields[fieldModal.rightTable].add(fieldModal.rightField);
  }

  showFieldModal.value = false;
  currentRelationId.value = null; // 重置
  updateGraph();
};

// 在弹窗中点击“解除关联”
const handleDeleteFromModal = () => {
  if (currentRelationId.value) {
    deleteRelation(currentRelationId.value);
    showFieldModal.value = false;
    currentRelationId.value = null;
  }
}

const deleteRelation = (id: string) => {
  relations.value = relations.value.filter(r => r.id !== id);
  updateGraph();
};

const handleTableChange = async () => {
  // 清理不存在的表
  for (const key in checkedFields) {
    if (!selectedTableKeys.value.includes(key)) delete checkedFields[key];
  }
  const columns = await Promise.all(selectedTableKeys.value.map(id => useFetch(`/system/column/columnList?dwtableId=${id}`,).get().json()));
  columns.forEach(({ data }, index) => {
    allTables.value[index].fields = data.value.data;
  })

  const rows = await Promise.all(selectedTableKeys.value.map(id => useFetch(`/system/record/dataList?dwtableId=${id}`,).get().json()));
  rows.forEach(({ data }, index) => {
    const columns = allTables.value[index].fields;
    const list = data.value.data.filter((item: {
      tableData: any;
    }) => item?.tableData).sort((a, b) => a.id - b.id);
    const tableData = list.map((item: {
      [x: string]: { [x: string]: any; };
      tableData: { [x: string]: any; };
      id: any;
    }) => {
      for (const key in item.tableData) {
        const column = columns.find((v: { id: any; }) => `${v.id}` === key);
        if (column) {
          if ([FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(column.type)) {
            item['tableData'][column.id] = item['tableData'][column.id].split(',').filter((item: any) => item)
          }
          if (item['tableData'][column.id]?.length === 0) {
            item['tableData'][column.id] = null;
          }
          item['tableData'][item.columns[key]] = item['tableData'][column.id];
        }
      }
      item.tableData['id'] = item.id;
      return item.tableData;
    });
    allTables.value[index].rows = tableData;
  })


  relations.value = relations.value.filter(r => selectedTableKeys.value.includes(r.leftTable) && selectedTableKeys.value.includes(r.rightTable));
  nextTick(updateGraph);
};

onMounted(() => {
  initD3();
  window.addEventListener('resize', initD3);
});
</script>

<style scoped lang="scss">
.relation-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f0f2f5;
}

.toolbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;

  .main-title {
    font-weight: 700;
    font-size: 16px;
    color: #1f1f1f;
  }
}

.workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  gap: 16px;
  overflow: auto;
}

/* 图表区域 */
.graph-section {
  flex: 3;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
  min-height: 500px;
}

.section-header {
  height: 48px;
  padding: 0 16px;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;

  .label {
    font-weight: 600;
    color: #333;
  }

  .sub-label {
    font-size: 12px;
    color: #666;
  }
}

/* 图例系统 */
.legend-container {
  display: flex;
  align-items: center;
  gap: 16px;

  .legend-item {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: #666;

    .line {
      width: 16px;
      height: 3px;
      border-radius: 2px;
    }

    .line.inner {
      background: #52c41a;
    }

    .line.left {
      background: #2f54eb;
      border-bottom: 2px dashed #2f54eb;
      height: 0;
    }

    .line.right {
      background: #722ed1;
      border-bottom: 2px dotted #722ed1;
      height: 0;
    }

    .line.full {
      background: #faad14;
      height: 1px;
    }

    .icon-box {
      width: 12px;
      height: 12px;
      border: 1px solid #ccc;
      border-radius: 2px;
    }

    .icon-box.checked {
      background: #1890ff;
      border-color: #1890ff;
    }
  }

  .divider {
    width: 1px;
    height: 16px;
    background: #eee;
  }
}

.er-wrapper {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.er-container {
  width: 100%;
  height: 100%;
  background-image: radial-gradient(#e0e0e0 1px, transparent 1px);
  background-size: 20px 20px;
}

.empty-placeholder {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #bbb;
}

.connecting-tip {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  background: #fff;
  padding: 6px 16px;
  border-radius: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  border: 1px solid #1890ff;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;

  .step-badge {
    background: #1890ff;
    color: #fff;
    border-radius: 50%;
    width: 16px;
    height: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 10px;
  }

  .highlight {
    color: #1890ff;
  }
}

/* 表格区域 */
.table-section {
  flex: 2;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
  min-height: 200px;
}

.table-wrapper {
  flex: 1;
  overflow: auto;
  padding: 0;
  position: relative;
}

.custom-header {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;

  .table-tag {
    margin: 0;
    font-size: 10px;
    border-radius: 2px;
    height: 20px;
    line-height: 18px;
    padding: 0 4px;
  }

  .field-name {
    font-weight: 600;
    color: #333;
  }

  .join-icon {
    font-size: 12px;
  }
}

.text-warning {
  color: #faad14;
}

/* 空状态占位符 */
.no-relation-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;

  .icon {
    font-size: 32px;
    margin-bottom: 8px;
    opacity: 0.5;
  }

  .text {
    font-size: 14px;
    font-weight: 500;
    color: #666;
  }

  .sub-text {
    font-size: 12px;
    margin-top: 4px;
  }
}

/* 弹窗样式 */
.join-config-container {
  padding: 12px 24px;
}

.field-match-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f7f9fc;
  padding: 20px;
  border-radius: 8px;
  border: 1px solid #eef2f6;
  margin-bottom: 24px;

  .field-card {
    background: #fff;
    border: 1px solid #e8e8e8;
    border-radius: 6px;
    width: 200px;
    padding: 12px;
    text-align: center;
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.02);

    .table-label {
      font-size: 12px;
      color: #999;
      margin-bottom: 4px;
    }

    .field-val {
      font-size: 15px;
      font-weight: 600;
      color: #333;
    }
  }

  .connector {
    display: flex;
    align-items: center;
    justify-content: center;
    color: #ccc;
    flex: 1;
    font-size: 20px;
  }
}

.type-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;

  .type-card {
    border: 1px solid #e8e8e8;
    border-radius: 8px;
    padding: 16px;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    transition: all 0.2s;
    background: #fff;

    &:hover {
      border-color: #1890ff;
      transform: translateY(-1px);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
    }

    &.active {
      border-color: #1890ff;
      background: #f0f7ff;
      box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
    }

    .name {
      font-size: 14px;
      font-weight: 600;
      margin-bottom: 4px;
    }

    .desc {
      font-size: 12px;
      color: #999;
    }
  }
}

.venn-icon {
  width: 50px;
  height: 32px;
  position: relative;
  margin-bottom: 12px;

  .circle {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    position: absolute;
    top: 4px;
    border: 2px solid #e8e8e8;
    background: #fff;
    z-index: 1;
  }

  .circle.left {
    left: 4px;
  }

  .circle.right {
    right: 4px;
  }

  &.left .circle.left {
    background: #2f54eb;
    border-color: #2f54eb;
    z-index: 2;
  }

  &.left .circle.right {
    border-color: #d9d9d9;
  }

  &.inner .circle {
    border-color: #52c41a;
  }

  &.inner::after {
    content: '';
    position: absolute;
    top: 4px;
    left: 16px;
    width: 18px;
    height: 24px;
    background: #52c41a;
    clip-path: ellipse(40% 50% at 50% 50%);
    z-index: 3;
  }

  &.right .circle.right {
    background: #722ed1;
    border-color: #722ed1;
    z-index: 2;
  }

  &.right .circle.left {
    border-color: #d9d9d9;
  }

  &.full .circle.left {
    background: #faad14;
    border-color: #faad14;
  }

  &.full .circle.right {
    background: #faad14;
    border-color: #faad14;
  }
}
</style>
