<template>
  <div class="app-container">
    <!-- 档位管理 -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="档位名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入档位名称"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="档位状态" clearable style="width: 160px">
          <el-option
            v-for="dict in dict.type.sys_normal_disable"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['system:vip:tier:add']"
        >新增</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="tierList">
      <el-table-column label="档位ID" align="center" prop="id" width="80" />
      <el-table-column label="档位名称" align="center" prop="name" :show-overflow-tooltip="true" />
      <el-table-column label="时长(天)" align="center" prop="days" width="100" />
      <el-table-column label="价格(元)" align="center" prop="price" width="120" />
      <el-table-column label="排序" align="center" prop="sort" width="80" />
      <el-table-column label="状态" align="center" width="90">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_normal_disable" :value="scope.row.status"/>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="150" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:vip:tier:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:vip:tier:remove']"
          >删除</el-button>
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

    <!-- 收款码配置 -->
    <el-card class="box-card">
      <div slot="header" class="clearfix">
        <span>收款码配置</span>
        <span class="qr-header-tip">用户开通VIP时扫码支付，请上传微信/支付宝收款码图片</span>
      </div>
      <el-row :gutter="40">
        <el-col :span="12" v-for="channel in qrChannels" :key="channel.key">
          <div class="qr-card">
            <div class="qr-title">{{ channel.label }}</div>
            <el-image
              v-if="qrImages[channel.key]"
              :src="qrImages[channel.key]"
              :preview-src-list="[qrImages[channel.key]]"
              fit="contain"
              class="qr-img"
            />
            <div v-else class="qr-img qr-empty">
              <i class="el-icon-picture-outline qr-empty-icon"></i>
              <div>暂未上传收款码</div>
            </div>
            <el-upload
              action="#"
              :http-request="(opt) => doQrUpload(channel.key, opt.file)"
              :before-upload="beforeQrUpload"
              :show-file-list="false"
              accept="image/*"
            >
              <el-button
                size="mini"
                type="primary"
                icon="el-icon-upload2"
                :loading="qrUploading[channel.key]"
                v-hasPermi="['system:vip:tier:qrcode']"
              >{{ qrImages[channel.key] ? "重新上传" : "上传图片" }}</el-button>
            </el-upload>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 添加或修改档位对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="档位名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入档位名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="时长(天)" prop="days">
          <el-input-number v-model="form.days" :min="1" :precision="0" :step="1" controls-position="right" placeholder="请输入时长" style="width: 100%" />
        </el-form-item>
        <el-form-item label="价格(元)" prop="price">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" controls-position="right" placeholder="请输入价格" style="width: 100%" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :precision="0" :step="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio
              v-for="dict in dict.type.sys_normal_disable"
              :key="dict.value"
              :label="dict.value"
            >{{dict.label}}</el-radio>
          </el-radio-group>
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
import { listTiers, addTier, updateTier, deleteTier, getQrcode, uploadQrcode } from "@/api/system/vip";

export default {
  name: "VipSetting",
  dicts: ['sys_normal_disable'],
  data() {
    return {
      // 遮罩层
      loading: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 档位表格数据
      tierList: [],
      // 收款码渠道
      qrChannels: [
        { key: "wechat", label: "微信收款码" },
        { key: "alipay", label: "支付宝收款码" }
      ],
      // 收款码图片预览地址
      qrImages: { wechat: "", alipay: "" },
      // 收款码上传中状态
      qrUploading: { wechat: false, alipay: false },
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: undefined,
        status: undefined
      },
      // 表单参数
      form: {},
      // 表单校验（天数正整数/价格非负由 el-input-number 控件约束）
      rules: {
        name: [
          { required: true, message: "档位名称不能为空", trigger: "blur" }
        ],
        days: [
          { required: true, message: "档位时长不能为空", trigger: "blur" }
        ],
        price: [
          { required: true, message: "档位价格不能为空", trigger: "blur" }
        ]
      }
    };
  },
  created() {
    this.getList();
    this.getQrcodes();
  },
  methods: {
    /** 查询档位列表 */
    getList() {
      this.loading = true;
      listTiers(this.queryParams).then(response => {
        this.tierList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    /** 查询收款码配置 */
    getQrcodes() {
      getQrcode().then(response => {
        this.qrImages = {
          wechat: response.wechatQr ? process.env.VUE_APP_BASE_API + response.wechatQr : "",
          alipay: response.alipayQr ? process.env.VUE_APP_BASE_API + response.alipayQr : ""
        };
      });
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 表单重置
    reset() {
      this.form = {
        id: undefined,
        name: undefined,
        days: undefined,
        price: undefined,
        sort: 0,
        status: "0"
      };
      this.resetForm("form");
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
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加VIP档位";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      this.form = {
        id: row.id,
        name: row.name,
        days: row.days,
        price: row.price,
        sort: row.sort,
        status: row.status
      };
      this.open = true;
      this.title = "修改VIP档位";
    },
    /** 提交按钮 */
    submitForm: function() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != undefined) {
            updateTier(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addTier(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const tierId = row.id;
      this.$modal.confirm('是否确认删除VIP档位编号为"' + tierId + '"的数据项？').then(function() {
        return deleteTier(tierId);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 收款码上传前校验（图片类型限制） */
    beforeQrUpload(file) {
      const isImage = file.type && file.type.startsWith("image/");
      const isLt10M = file.size / 1024 / 1024 < 10;
      if (!isImage) {
        this.$modal.msgError("收款码仅支持上传图片格式文件");
        return false;
      }
      if (!isLt10M) {
        this.$modal.msgError("上传图片大小不能超过 10MB");
        return false;
      }
      return true;
    },
    /** 收款码上传 */
    doQrUpload(channel, file) {
      this.$set(this.qrUploading, channel, true);
      uploadQrcode(channel, file).then(() => {
        this.$modal.msgSuccess("收款码上传成功");
        this.getQrcodes();
      }).catch(() => {}).finally(() => {
        this.qrUploading[channel] = false;
      });
    }
  }
};
</script>

<style scoped>
.qr-header-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
.qr-card {
  text-align: center;
  padding: 10px 0;
}
.qr-title {
  font-weight: bold;
  margin-bottom: 10px;
}
.qr-img {
  width: 220px;
  height: 220px;
  margin: 0 auto 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  background-color: #f5f7fa;
}
.qr-empty {
  color: #c0c4cc;
  font-size: 13px;
  flex-direction: column;
}
.qr-empty-icon {
  font-size: 40px;
  margin-bottom: 8px;
  color: #dcdfe6;
}
</style>
