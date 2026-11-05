<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="用户名" prop="userName">
        <el-input
          v-model="queryParams.userName"
          placeholder="请输入用户名"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="memberList">
      <el-table-column label="用户ID" align="center" prop="userId" width="80" />
      <el-table-column label="账号" align="center" prop="userName" :show-overflow-tooltip="true" />
      <el-table-column label="昵称" align="center" prop="nickName" :show-overflow-tooltip="true" />
      <el-table-column label="VIP状态" align="center" width="220">
        <template slot-scope="scope">
          <el-tag v-if="isVipActive(scope.row)" class="vip-tag">VIP</el-tag>
          <el-tag v-else type="info">普通</el-tag>
          <div v-if="scope.row.expireTime" class="expire-time">
            {{ isVipActive(scope.row) ? "到期：" : "过期：" }}{{ parseTime(scope.row.expireTime) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" align="center" width="160">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.updateTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="200" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-time"
            @click="handleAdjust(scope.row)"
            v-hasPermi="['system:vip:member:adjust']"
          >调整到期</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-document"
            @click="handleViewOrders(scope.row)"
            v-hasPermi="['system:vip:order:list']"
          >申请记录</el-button>
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

    <!-- 调整会员到期时间对话框 -->
    <el-dialog title="调整会员到期时间" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="用户">
          {{ adjustRow.userName }}（ID：{{ adjustRow.userId }}）
        </el-form-item>
        <el-form-item label="当前到期">
          <span v-if="adjustRow.expireTime">{{ parseTime(adjustRow.expireTime) }}</span>
          <span v-else>非会员</span>
        </el-form-item>
        <el-form-item label="新到期时间" prop="expireTime">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            value-format="timestamp"
            placeholder="选择到期时间"
            style="width: 100%"
          ></el-date-picker>
          <div class="adjust-tip">提示：可将到期时间设为过去时间，即时将用户降级为普通会员</div>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listMembers, adjustMember } from "@/api/system/vip";

export default {
  name: "VipMember",
  data() {
    return {
      // 遮罩层
      loading: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 会员表格数据
      memberList: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        userName: undefined
      },
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 当前调整的用户
      adjustRow: {},
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        expireTime: [
          { required: true, message: "到期时间不能为空", trigger: "change" }
        ]
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询会员列表 */
    getList() {
      this.loading = true;
      listMembers(this.queryParams).then(response => {
        this.memberList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    /** 是否为有效VIP（到期时间在当前之后；后端返回时间戳或格式化字符串均可比较） */
    isVipActive(row) {
      return row.expireTime != null && new Date(row.expireTime).getTime() > Date.now();
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    /** 调整到期时间按钮操作 */
    handleAdjust(row) {
      this.adjustRow = row;
      this.resetForm("form");
      this.form = {
        userId: row.userId,
        // value-format="timestamp"：后端 VipMembership.expireTime 为无 @JsonFormat 的 Date 字段，
        // 以时间戳提交（默认 Jackson 可直接反序列化为 Date）
        expireTime: row.expireTime ? new Date(row.expireTime).getTime() : undefined
      };
      this.open = true;
      this.title = "调整会员到期时间";
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 表单重置
    reset() {
      this.form = {
        userId: undefined,
        expireTime: undefined
      };
      this.resetForm("form");
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          adjustMember({ userId: this.form.userId, expireTime: this.form.expireTime }).then(() => {
            this.$modal.msgSuccess("调整成功");
            this.open = false;
            this.getList();
          });
        }
      });
    },
    /** 申请记录按钮操作：跳转充值审核页并按用户名筛选 */
    handleViewOrders(row) {
      this.$router.push({ name: "VipOrder", query: { userName: row.userName } }).catch(() => {});
    }
  }
};
</script>

<style scoped>
.vip-tag {
  background-color: #f7e6b2;
  border-color: #e6c07b;
  color: #b88230;
  font-weight: bold;
}
.expire-time {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.adjust-tip {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
}
</style>
