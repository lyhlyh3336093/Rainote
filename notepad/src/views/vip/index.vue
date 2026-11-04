<template>
  <div class="vip-page">
    <!-- 状态卡：VIP（金色标识+到期+权益）/ 管理员 / 普通用户（权益对比表） -->
    <a-card class="status-card" :bordered="false">
      <a-spin :spinning="loading">
        <!-- VIP 会员 -->
        <div v-if="info?.status?.vip" class="vip-header">
          <div class="vip-crown">VIP</div>
          <div class="vip-meta">
            <div class="vip-title">VIP 会员</div>
            <div class="vip-expire">到期时间：{{ info.status.expireTime || '-' }}</div>
          </div>
          <div class="vip-benefits">
            <a-tag v-for="b in vipBenefits" :key="b" color="gold">{{ b }}</a-tag>
          </div>
        </div>
        <!-- 管理员：豁免所有限额，无需充值 -->
        <div v-else-if="info?.status?.admin" class="vip-header">
          <div class="admin-crown">ADMIN</div>
          <div class="vip-meta">
            <div class="vip-title">管理员账号</div>
            <div class="vip-expire">豁免所有限额，无需开通 VIP</div>
          </div>
        </div>
        <!-- 普通用户：权益对比表 -->
        <template v-else>
          <div class="compare-title">升级 VIP，解锁全部高级能力</div>
          <table class="compare-table">
            <thead>
              <tr>
                <th style="width: 40%">权益</th>
                <th style="width: 30%">普通用户</th>
                <th style="width: 30%">VIP 会员</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in compareRows" :key="row.benefit">
                <td>{{ row.benefit }}</td>
                <td>{{ row.normal }}</td>
                <td class="vip-col">{{ row.vip }}</td>
              </tr>
            </tbody>
          </table>
          <div class="compare-note">* VIP 配额数值以站点实际配置为准</div>
        </template>
      </a-spin>
    </a-card>

    <!-- 档位卡片列表（admin 豁免无需充值，不展示） -->
    <template v-if="!info?.status?.admin">
      <div class="section-title">选择充值档位</div>
      <div class="tier-list">
        <a-card
          v-for="tier in info?.tiers || []"
          :key="tier.id"
          class="tier-card"
          hoverable
          @click="openQrModal(tier)"
        >
          <div class="tier-name">{{ tier.name }}</div>
          <div class="tier-days">{{ tier.days }} 天</div>
          <div class="tier-price">¥{{ tier.price }}</div>
          <a-button type="primary" ghost size="small">扫码支付</a-button>
        </a-card>
        <a-empty v-if="!loading && !(info?.tiers || []).length" description="暂无可充值档位" class="tier-empty" />
      </div>

      <!-- 提交表单 -->
      <a-card ref="submitCard" class="submit-card" :bordered="false" title="提交充值申请">
        <!-- 已有待审核申请：展示审核中状态并禁用提交 -->
        <a-alert
          v-if="pendingOrder"
          type="warning"
          show-icon
          message="审核中"
          :description="`您有待审核的充值申请（${pendingOrder.tierName}），请等待管理员处理后再提交新申请。`"
        />
        <a-form v-else class="submit-form" layout="vertical">
          <a-form-item label="充值档位" required>
            <a-select
              v-model:value="form.tierId"
              placeholder="请选择充值档位"
              :disabled="uploading"
            >
              <a-select-option v-for="tier in info?.tiers || []" :key="tier.id" :value="tier.id">
                {{ tier.name }}（{{ tier.days }}天 / ¥{{ tier.price }}）
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="支付凭证" required>
            <input
              ref="proofInput"
              type="file"
              accept="image/*"
              style="display: none"
              @change="handleProofSelect"
            />
            <div v-if="form.proofUrl" class="proof-preview">
              <img :src="proofPreviewUrl" alt="支付凭证" @click="previewProof" />
              <div class="proof-actions">
                <a-button size="small" :disabled="uploading" @click="resetProof">重新上传</a-button>
              </div>
            </div>
            <a-button v-else :loading="uploading" @click="proofInput?.click()">
              {{ uploading ? '上传中…' : '上传支付凭证（图片 ≤10MB）' }}
            </a-button>
            <div v-if="proofError" class="proof-error">{{ proofError }}</div>
          </a-form-item>
          <a-form-item label="备注（选填）">
            <a-textarea
              v-model:value="form.userRemark"
              :rows="2"
              :maxlength="200"
              placeholder="可填写付款时间、金额等信息，便于管理员核对"
            />
          </a-form-item>
          <a-button
            type="primary"
            :loading="submitting"
            :disabled="uploading || !form.tierId || !form.proofUrl"
            @click="handleSubmit"
          >
            提交申请
          </a-button>
        </a-form>
      </a-card>
    </template>

    <!-- 我的申请列表 -->
    <div class="section-title">我的申请</div>
    <a-table
      rowKey="id"
      :columns="orderColumns"
      :dataSource="orders"
      :loading="ordersLoading"
      :pagination="orderPagination"
      size="middle"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.dataIndex === 'amount'">
          <span>¥{{ text }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'proofUrl'">
          <img
            v-if="proofImages[record.id]"
            :src="proofImages[record.id]"
            class="proof-thumb"
            alt="支付凭证"
            @click="previewOrderProof(record)"
          />
          <span v-else>—</span>
        </template>
        <template v-else-if="column.dataIndex === 'status'">
          <a-tag :color="statusColor[record.status]">{{ statusText[record.status] || record.status }}</a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'adminRemark'">
          <span v-if="record.status === '2' && record.adminRemark" class="reject-remark">
            驳回留言：{{ record.adminRemark }}
          </span>
          <span v-else>{{ record.adminRemark || '—' }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <a-button
            v-if="record.status === '2'"
            type="link"
            size="small"
            @click="resubmit(record)"
          >
            重新提交
          </a-button>
          <span v-else>—</span>
        </template>
      </template>
    </a-table>

    <!-- 扫码支付弹窗：微信/支付宝 tab 切换收款码 -->
    <a-modal
      v-model:open="qrVisible"
      :title="qrTier ? `扫码支付 — ${qrTier.name}（¥${qrTier.price}）` : '扫码支付'"
      :footer="null"
      centered
      destroyOnClose
    >
      <a-tabs v-model:activeKey="qrChannel">
        <a-tab-pane key="wechat" tab="微信支付">
          <div class="qr-box">
            <img v-if="qrUrls.wechat" :src="qrUrls.wechat" alt="微信收款码" />
            <div v-else class="qr-placeholder">管理员未上传收款码</div>
          </div>
        </a-tab-pane>
        <a-tab-pane key="alipay" tab="支付宝">
          <div class="qr-box">
            <img v-if="qrUrls.alipay" :src="qrUrls.alipay" alt="支付宝收款码" />
            <div v-else class="qr-placeholder">管理员未上传收款码</div>
          </div>
        </a-tab-pane>
      </a-tabs>
      <div class="qr-tip">请使用对应 App 扫码支付，支付完成后上传凭证提交申请</div>
      <div class="qr-actions">
        <a-button type="primary" @click="confirmPaid">我已支付，去上传凭证</a-button>
      </div>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { API_BASE } from '../../config/api';
import {
  getVipInfo,
  uploadProof,
  submitOrder,
  listMyOrders,
  fetchProofImage,
  type VipInfo,
  type VipOrder,
  type VipTier,
} from '../../api/vip';

const loading = ref(false);
const info = ref<VipInfo | null>(null);
const pendingOrder = computed(() => info.value?.pendingOrder || null);

// ===== 提交表单 =====
const submitCard = ref();
const proofInput = ref<HTMLInputElement>();
const uploading = ref(false);
const submitting = ref(false);
const proofError = ref('');
const proofPreviewUrl = ref('');
const form = reactive({
  tierId: null as number | null,
  proofUrl: '',
  payChannel: 'wechat',
  userRemark: '',
});

// ===== 扫码弹窗 =====
const qrVisible = ref(false);
const qrTier = ref<VipTier | null>(null);
const qrChannel = ref<'wechat' | 'alipay'>('wechat');
// 收款码存于 /profile 静态映射（GET permitAll），直接拼 API_BASE 展示
const qrUrls = computed(() => ({
  wechat: info.value?.wechatQr ? `${API_BASE}${info.value.wechatQr}` : '',
  alipay: info.value?.alipayQr ? `${API_BASE}${info.value.alipayQr}` : '',
}));

const openQrModal = (tier: VipTier) => {
  qrTier.value = tier;
  qrVisible.value = true;
};

// "我已支付"：关闭弹窗，带入所选档位与支付渠道进入提交表单
const confirmPaid = () => {
  if (qrTier.value) {
    form.tierId = qrTier.value.id;
  }
  form.payChannel = qrChannel.value;
  qrVisible.value = false;
  submitCard.value?.$el?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
};

// 驳回记录"重新提交"：回填原档位与备注进入提交流程
const resubmit = (order: VipOrder) => {
  const tierExists = (info.value?.tiers || []).some(t => t.id === order.tierId);
  form.tierId = tierExists ? order.tierId : null;
  form.userRemark = order.userRemark || '';
  form.payChannel = order.payChannel === 'alipay' ? 'alipay' : 'wechat';
  submitCard.value?.$el?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
};

// ===== 凭证上传（前端预校验 ≤10MB 且图片类型；上传中禁用提交；失败内联报错） =====
const PROOF_MAX_BYTES = 10 * 1024 * 1024;

const handleProofSelect = async (event: Event) => {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0];
  target.value = ''; // 清空选择，允许重复选择同一文件
  if (!file) return;

  proofError.value = '';
  if (!file.type.startsWith('image/')) {
    proofError.value = '凭证仅支持图片格式';
    return;
  }
  if (file.size > PROOF_MAX_BYTES) {
    proofError.value = '凭证图片不能超过 10MB';
    return;
  }

  uploading.value = true;
  try {
    const res = await uploadProof(file);
    if (res.code === 200 && res.proofUrl) {
      if (proofPreviewUrl.value) URL.revokeObjectURL(proofPreviewUrl.value);
      proofPreviewUrl.value = URL.createObjectURL(file);
      form.proofUrl = res.proofUrl;
    } else {
      proofError.value = res.msg || '凭证上传失败，请重试';
    }
  } catch {
    proofError.value = '网络异常，凭证上传失败，请重试';
  } finally {
    uploading.value = false;
  }
};

const resetProof = () => {
  if (proofPreviewUrl.value) URL.revokeObjectURL(proofPreviewUrl.value);
  proofPreviewUrl.value = '';
  form.proofUrl = '';
  proofError.value = '';
};

const previewProof = () => {
  window.open(proofPreviewUrl.value, '_blank');
};

// ===== 提交申请 =====
const handleSubmit = async () => {
  if (!form.tierId || !form.proofUrl || submitting.value) return;
  submitting.value = true;
  try {
    const res = await submitOrder({
      tierId: form.tierId,
      proofUrl: form.proofUrl,
      payChannel: form.payChannel,
      userRemark: form.userRemark || undefined,
    });
    if (res.code === 200) {
      message.success('充值申请已提交，请等待管理员审核');
      resetProof();
      form.tierId = null;
      form.userRemark = '';
      await Promise.all([loadInfo(), loadOrders(1)]);
    } else {
      message.error(res.msg || '提交失败，请稍后重试');
    }
  } catch {
    message.error('网络异常，提交失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
};

// ===== 我的申请列表 =====
const orders = ref<VipOrder[]>([]);
const ordersLoading = ref(false);
const orderPage = reactive({ pageNum: 1, pageSize: 10, total: 0 });
const orderPagination = computed(() => ({
  total: orderPage.total,
  current: orderPage.pageNum,
  pageSize: orderPage.pageSize,
  showTotal: (total: number) => `共 ${total} 条`,
  showSizeChanger: true,
  onChange(page: number, pageSize: number) {
    orderPage.pageNum = page;
    orderPage.pageSize = pageSize;
    loadOrders(page);
  },
}));
const statusText: Record<string, string> = { '0': '待审核', '1': '已开通', '2': '已驳回' };
const statusColor: Record<string, string> = { '0': 'orange', '1': 'gold', '2': 'red' };
const orderColumns = [
  { title: '档位', dataIndex: 'tierName', key: 'tierName' },
  { title: '金额', dataIndex: 'amount', key: 'amount' },
  { title: '支付方式', dataIndex: 'payChannel', key: 'payChannel' },
  { title: '凭证', dataIndex: 'proofUrl', key: 'proofUrl' },
  { title: '备注', dataIndex: 'userRemark', key: 'userRemark' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '管理员留言', dataIndex: 'adminRemark', key: 'adminRemark' },
  { title: '申请时间', dataIndex: 'createTime', key: 'createTime' },
  { title: '操作', dataIndex: 'action', key: 'action' },
];

// 凭证缩略图：经鉴权下载端点取 blob 转 objectURL（KTD12，私有目录不可静态访问）
const proofImages = reactive<Record<number, string>>({});

const loadOrders = async (pageNum = orderPage.pageNum) => {
  ordersLoading.value = true;
  try {
    const res = await listMyOrders(pageNum, orderPage.pageSize);
    orders.value = res.rows || [];
    orderPage.total = res.total || 0;
    orderPage.pageNum = pageNum;
    // 逐行加载凭证缩略图（失败不阻塞列表展示）
    for (const row of orders.value) {
      if (row.proofUrl && !proofImages[row.id]) {
        fetchProofImage(row.proofUrl)
          .then(url => { proofImages[row.id] = url; })
          .catch(() => { /* 凭证加载失败仅不显示缩略图 */ });
      }
    }
  } catch {
    message.error('申请列表加载失败，请稍后重试');
  } finally {
    ordersLoading.value = false;
  }
};

const previewOrderProof = (record: VipOrder) => {
  const url = proofImages[record.id];
  if (url) window.open(url, '_blank');
};

// ===== 状态卡数据 =====
const formatBytes = (bytes: number): string => {
  if (!bytes || bytes <= 0) return '-';
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(0)} GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(0)} MB`;
  return `${(bytes / 1024).toFixed(0)} KB`;
};

const vipBenefits = computed(() => {
  const s = info.value?.status;
  if (!s) return [];
  return [
    s.noteLimit < 0 ? '笔记不限' : `笔记 ${s.noteLimit} 篇`,
    `单文件 ${formatBytes(s.fileMaxBytes)}`,
    `总容量 ${formatBytes(s.totalMaxBytes)}`,
    `AI ${s.aiDailyLimit} 次/日`,
    `回收站 ${s.recycleDays} 天`,
  ];
});

const compareRows = computed(() => {
  const s = info.value?.status;
  if (!s) return [];
  return [
    { benefit: '笔记创建数', normal: s.noteLimit < 0 ? '不限' : `${s.noteLimit} 篇`, vip: '不限' },
    { benefit: '单文件上传上限', normal: formatBytes(s.fileMaxBytes), vip: '512 MB' },
    { benefit: '附件总容量', normal: formatBytes(s.totalMaxBytes), vip: '10 GB' },
    { benefit: 'AI 对话', normal: `${s.aiDailyLimit} 次/日`, vip: '100 次/日' },
    { benefit: '回收站保留', normal: `${s.recycleDays} 天`, vip: '30 天' },
    { benefit: '高级列（双向关联/集合运算/语义关联/lookUp）', normal: '—', vip: '✓' },
    { benefit: 'AI Agent', normal: '—', vip: '✓' },
    { benefit: '笔记共享创建', normal: '—', vip: '✓' },
    { benefit: '个人数据导出', normal: '—', vip: '✓' },
  ];
});

const loadInfo = async () => {
  loading.value = true;
  try {
    info.value = await getVipInfo();
  } catch {
    message.error('VIP 信息加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  loadInfo();
  loadOrders(1);
});

onUnmounted(() => {
  // 释放凭证预览/缩略图的 objectURL，避免内存泄漏
  if (proofPreviewUrl.value) URL.revokeObjectURL(proofPreviewUrl.value);
  Object.values(proofImages).forEach(url => URL.revokeObjectURL(url));
});
</script>

<style scoped lang="scss">
.vip-page {
  padding: 16px;
  max-width: 1100px;
  margin: 0 auto;
}

.status-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.vip-header {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.vip-crown {
  min-width: 64px;
  padding: 8px 12px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f7c948, #e8a33d);
  color: #fff;
  font-weight: 700;
  font-size: 20px;
  text-align: center;
  letter-spacing: 1px;
  box-shadow: 0 2px 8px rgba(232, 163, 61, 0.4);
}

.admin-crown {
  min-width: 64px;
  padding: 8px 12px;
  border-radius: 8px;
  background: linear-gradient(135deg, #1677ff, #4096ff);
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  text-align: center;
  letter-spacing: 1px;
}

.vip-meta {
  .vip-title {
    font-size: 18px;
    font-weight: 600;
  }

  .vip-expire {
    color: #8c8c8c;
    font-size: 13px;
    margin-top: 4px;
  }
}

.vip-benefits {
  margin-left: auto;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.compare-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}

.compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  th,
  td {
    border: 1px solid #f0f0f0;
    padding: 8px 12px;
    text-align: left;
  }

  th {
    background: #fafafa;
    font-weight: 600;
  }

  .vip-col {
    color: #d48806;
    font-weight: 600;
  }
}

.compare-note {
  color: #bfbfbf;
  font-size: 12px;
  margin-top: 8px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin: 16px 0 12px;
}

.tier-list {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.tier-card {
  width: 180px;
  text-align: center;
  border-radius: 8px;

  .tier-name {
    font-size: 15px;
    font-weight: 600;
  }

  .tier-days {
    color: #8c8c8c;
    font-size: 13px;
    margin: 6px 0;
  }

  .tier-price {
    color: #d48806;
    font-size: 22px;
    font-weight: 700;
    margin-bottom: 12px;
  }
}

.tier-empty {
  width: 100%;
}

.submit-card {
  border-radius: 8px;
}

.proof-preview {
  display: flex;
  align-items: center;
  gap: 12px;

  img {
    width: 96px;
    height: 96px;
    object-fit: cover;
    border-radius: 6px;
    border: 1px solid #f0f0f0;
    cursor: zoom-in;
  }
}

.proof-error {
  color: #ff4d4f;
  font-size: 12px;
  margin-top: 6px;
}

.proof-thumb {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid #f0f0f0;
  cursor: zoom-in;
}

.reject-remark {
  color: #ff4d4f;
}

.qr-box {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;

  img {
    max-width: 240px;
    max-height: 240px;
    border: 1px solid #f0f0f0;
    border-radius: 6px;
  }

  .qr-placeholder {
    color: #bfbfbf;
    padding: 40px;
    border: 1px dashed #d9d9d9;
    border-radius: 6px;
  }
}

.qr-tip {
  color: #8c8c8c;
  font-size: 12px;
  text-align: center;
  margin: 8px 0 16px;
}

.qr-actions {
  text-align: center;
}
</style>
