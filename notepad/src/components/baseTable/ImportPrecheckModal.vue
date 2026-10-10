<template>
  <a-modal
    v-model:open="state.visible"
    title="导入预检"
    width="680px"
    :footer="null"
    destroyOnClose
    centered
  >
    <div class="excel-precheck-body">
      <!-- 替换式导入警示（有删除即必显：数据删除不可无提示发生） -->
      <a-alert v-if="hasReplace" type="error" show-icon style="margin-bottom: 12px">
        <template #message>本次导入将替换表数据（不可恢复，请确认）</template>
        <template #description>
          <div v-for="(line, i) in replaceLines" :key="i">{{ line }}</div>
        </template>
      </a-alert>

      <!-- 基础列/违规列缺参：每列一行统一值输入（R12） -->
      <template v-if="basicGroups.length > 0">
        <a-divider orientation="left" class="precheck-divider">列补值（空 = 接受空值）</a-divider>
      </template>
      <div v-for="g in basicGroups" :key="`basic-${g.columnName}`" class="precheck-form-row">
        <div class="precheck-label">
          <span class="precheck-name">{{ g.columnName }}</span>
          <span class="precheck-hint">{{ g.hint }}</span>
        </div>
        <a-input
          v-model:value="g.value"
          :status="g.error ? 'error' : undefined"
          :placeholder="g.placeholder"
          :disabled="state.loading"
          @change="validateBasic(g)"
        />
        <div v-if="g.error" class="precheck-error">{{ g.error }}</div>
      </div>

      <!-- 关联列缺参：按 distinct 未命中名分组逐值选择器（KTD7；替换式语义下列未出现在
           Excel 中即被删除，"关联缺列"kind 已消失） -->
      <template v-for="col in relationColumns" :key="`rel-${col.columnId}`">
        <a-divider orientation="left" class="precheck-divider">
          关联列「{{ col.columnName }}」未命中记录（{{ col.groups.length }} 组）
        </a-divider>
        <div v-if="col.groups.length > COLLAPSE_THRESHOLD" class="precheck-toolbar">
          <a-button size="small" :disabled="state.loading" @click="toggleCollapse(col)">
            {{ col.collapsed ? `展开全部（剩余 ${unhandledCount(col)} 组未处理）` : '折叠未处理项' }}
          </a-button>
          <a-input
            v-model:value="col.filterKeyword"
            size="small"
            allowClear
            placeholder="按未命中名过滤"
            class="precheck-filter"
            :disabled="state.loading"
          />
          <a-button size="small" danger :disabled="state.loading" @click="markAllBlank(col)">
            全部留空
          </a-button>
        </div>
        <div v-for="g in visibleGroups(col)" :key="`rel-${col.columnId}-${g.name}`" class="precheck-form-row">
          <div class="precheck-label">
            <span class="precheck-name">{{ g.name }}</span>
            <span class="precheck-hint">{{ g.hint }}</span>
          </div>
          <a-select
            v-model:value="g.selectedId"
            show-search
            allowClear
            :placeholder="'选择目标记录（清空 = 不建立关联）'"
            style="width: 100%"
            :options="g.options"
            :disabled="state.loading"
            option-filter-prop="label"
          />
        </div>
        <!-- #14：超出渲染上限的未处理分组计数行（不可展开，经过滤框或"全部留空"处理） -->
        <div v-if="remainingUnhandledCount(col) > 0" class="precheck-hint" style="margin-bottom: 8px">
          其余 {{ remainingUnhandledCount(col) }} 组未处理
        </div>
      </template>

      <!-- 链接解析失败（U6，R8）：21/25 列（及入链重指向名称歧义）强失败——只读呈现，
           无法补参，须修正 Excel 文件后重新预检（确认导入已禁用，零写入门禁） -->
      <template v-if="linkFailures.length > 0">
        <a-divider orientation="left" class="precheck-divider">
          链接解析失败（{{ linkFailures.length }} 项，须修正文件后重新预检）
        </a-divider>
      </template>
      <div v-for="(f, i) in linkFailures" :key="`lf-${i}`" class="precheck-form-row">
        <div class="precheck-label">
          <span class="precheck-name">{{ f.columnName }}{{ f.rowNumber > 0 ? ` · 第 ${f.rowNumber} 行` : '' }}</span>
          <span class="precheck-hint">{{ f.entryText }}</span>
        </div>
        <div class="precheck-error">{{ f.reason }}</div>
        <div v-if="f.explanation" class="precheck-hint">{{ f.explanation }}</div>
      </div>

      <!-- 歧义项：预选 sort 靠前可改选（R14/KTD7） -->
      <template v-if="ambiguityGroups.length > 0">
        <a-divider orientation="left" class="precheck-divider">同名记录歧义（已预选，可改选）</a-divider>
      </template>
      <div v-for="g in ambiguityGroups" :key="`amb-${g.columnName}-${g.name}`" class="precheck-form-row">
        <div class="precheck-label">
          <span class="precheck-name">{{ g.columnName }} → {{ g.name }}</span>
          <span class="precheck-hint">{{ g.candidateCount }} 条同名记录</span>
        </div>
        <a-select
          v-model:value="g.selectedId"
          show-search
          allowClear
          placeholder="选择目标记录（清空 = 不建立关联）"
          style="width: 100%"
          :options="g.options"
          :disabled="state.loading"
          option-filter-prop="label"
        />
      </div>

      <!-- 影响面摘要（所有模式显示） -->
      <template v-if="impactSummary.length > 0">
        <a-divider orientation="left" class="precheck-divider">影响面摘要</a-divider>
        <ul class="precheck-impact">
          <li v-for="(item, index) in impactSummary" :key="index">{{ item }}</li>
        </ul>
      </template>
    </div>

    <div style="text-align: right; margin-top: 16px">
      <a-button style="margin-right: 8px" :disabled="state.loading" @click="cancel">取消</a-button>
      <a-button type="primary" :loading="state.loading" :disabled="!canConfirm" @click="confirm">
        确认导入
      </a-button>
    </div>
  </a-modal>
</template>

<script lang="tsx">
import { computed, defineComponent, reactive, watch } from 'vue';
import { Modal } from 'ant-design-vue';
import { importExcelData } from '@/api/import';
import type {
  ExcelImportParamsPayload,
  ExcelImportPrecheckResult,
  ExcelLinkFailure,
  ExcelRecordCandidate,
} from '@/api/import';

/** 分组折叠阈值：某列未命中名分组数超过该值时默认折叠，仅展开未处理项（R12 分组规模策略） */
const COLLAPSE_THRESHOLD = 20;

/**
 * 折叠分支未处理分组渲染上限（#14）：弹框刚打开时全部分组均未处理，上万 distinct
 * 未命中名时无上限会渲染数万 a-select 卡死 UI；50 行兼顾首屏渲染开销与滚动定位可用性，
 * 超出部分以"其余 N 组未处理"计数行提示（不可展开），经上方关键字过滤触达
 * 或"全部留空"批量处理。
 */
const UNHANDLED_RENDER_LIMIT = 50;

/** 基础列/违规列缺参输入分组 */
interface BasicGroup {
  columnName: string;
  columnType: number | null;
  hint: string;
  placeholder: string;
  value: string;
  error: string | null;
}

/** 关联列按 distinct 未命中名分组的选择器（KTD7） */
interface RelationGroup {
  columnId: number;
  columnName: string;
  name: string;
  hint: string;
  options: { label: string; value: number }[];
  selectedId: number | null;
  /** "全部留空"批量操作标记：已显式确认为留空（发射 recordId:null），折叠时视为已处理 */
  markedBlank: boolean;
}

/** 歧义项分组（预选 preselectedRecordId，可改选可清空） */
interface AmbiguityGroup extends RelationGroup {
  candidateCount: number;
}

/** 关联列整体（一个 18/21 列一组，承载折叠状态） */
interface RelationColumn {
  columnId: number;
  columnName: string;
  groups: RelationGroup[];
  collapsed: boolean;
  /** 未命中名关键字过滤（#14：渲染上限下触达被截断分组） */
  filterKeyword: string;
}

/**
 * Excel 导入预检弹框（U6，R12/R13）——一个组件两种模式：
 * <ul>
 *   <li>补参模式（hasBlockingIssues）：缺参逐列补值 + 关联列逐值记录选择器（KTD7）；
 *       21/25 列链接解析失败（R8 强失败，无法补参）以只读分组呈现并禁用确认导入
 *       （零写入门禁），须修正文件后重新预检；</li>
 *   <li>只读确认模式（无缺参无歧义但有影响面信息）：仅影响面摘要 + 确认/取消（R13 轻量确认）。</li>
 * </ul>
 * 候选数据源仅用预检响应内嵌的 relationCandidates（已过后端归属校验），
 * 客户端过滤搜索，不调用任何远程搜索端点。
 */
export default defineComponent({
  name: 'ImportPrecheckModal',
  props: {
    /** 预检结果（阶段一响应 data） */
    precheck: { type: Object as () => ExcelImportPrecheckResult, required: true },
    /** 预检的文件（阶段二重新上传，KTD5 文件同一性） */
    file: { type: File, required: true },
    /** 多维表格归属笔记 id */
    noteId: { type: [String, Number], required: true },
    /** 目标数据表 id */
    dwtableId: { type: [String, Number], required: true },
    /** 是否可见（v-model:open） */
    open: { type: Boolean, default: false },
  },
  emits: ['update:open', 'success'],
  setup(props, ctx) {
    const state = reactive({
      visible: false,
      loading: false,
    });
    const basicGroups = reactive<BasicGroup[]>([]);
    const relationColumns = reactive<RelationColumn[]>([]);
    const ambiguityGroups = reactive<AmbiguityGroup[]>([]);

    /** 列类型 → 输入提示/占位文案 */
    const typeHint = (columnType: number | null): { hint: string; placeholder: string } => {
      switch (columnType) {
        case 2: return { hint: '', placeholder: '数字，如 42 / 3.14' };
        case 5: return { hint: '', placeholder: 'yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd' };
        case 7: return { hint: '', placeholder: 'true 或 false' };
        default: return { hint: '', placeholder: '统一值，留空接受空值' };
      }
    };

    /** 关联列候选（预检内嵌，KTD7 安全判据——只用预检候选，禁止远程搜索） */
    const candidatesOf = (columnId: number): { label: string; value: number }[] => {
      const found = (props.precheck.relationCandidates || []).find(
        (c) => `${c.columnId}` === `${columnId}`,
      );
      return (found?.candidates || []).map((c: ExcelRecordCandidate) => ({
        label: c.name,
        value: c.recordId,
      }));
    };

    /** 缺参行提示：缺列=全部 N 行；缺值/违规=行号集摘要；关联未命中=出现行号集 */
    const rowHint = (rows: number[] | null, total: number | null): string => {
      if (total != null) return `缺失 ${total} 行`;
      if (rows && rows.length > 0) return `缺失 ${rows.length} 行`;
      return '';
    };

    /** 从预检结果构建全部补参输入分组（只读确认模式下全空） */
    const buildGroups = () => {
      basicGroups.splice(0, basicGroups.length);
      relationColumns.splice(0, relationColumns.length);
      ambiguityGroups.splice(0, ambiguityGroups.length);
      const p = props.precheck;
      if (!p) return;

      // 基础列/违规列：缺值/类型违规 → a-input 统一值（替换式语义下缺列 kind 已消失）
      for (const m of p.missingParams || []) {
        if (m.kind === '缺值' || m.kind === '类型违规') {
          const { hint: tHint, placeholder } = typeHint(m.columnType);
          const rows = m.rowNumbers;
          const hint = [rowHint(rows, m.totalRows), m.kind === '类型违规' ? m.reason : '']
            .filter(Boolean).join('：') || tHint;
          basicGroups.push({
            columnName: m.columnName,
            columnType: m.columnType,
            hint,
            placeholder,
            value: '',
            error: null,
          });
        }
      }

      // 关联列：关联未命中的 missNames（替换式语义下关联缺列 kind 已消失）
      for (const m of p.missingParams || []) {
        if (m.kind === '关联未命中') {
          const options = candidatesOf(m.columnId);
          const groups: RelationGroup[] = (m.missNames || []).map((n) => ({
            columnId: m.columnId,
            columnName: m.columnName,
            name: n.name,
            hint: rowHint(n.rowNumbers, null),
            options,
            selectedId: null,
            markedBlank: false,
          }));
          relationColumns.push({
            columnId: m.columnId,
            columnName: m.columnName,
            groups,
            collapsed: groups.length > COLLAPSE_THRESHOLD,
            filterKeyword: '',
          });
        }
      }

      // 歧义项：预选 sort 靠前记录（R14），用户可改选/清空
      for (const a of p.ambiguityItems || []) {
        ambiguityGroups.push({
          columnId: a.columnId,
          columnName: a.columnName,
          name: a.name,
          hint: `${a.candidateCount} 条同名记录`,
          options: candidatesOf(a.columnId),
          selectedId: a.preselectedRecordId ?? null,
          markedBlank: false,
          candidateCount: a.candidateCount,
        });
      }
    };

    /** 分组已构建自的预检引用（去重标记，避免 open watch 与 precheck watch 同批双重构建） */
    let builtFrom: ExcelImportPrecheckResult | null | undefined;
    const rebuildGroups = () => {
      builtFrom = props.precheck;
      buildGroups();
    };
    // #10: precheck 引用变化即重建分组（与 open 状态无关）——失败保留期间用户重新预检成功后，
    // 弹框分组刷新为新文件内容（已填补参重置为空是预期：新文件新基线）
    watch(() => props.precheck, rebuildGroups);
    // immediate：父组件在同一同步块内设置 precheck 与 modalOpen（v-if 首挂时 open 已为 true，
    // 非 immediate 的 watch 挂载后无变化永不触发，弹框死锁不显示——#15）
    watch(() => props.open, (open) => {
      state.visible = open;
      // 引用未变才由 open 重建（本次引用已由 precheck watch 构建过则跳过）
      if (open && builtFrom !== props.precheck) rebuildGroups();
    }, { immediate: true });
    watch(() => state.visible, (v) => ctx.emit('update:open', v));

    /** 前端类型校验（与后端 ExcelColumnMatcher.validateCellText 同语义，空输入=接受空值） */
    const isStrictDate = (value: string): boolean => {
      const dt = value.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/);
      const d = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
      const m = dt || d;
      if (!m) return false;
      const year = Number(m[1]);
      const month = Number(m[2]);
      const day = Number(m[3]);
      if (month < 1 || month > 12) return false;
      const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
      const daysInMonth = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
      if (day < 1 || day > daysInMonth[month - 1]) return false;
      if (d) return true;
      const hour = Number(dt![4]);
      const minute = Number(dt![5]);
      const second = Number(dt![6]);
      return hour <= 23 && minute <= 59 && second <= 59;
    };

    /** 十进制数字串校验（对齐后端 BigDecimal 语义，拒绝 0x/Infinity/NaN 等非十进制形式） */
    const isDecimalNumber = (value: string): boolean =>
      /^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(value);

    const validateBasic = (g: BasicGroup) => {
      const value = (g.value || '').trim();
      if (!value) {
        g.error = null;
        return;
      }
      switch (g.columnType) {
        case 2:
          g.error = isDecimalNumber(value) ? null : '数字格式非法';
          break;
        case 5:
          g.error = isStrictDate(value) ? null : '日期格式须为 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd';
          break;
        case 7:
          g.error = value === 'true' || value === 'false' ? null : '复选框值须为 true 或 false';
          break;
        default:
          g.error = null;
      }
    };

    /** 未处理分组数（未选择且未显式留空） */
    const unhandledCount = (col: RelationColumn): number =>
      col.groups.filter((g) => g.selectedId == null && !g.markedBlank).length;

    /** 折叠时只显示未处理项（#14：加渲染上限）；展开时显示全部（用户主动展开不受限） */
    const visibleGroups = (col: RelationColumn): RelationGroup[] => {
      if (!col.collapsed) return col.groups;
      const unhandled = col.groups.filter((g) => g.selectedId == null && !g.markedBlank);
      const keyword = (col.filterKeyword || '').trim();
      if (keyword) {
        // 关键字过滤：匹配组正常显示（同样受上限保护，防单字符命中数千组再次放大渲染量）
        return unhandled.filter((g) => g.name.includes(keyword)).slice(0, UNHANDLED_RENDER_LIMIT);
      }
      return unhandled.slice(0, UNHANDLED_RENDER_LIMIT);
    };

    /** 折叠分支超出渲染上限的未处理分组数（"其余 N 组未处理"计数行，不可展开） */
    const remainingUnhandledCount = (col: RelationColumn): number =>
      col.collapsed ? unhandledCount(col) - visibleGroups(col).length : 0;

    const toggleCollapse = (col: RelationColumn) => {
      col.collapsed = !col.collapsed;
    };

    /** 全部留空：把该列所有未选分组置为显式空（发射 recordId:null） */
    const markAllBlank = (col: RelationColumn) => {
      for (const g of col.groups) {
        if (g.selectedId == null) {
          g.markedBlank = true;
        }
      }
    };

    /** 链接解析失败清单（U6，R8）：21/25 列强失败 + 入链重指向名称歧义——只读呈现，不参与补参 */
    const linkFailures = computed<ExcelLinkFailure[]>(() =>
      (props.precheck?.linkFailures || []) as ExcelLinkFailure[]);

    /** 替换式导入警示：有删除（行/列）或跨表入链影响（重指向/清空）即必显（数据变更不可无提示发生） */
    const hasReplace = computed(() => {
      const p = props.precheck;
      if (!p) return false;
      const r = p.replaceImpact;
      return !!r && ((r.deletedRowCount || 0) > 0
        || (r.deletedColumns || []).length > 0
        || (r.repointedLinkColumns || []).length > 0
        || (r.willClearLinkEntries || []).length > 0);
    });

    /** 替换警示分句（只列删除类与跨表入链影响；新建列/取消隐藏在影响面摘要中呈现）。
     *  跨表入链按重指向口径（U6 语义迁移，取代旧"将清空关联列"整列清空警示） */
    const replaceLines = computed<string[]>(() => {
      const p = props.precheck;
      const r = p?.replaceImpact;
      if (!r) return [];
      const lines: string[] = [];
      if ((r.deletedRowCount || 0) > 0) {
        lines.push(`删除现有 ${r.deletedRowCount} 行数据`);
      }
      const deleted = r.deletedColumns || [];
      if (deleted.length > 0) {
        const names = deleted.map((c) => c.columnName).join('、');
        lines.push(`删除 ${deleted.length} 列：${names.length > 60 ? `${names.slice(0, 60)}…` : names}`);
      }
      const repointed = r.repointedLinkColumns || [];
      if (repointed.length > 0) {
        const totalEntries = repointed.reduce((sum, c) => sum + (c.repointedEntryCount || 0), 0);
        const names = repointed.map((c) => `${c.tableName}.${c.columnName}`).join('、');
        lines.push(`其他表指向本表的 ${totalEntries} 条链接将重指向新记录（${
          names.length > 60 ? `${names.slice(0, 60)}…` : names}）`);
      }
      const willClear = r.willClearLinkEntries || [];
      if (willClear.length > 0) {
        const names = [...new Set(willClear.map((c) => `${c.tableName}.${c.columnName}`))].join('、');
        lines.push(`将清空 ${willClear.length} 条他表链接条目（指向未保留的记录：${
          names.length > 60 ? `${names.slice(0, 60)}…` : names}）`);
      }
      return lines;
    });

    /** 影响面摘要（所有模式显示，R11；替换式新增建列/取消隐藏信息性条目） */
    const impactSummary = computed<string[]>(() => {
      const p = props.precheck;
      if (!p) return [];
      const items: string[] = [];
      for (const f of p.defaultValueFills || []) {
        items.push(`列「${f.columnName}」将以默认值「${f.defaultValue}」填充 ${f.rowCount} 行`);
      }
      if ((p.newOptions || []).length > 0) {
        items.push(`将创建 ${p.newOptions.length} 个新选项（单选/多选列自动追加）`);
      }
      // symmetricWriteImpact 按 tableName 聚合
      const byTable = new Map<string, number>();
      for (const s of p.symmetricWriteImpact || []) {
        byTable.set(s.tableName, (byTable.get(s.tableName) || 0) + s.affectedRecordCount);
      }
      for (const [tableName, count] of byTable) {
        items.push(`双向关联将修改表「${tableName}」的 ${count} 条记录`);
      }
      const newColumnNames = p.replaceImpact?.newColumnNames || [];
      if (newColumnNames.length > 0) {
        items.push(`将新建 ${newColumnNames.length} 个文本列：${newColumnNames.join('、')}`);
      }
      const unhiddenColumnNames = p.replaceImpact?.unhiddenColumnNames || [];
      if (unhiddenColumnNames.length > 0) {
        items.push(`${unhiddenColumnNames.length} 个隐藏列将取消隐藏并导入数据`);
      }
      if ((p.ignoredSheets || []).length > 0) {
        items.push(`忽略 sheet：${p.ignoredSheets.join('、')}`);
      }
      if ((p.skippedHeaders || []).length > 0) {
        items.push(`跳过表头：${p.skippedHeaders.map((h) => `${h.headerName}（${h.reason}）`).join('、')}`);
      }
      return items;
    });

    /** 确认可用：全部基础列校验通过（空=接受）且无链接解析失败（强失败零写入门禁：
     *  21/25 列失败无法补参，须取消并修正文件后重新预检） */
    const canConfirm = computed(() => basicGroups.every((g) => !g.error)
      && linkFailures.value.length === 0);

    /** 组装阶段二补参 JSON（按 ExcelImportParams 契约与 KTD5 发射规则） */
    const buildParamsPayload = (): ExcelImportParamsPayload => {
      const p = props.precheck;
      // columnValues：只对基础列/违规列缺参发射（空串=显式补空）
      const columnValues: Record<string, string> = {};
      for (const g of basicGroups) {
        columnValues[g.columnName] = g.value ?? '';
      }
      // relationSelections：每个选择器分组（含歧义预选被清空者）都发射，未选编码 recordId:null
      const relationSelections: { columnName: string; name: string; recordId: number | null }[] = [];
      for (const col of relationColumns) {
        for (const g of col.groups) {
          relationSelections.push({
            columnName: col.columnName,
            name: g.name,
            recordId: g.selectedId ?? null,
          });
        }
      }
      for (const g of ambiguityGroups) {
        relationSelections.push({
          columnName: g.columnName,
          name: g.name,
          recordId: g.selectedId ?? null,
        });
      }
      // precheckBaseline：从预检结果构建（反向漂移 fail-fast 比对基线）
      const missingColumns: string[] = [];
      const missNames: { columnName: string; name: string }[] = [];
      for (const m of p.missingParams || []) {
        if (!missingColumns.includes(m.columnName)) missingColumns.push(m.columnName);
        if (m.kind === '关联未命中') {
          for (const n of m.missNames || []) {
            missNames.push({ columnName: m.columnName, name: n.name });
          }
        }
      }
      return {
        fileFingerprint: p.fileFingerprint,
        precheckBaseline: {
          missingColumns,
          missNames,
          ambiguity: (p.ambiguityItems || []).map((a) => ({
            columnName: a.columnName,
            name: a.name,
            preselectedRecordId: a.preselectedRecordId ?? null,
          })),
          // 替换基线：预检时将删除/新建的列（漏发会被后端漂移拒绝——契约一致性关键）
          replaceDeletedColumns: (p.replaceImpact?.deletedColumns || []).map((c) => c.columnName),
          replaceNewColumns: p.replaceImpact?.newColumnNames || [],
        },
        columnValues,
        relationSelections,
      };
    };

    /** 关闭并终止（无写入） */
    const cancel = () => {
      if (state.loading) return;
      state.visible = false;
    };

    /**
     * 确认导入：组装补参 JSON → importExcelData。
     * 弹框保持打开直至返回——成功销毁弹框并通知父组件刷新表格。
     * 失败分两类（#12）：数据漂移（"数据已变化"fail-fast，基线已失效，保留弹框重试
     * 必然再失败）→ 关闭弹框并引导用户重新选文件预检；其他失败 Modal.error 显示 msg，
     * 弹框保留已填全部补参（用户可取消或修改后重试）。
     */
    const confirm = async () => {
      if (state.loading || !canConfirm.value) return;
      state.loading = true;
      try {
        const recordCount = await importExcelData(
          { noteId: props.noteId, dwtableId: props.dwtableId, file: props.file },
          buildParamsPayload(),
        );
        state.visible = false;
        Modal.success({ title: '导入成功', content: `已替换导入 ${recordCount} 条记录` });
        ctx.emit('success', recordCount);
      } catch (e: any) {
        if (e?.message?.includes('数据已变化')) {
          state.visible = false;
          Modal.error({ title: '导入失败', content: '数据已变化，请重新选择文件进行预检' });
        } else {
          Modal.error({ title: '导入失败', content: e?.message || '导入失败，请稍后重试' });
        }
      } finally {
        state.loading = false;
      }
    };

    return {
      state,
      basicGroups,
      relationColumns,
      ambiguityGroups,
      linkFailures,
      COLLAPSE_THRESHOLD,
      unhandledCount,
      visibleGroups,
      remainingUnhandledCount,
      toggleCollapse,
      markAllBlank,
      hasReplace,
      replaceLines,
      impactSummary,
      canConfirm,
      validateBasic,
      cancel,
      confirm,
    };
  },
});
</script>

<style scoped lang="scss">
.excel-precheck-body {
  max-height: 55vh;
  overflow: auto;
  padding: 0 4px;
}

.precheck-divider {
  margin-top: 8px;

  &:first-child {
    margin-top: 0;
  }
}

.precheck-form-row {
  margin-bottom: 12px;
}

.precheck-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.precheck-name {
  font-weight: 500;
}

.precheck-hint {
  color: #999;
  font-size: 12px;
}

.precheck-error {
  color: #ff4d4f;
  font-size: 12px;
  margin-top: 2px;
}

.precheck-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.precheck-filter {
  width: 180px;
}

.precheck-impact {
  margin: 0;
  padding-left: 20px;
  color: #555;

  li {
    margin-bottom: 4px;
    line-height: 1.6;
  }
}
</style>