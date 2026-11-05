<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="订单状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="订单状态" clearable style="width: 160px">
          <el-option
            v-for="item in statusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="用户名" prop="userName">
        <el-input
          v-model="queryParams.userName"
          placeholder="请输入用户名"
          clearable
          style="width: 160px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="申请时间">
        <el-date-picker
          v-model="dateRange"
          style="width: 240px"
          value-format="yyyy-MM-dd"
          type="daterange"
          range-separator="-"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        ></el-date-picker>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="orderList">
      <el-table-column label="订单ID" align="center" prop="id" width="80" />
      <el-table-column label="用户名" align="center" prop="userName" :show-overflow-tooltip="true" />
      <el-table-column label="充值档位" align="center" width="150">
        <template slot-scope="scope">
          <span>{{ scope.row.tierName }}（{{ scope.row.tierDays }}天）</span>
        </template>
      </el-table-column>
      <el-table-column label="金额(元)" align="center" prop="amount" width="100" />
      <el-table-column label="支付渠道" align="center" width="100">
        <template slot-scope="scope">
          <span>{{ channelLabel(scope.row.payChannel) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="支付凭证" align="center" width="90">
        <template slot-scope="scope">
          <el-image
            v-if="proofUrls[scope.row.id]"
            :src="proofUrls[scope.row.id]"
            :preview-src-list="[proofUrls[scope.row.id]]"
            fit="cover"
            style="width: 40px; height: 40px; border-radius: 4px"
          />
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="用户备注" align="center" prop="userRemark" :show-overflow-tooltip="true" />
      <el-table-column label="状态" align="center" width="90">
        <template slot-scope="scope">
          <el-tag :type="statusTag(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="管理员留言" align="center" prop="adminRemark" :show-overflow-tooltip="true" />
      <el-table-column label="申请时间" align="center" width="160">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="110" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            v-if="scope.row.status === '0'"
            size="mini"
            type="text"
            icon="el-icon-s-check"
            @click="handleAudit(scope.row)"
            v-hasPermi="['system:vip:order:audit']"
          >审核</el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 充值审核对话框 -->
    <el-dialog title="充值审核" :visible.sync="auditOpen" width="560px" append-to-body>
      <div class="order-summary">
        <el-row>
          <el-col :span="12"><span class="summary-label">订单ID</span>{{ auditRow.id }}</el-col>
          <el-col :span="12"><span class="summary-label">申请时间</span>{{ parseTime(auditRow.createTime) }}</el-col>
        </el-row>
        <el-row>
          <el-col :span="12"><span class="summary-label">用户名</span>{{ auditRow.userName }}</el-col>
          <el-col :span="12"><span class="summary-label">支付渠道</span>{{ channelLabel(auditRow.payChannel) }}</el-col>
        </el-row>
        <el-row>
          <el-col :span="12"><span class="summary-label">充值档位</span>{{ auditRow.tierName }}（{{ auditRow.tierDays }}天）</el-col>
          <el-col :span="12"><span class="summary-label">金额</span>￥{{ auditRow.amount }}</el-col>
        </el-row>
        <el-row v-if="auditRow.userRemark">
          <el-col :span="24"><span class="summary-label">用户备注</span>{{ auditRow.userRemark }}</el-col>
        </el-row>
      </div>
      <el-form ref="auditForm" :model="auditForm" :rules="auditRules" label-width="90px">
        <el-form-item label="支付凭证">
          <el-image
            v-if="proofUrls[auditRow.id]"
            :src="proofUrls[auditRow.id]"
            :preview-src-list="[proofUrls[auditRow.id]]"
            fit="contain"
            style="width: 160px; height: 160px; border: 1px solid #dcdfe6; border-radius: 4px"
          />
          <span v-else>凭证加载失败，请联系管理员核对</span>
        </el-form-item>
        <el-form-item label="管理员留言" prop="adminRemark">
          <el-input
            v-model="auditForm.adminRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="auditRejecting ? '请填写驳回原因（用户可见）' : '选填'"
          />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="success" @click="handleApprove" v-hasPermi="['system:vip:order:audit']">通 过</el-button>
        <el-button type="danger" @click="handleReject" v-hasPermi="['system:vip:order:audit']">驳 回</el-button>
        <el-button @click="cancelAudit">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listOrders, auditOrder, getProofImage } from "@/api/system/vip";
import { blobValidate } from "@/utils/ruoyi";

export default {
  name: "VipOrder",
  data() {
    return {
      // 遮罩层
      loading: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 订单表格数据
      orderList: [],
      // 订单状态选项（'0'待审核 '1'已开通 '2'已驳回）
      statusOptions: [
        { value: "0", label: "待审核" },
        { value: "1", label: "已开通" },
        { value: "2", label: "已驳回" }
      ],
      // 支付渠道选项
      channelOptions: [
        { value: "wechat", label: "微信支付" },
        { value: "alipay", label: "支付宝支付" }
      ],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        status: "0",
        userName: undefined
      },
      // 申请时间范围
      dateRange: [],
      // 凭证缩略图（订单ID → blob objectURL）
      proofUrls: {},
      // 审核弹窗
      auditOpen: false,
      // 当前审核的订单
      auditRow: {},
      // 审核表单
      auditForm: {},
      // 是否处于驳回模式（控制留言必填校验）
      auditRejecting: false
    };
  },
  computed: {
    // 驳回时留言必填
    auditRules() {
      return {
        adminRemark: this.auditRejecting
          ? [{ required: true, message: "驳回时必须填写留言说明原因", trigger: ["blur", "change"] }]
          : []
      };
    }
  },
  created() {
    // 会员管理页"申请记录"跳转携带的用户名筛选
    if (this.$route.query.userName) {
      this.queryParams.userName = this.$route.query.userName;
    }
    this.getList();
  },
  activated() {
    // keep-alive 缓存下，从会员管理"申请记录"再次跳转时刷新筛选
    const userName = this.$route.query.userName;
    if (userName && userName !== this.queryParams.userName) {
      this.queryParams.userName = userName;
      this.handleQuery();
    }
  },
  beforeDestroy() {
    this.resetProofUrls();
  },
  methods: {
    /** 查询订单列表 */
    getList() {
      this.loading = true;
      this.resetProofUrls();
      // 结束日期补到当日 23:59:59，避免当天记录被 00:00:00 边界排除
      const range = this.dateRange && this.dateRange.length === 2
        ? [this.dateRange[0], this.dateRange[1] + " 23:59:59"]
        : [];
      listOrders(this.addDateRange(this.queryParams, range)).then(response => {
        this.orderList = response.rows;
        this.total = response.total;
        this.loading = false;
        this.loadProofs();
      });
    },
    /** 凭证走鉴权下载端点，转 objectURL 供缩略图与大图预览 */
    loadProofs() {
      this.orderList.forEach(row => {
        if (row.proofUrl) {
          getProofImage(row.proofUrl).then(data => {
            if (blobValidate(data)) {
              this.$set(this.proofUrls, row.id, URL.createObjectURL(data));
            }
          }).catch(() => {});
        }
      });
    },
    /** 释放已创建的凭证 objectURL */
    resetProofUrls() {
      Object.keys(this.proofUrls).forEach(key => {
        if (this.proofUrls[key]) {
          URL.revokeObjectURL(this.proofUrls[key]);
        }
      });
      this.proofUrls = {};
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.dateRange = [];
      this.resetForm("queryForm");
      this.handleQuery();
    },
    /** 订单状态标签样式 */
    statusTag(status) {
      return status === "1" ? "success" : status === "2" ? "danger" : "warning";
    },
    /** 订单状态标签文本 */
    statusLabel(status) {
      const option = this.statusOptions.find(item => item.value === status);
      return option ? option.label : "未知";
    },
    /** 支付渠道文本 */
    channelLabel(channel) {
      const option = this.channelOptions.find(item => item.value === channel);
      return option ? option.label : (channel || "-");
    },
    /** 审核按钮操作 */
    handleAudit(row) {
      this.auditRow = row;
      this.auditRejecting = false;
      this.resetForm("auditForm");
      this.auditForm = { adminRemark: "" };
      this.auditOpen = true;
    },
    /** 取消审核 */
    cancelAudit() {
      this.auditOpen = false;
      this.auditRejecting = false;
    },
    /** 通过（二次确认，防误触） */
    handleApprove() {
      const row = this.auditRow;
      this.$modal.confirm('确认通过该订单并为用户「' + row.userName + '」开通 ' + row.tierDays + ' 天VIP（金额￥' + row.amount + '）？').then(() => {
        return auditOrder({ id: row.id, approve: true, adminRemark: this.auditForm.adminRemark }).then(() => {
          this.$modal.msgSuccess("已通过并开通");
          this.auditOpen = false;
          this.getList();
        }).catch(() => {
          // 审核失败（如订单已被并发审核）时刷新列表同步最新状态
          this.auditOpen = false;
          this.getList();
        });
      }).catch(() => {});
    },
    /** 驳回（留言必填） */
    handleReject() {
      this.auditRejecting = true;
      this.$nextTick(() => {
        this.$refs["auditForm"].validate(valid => {
          if (valid) {
            auditOrder({ id: this.auditRow.id, approve: false, adminRemark: this.auditForm.adminRemark }).then(() => {
              this.$modal.msgSuccess("已驳回");
              this.auditOpen = false;
              this.getList();
            }).catch(() => {
              // 审核失败（如订单已被并发审核）时刷新列表同步最新状态
              this.auditOpen = false;
              this.getList();
            });
          }
        });
      });
    }
  }
};
</script>

<style scoped>
.order-summary {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 10px 15px;
  margin-bottom: 15px;
  font-size: 13px;
  line-height: 24px;
}
.order-summary .el-row:not(:last-child) {
  margin-bottom: 4px;
}
.summary-label {
  color: #909399;
  margin-right: 8px;
}
</style>
