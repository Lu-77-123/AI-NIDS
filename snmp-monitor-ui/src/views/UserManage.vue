<template>
  <div class="user-manage-container">
    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" style="margin-bottom: 20px;">
      <el-col :span="8">
        <el-card shadow="never" class="stat-card stat-total">
          <div class="stat-value">{{ userList.length }}</div>
          <div class="stat-label">用户总数</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat-card stat-active">
          <div class="stat-value">{{ activeCount }}</div>
          <div class="stat-label">启用用户</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat-card stat-disabled">
          <div class="stat-value">{{ disabledCount }}</div>
          <div class="stat-label">禁用用户</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 搜索 + 新增 -->
    <el-card shadow="never" style="margin-bottom: 20px;">
      <el-form :inline="true" :model="searchForm" size="small">
        <el-form-item label="用户名">
          <el-input v-model="searchForm.username" placeholder="请输入用户名" clearable style="width: 160px;" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="searchForm.realName" placeholder="请输入真实姓名" clearable style="width: 160px;" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="searchForm.role" placeholder="全部" clearable style="width: 120px;">
            <el-option label="管理员" value="admin" />
            <el-option label="操作员" value="operator" />
            <el-option label="只读用户" value="viewer" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="handleReset">重置</el-button>
        </el-form-item>
        <el-form-item style="float: right;">
          <el-button type="success" icon="el-icon-plus" @click="handleAdd">新增用户</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 用户列表表格 -->
    <el-card shadow="never">
      <el-table :data="pagedList" border stripe style="width: 100%;" v-loading="loading">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="realName" label="真实姓名" width="120">
          <template slot-scope="scope">{{ scope.row.realName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="110" align="center">
          <template slot-scope="scope">
            <el-tag size="small" :type="roleTag(scope.row.role)">{{ roleText(scope.row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180">
          <template slot-scope="scope">{{ scope.row.email || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="130">
          <template slot-scope="scope">{{ scope.row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template slot-scope="scope">
            <el-tag size="small" :type="scope.row.status === 1 ? 'success' : 'info'">
              {{ scope.row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" align="center">
          <template slot-scope="scope">{{ formatTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" align="center" fixed="right" class-name="user-ops-col">
          <template slot-scope="scope">
            <el-button type="primary" icon="el-icon-edit" size="mini" @click="handleEdit(scope.row)">编辑</el-button>
            <el-button type="warning" icon="el-icon-key" size="mini" @click="handleResetPwd(scope.row)">重置密码</el-button>
            <el-button type="danger" icon="el-icon-delete" size="mini"
              :disabled="scope.row.username === 'admin'" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        style="margin-top: 20px; text-align: right;"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="filteredList.length"
        :page-sizes="[5, 10, 20, 50]"
        :page-size="pageSize"
        :current-page="currentPage"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </el-card>

    <!-- 新增 / 编辑 弹窗 -->
    <el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="520px" @close="resetForm">
      <el-form ref="userForm" :model="userForm" :rules="rules" label-width="100px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="userForm.username" placeholder="请输入用户名" :disabled="isEdit" />
        </el-form-item>
        <el-form-item :label="isEdit ? '新密码' : '密码'" prop="password">
          <el-input v-model="userForm.password" type="password" show-password
            :placeholder="isEdit ? '留空则不修改密码' : '请输入密码，默认 123456'" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="userForm.realName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="userForm.role" placeholder="请选择角色" style="width: 100%;">
            <el-option label="管理员（admin）" value="admin" />
            <el-option label="操作员（operator）" value="operator" />
            <el-option label="只读用户（viewer）" value="viewer" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="userForm.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="userForm.phone" placeholder="可选" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="userForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="dialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确 定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { getUserList, addUser, updateUser, deleteUser, resetUserPassword } from '../api/user';

export default {
  name: 'UserManage',
  data() {
    // 手机号校验
    const validatePhone = (rule, value, callback) => {
      if (!value) {
        callback();
      } else if (!/^1[3-9]\d{9}$/.test(value)) {
        callback(new Error('手机号格式不正确'));
      } else {
        callback();
      }
    };
    // 邮箱校验
    const validateEmail = (rule, value, callback) => {
      if (!value) {
        callback();
      } else if (!/^[\w.-]+@[\w.-]+\.\w+$/.test(value)) {
        callback(new Error('邮箱格式不正确'));
      } else {
        callback();
      }
    };
    return {
      loading: false,
      submitting: false,
      searchForm: { username: '', realName: '', role: '' },
      userList: [],
      currentPage: 1,
      pageSize: 10,
      dialogVisible: false,
      isEdit: false,
      userForm: {
        id: null, username: '', password: '', realName: '',
        role: 'viewer', email: '', phone: '', status: 1
      },
      rules: {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
          { min: 3, max: 20, message: '用户名长度 3-20 位', trigger: 'blur' }
        ],
        role: [{ required: true, message: '请选择角色', trigger: 'change' }],
        email: [{ validator: validateEmail, trigger: 'blur' }],
        phone: [{ validator: validatePhone, trigger: 'blur' }]
      }
    };
  },
  computed: {
    filteredList() {
      return this.userList.filter(item => {
        const matchUser = (item.username || '').includes(this.searchForm.username);
        const matchName = (item.realName || '').includes(this.searchForm.realName);
        const matchRole = !this.searchForm.role || item.role === this.searchForm.role;
        return matchUser && matchName && matchRole;
      });
    },
    pagedList() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.filteredList.slice(start, start + this.pageSize);
    },
    dialogTitle() {
      return this.isEdit ? '编辑用户' : '新增用户';
    },
    activeCount() {
      return this.userList.filter(u => u.status === 1).length;
    },
    disabledCount() {
      return this.userList.filter(u => u.status === 0).length;
    }
  },
  mounted() {
    this.fetchList();
  },
  methods: {
    fetchList() {
      this.loading = true;
      getUserList()
        .then(res => {
          this.userList = res.data || [];
        })
        .catch(() => {
          this.$message.error('获取用户列表失败，请确认后端服务已启动');
          this.userList = [];
        })
        .finally(() => {
          this.loading = false;
        });
    },
    roleTag(role) {
      const map = { admin: 'danger', operator: 'warning', viewer: 'info' };
      return map[role] || '';
    },
    roleText(role) {
      const map = { admin: '管理员', operator: '操作员', viewer: '只读用户' };
      return map[role] || role;
    },
    formatTime(t) {
      if (!t) return '-';
      return t.replace('T', ' ').substring(0, 19);
    },
    handleSearch() {
      this.currentPage = 1;
    },
    handleReset() {
      this.searchForm = { username: '', realName: '', role: '' };
      this.currentPage = 1;
    },
    handleSizeChange(val) {
      this.pageSize = val;
      this.currentPage = 1;
    },
    handleCurrentChange(val) {
      this.currentPage = val;
    },
    handleAdd() {
      this.isEdit = false;
      this.userForm = {
        id: null, username: '', password: '', realName: '',
        role: 'viewer', email: '', phone: '', status: 1
      };
      this.dialogVisible = true;
    },
    handleEdit(row) {
      this.isEdit = true;
      this.userForm = {
        id: row.id,
        username: row.username,
        password: '',
        realName: row.realName || '',
        role: row.role,
        email: row.email || '',
        phone: row.phone || '',
        status: row.status
      };
      this.dialogVisible = true;
    },
    handleSubmit() {
      this.$refs.userForm.validate(valid => {
        if (!valid) return;
        this.submitting = true;
        const action = this.isEdit ? updateUser(this.userForm) : addUser(this.userForm);
        action
          .then(res => {
            const data = res.data || {};
            if (data.success === false) {
              this.$message.error(data.message || '操作失败');
              return;
            }
            this.$message.success(this.isEdit ? '用户更新成功' : '用户新增成功');
            this.dialogVisible = false;
            this.fetchList();
          })
          .catch(() => {
            this.$message.error(this.isEdit ? '更新失败' : '新增失败');
          })
          .finally(() => {
            this.submitting = false;
          });
      });
    },
    handleResetPwd(row) {
      this.$confirm(`确认将用户「${row.username}」的密码重置为 123456 吗？`, '重置密码', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        resetUserPassword(row.id)
          .then(() => {
            this.$message.success('密码已重置为 123456');
          })
          .catch(() => {
            this.$message.error('重置密码失败');
          });
      }).catch(() => {});
    },
    handleDelete(row) {
      this.$confirm(`确认删除用户「${row.username}」吗？`, '删除确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        deleteUser(row.id)
          .then(() => {
            this.$message.success('删除成功');
            this.fetchList();
          })
          .catch(() => {
            this.$message.error('删除失败');
          });
      }).catch(() => {});
    },
    resetForm() {
      if (this.$refs.userForm) {
        this.$refs.userForm.resetFields();
      }
    }
  }
};
</script>

<style scoped>
.user-manage-container {
  padding: 20px;
  background-color: #f7f8fa;
  min-height: 100%;
}
/* 操作列三个按钮不换行，避免点击错位 */
.user-manage-container ::v-deep .user-ops-col .cell {
  white-space: nowrap;
}
.stat-card {
  text-align: center;
  padding: 10px 0;
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-label {
  color: #909399;
  font-size: 13px;
  margin-top: 4px;
}
.stat-total .stat-value { color: #3a7bd5; }
.stat-active .stat-value { color: #52a55c; }
.stat-disabled .stat-value { color: #94a0b4; }
</style>
