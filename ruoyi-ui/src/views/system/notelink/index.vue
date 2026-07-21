<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="笔记内容" prop="contextText">
        <el-input
          v-model="queryParams.contextText"
          placeholder="请输入笔记内容"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="所属笔记id" prop="noteId">
        <el-input
          v-model="queryParams.noteId"
          placeholder="请输入所属笔记id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="所属块id" prop="blockId">
        <el-input
          v-model="queryParams.blockId"
          placeholder="请输入所属块id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="关联多维表格id" prop="linkNoteId">
        <el-input
          v-model="queryParams.linkNoteId"
          placeholder="请输入关联多维表格id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="关联数据表id" prop="linkDwTableId">
        <el-input
          v-model="queryParams.linkDwTableId"
          placeholder="请输入关联数据表id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="关联行id" prop="linkRecordId">
        <el-input
          v-model="queryParams.linkRecordId"
          placeholder="请输入关联行id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="关联列id" prop="linkColumnId">
        <el-input
          v-model="queryParams.linkColumnId"
          placeholder="请输入关联列id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="关联单元格id" prop="linkItemId">
        <el-input
          v-model="queryParams.linkItemId"
          placeholder="请输入关联单元格id"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="单元格显示内容" prop="itemValue">
        <el-input
          v-model="queryParams.itemValue"
          placeholder="请输入单元格显示内容"
          clearable
          @keyup.enter.native="handleQuery"
        />
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
          v-hasPermi="['system:notelink:add']"
        >新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['system:notelink:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['system:notelink:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['system:notelink:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="notelinkList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="笔记链接主键" align="center" prop="id" />
      <el-table-column label="笔记内容" align="center" prop="contextText" />
      <el-table-column label="所属笔记id" align="center" prop="noteId" />
      <el-table-column label="所属块id" align="center" prop="blockId" />
      <el-table-column label="关联多维表格id" align="center" prop="linkNoteId" />
      <el-table-column label="关联数据表id" align="center" prop="linkDwTableId" />
      <el-table-column label="关联行id" align="center" prop="linkRecordId" />
      <el-table-column label="关联列id" align="center" prop="linkColumnId" />
      <el-table-column label="关联单元格id" align="center" prop="linkItemId" />
      <el-table-column label="单元格显示内容" align="center" prop="itemValue" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:notelink:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:notelink:remove']"
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

    <!-- 添加或修改笔记链接对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="笔记内容" prop="contextText">
          <el-input v-model="form.contextText" placeholder="请输入笔记内容" />
        </el-form-item>
        <el-form-item label="所属笔记id" prop="noteId">
          <el-input v-model="form.noteId" placeholder="请输入所属笔记id" />
        </el-form-item>
        <el-form-item label="所属块id" prop="blockId">
          <el-input v-model="form.blockId" placeholder="请输入所属块id" />
        </el-form-item>
        <el-form-item label="关联多维表格id" prop="linkNoteId">
          <el-input v-model="form.linkNoteId" placeholder="请输入关联多维表格id" />
        </el-form-item>
        <el-form-item label="关联数据表id" prop="linkDwTableId">
          <el-input v-model="form.linkDwTableId" placeholder="请输入关联数据表id" />
        </el-form-item>
        <el-form-item label="关联行id" prop="linkRecordId">
          <el-input v-model="form.linkRecordId" placeholder="请输入关联行id" />
        </el-form-item>
        <el-form-item label="关联列id" prop="linkColumnId">
          <el-input v-model="form.linkColumnId" placeholder="请输入关联列id" />
        </el-form-item>
        <el-form-item label="关联单元格id" prop="linkItemId">
          <el-input v-model="form.linkItemId" placeholder="请输入关联单元格id" />
        </el-form-item>
        <el-form-item label="单元格显示内容" prop="itemValue">
          <el-input v-model="form.itemValue" placeholder="请输入单元格显示内容" />
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
import { listNotelink, getNotelink, delNotelink, addNotelink, updateNotelink } from "@/api/system/notelink";

export default {
  name: "Notelink",
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 笔记链接表格数据
      notelinkList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        contextText: null,
        noteId: null,
        blockId: null,
        linkNoteId: null,
        linkDwTableId: null,
        linkRecordId: null,
        linkColumnId: null,
        linkItemId: null,
        itemValue: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
      }
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询笔记链接列表 */
    getList() {
      this.loading = true;
      listNotelink(this.queryParams).then(response => {
        this.notelinkList = response.rows;
        this.total = response.total;
        this.loading = false;
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
        id: null,
        contextText: null,
        noteId: null,
        blockId: null,
        linkNoteId: null,
        linkDwTableId: null,
        linkRecordId: null,
        linkColumnId: null,
        linkItemId: null,
        itemValue: null
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
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length!==1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加笔记链接";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getNotelink(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改笔记链接";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateNotelink(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addNotelink(this.form).then(response => {
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
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除笔记链接编号为"' + ids + '"的数据项？').then(function() {
        return delNotelink(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('system/notelink/export', {
        ...this.queryParams
      }, `notelink_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>
