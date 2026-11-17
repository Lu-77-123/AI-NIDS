<template>
  <div class="device-manage-container">
    <!-- 设备类型分类卡片：点击按类型快速筛选 -->
    <el-row :gutter="12" class="type-card-row">
      <el-col :xs="12" :sm="6" :md="3">
        <div :class="['type-card', { active: searchForm.type === '' }]" @click="toggleType('')">
          <div class="tc-name">全部设备</div>
          <div class="tc-count">{{ deviceList.length }}</div>
          <div class="tc-online"><i class="el-icon-success"></i> 在线 {{ onlineTotal }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="3" v-for="t in typeList" :key="t">
        <div :class="['type-card', { active: searchForm.type === t, zero: typeCount(t) === 0 }]" @click="toggleType(t)">
          <div class="tc-name">{{ t }}</div>
          <div class="tc-count">{{ typeCount(t) }}</div>
          <div class="tc-online"><i class="el-icon-success"></i> 在线 {{ typeOnlineCount(t) }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 顶部工具栏：搜索 + 新增 -->
    <el-card shadow="never" style="margin-bottom: 20px;">
      <el-form :inline="true" :model="searchForm" size="small">
        <el-form-item label="设备名称">
          <el-input v-model="searchForm.name" placeholder="请输入设备名称" clearable style="width: 160px;" />
        </el-form-item>
        <el-form-item label="IP 地址">
          <el-input v-model="searchForm.ip" placeholder="请输入 IP 地址" clearable style="width: 160px;" />
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="searchForm.type" placeholder="全部分类" clearable style="width: 140px;" @change="handleTypeChange">
            <el-option v-for="t in typeList" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="handleReset">重置</el-button>
        </el-form-item>
        <el-form-item style="float: right;">
          <el-button type="success" icon="el-icon-plus" @click="handleAdd">新增设备</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 设备列表表格 -->
    <el-card shadow="never">
      <el-table :data="pagedList" border stripe :row-class-name="deviceRowClass" style="width: 100%;" v-loading="loading">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="name" label="设备名称" min-width="150">
          <template slot-scope="scope">{{ scope.row.name || '-' }}</template>
        </el-table-column>
        <el-table-column prop="ip" label="IP 地址" width="150" />
        <el-table-column prop="type" label="设备类型" width="110" align="center">
          <template slot-scope="scope">
            <el-tag size="small" :type="typeTag(scope.row.type)">{{ scope.row.type || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template slot-scope="scope">
            <el-tag size="small" :type="scope.row.status === '在线' ? 'success' : 'danger'">
              {{ scope.row.status || '未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="upTime" label="运行时间" width="140" align="center">
          <template slot-scope="scope">{{ scope.row.upTime || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template slot-scope="scope">
            <el-button type="primary" icon="el-icon-edit" size="mini" @click="handleEdit(scope.row)">编辑</el-button>
            <el-button type="danger" icon="el-icon-delete" size="mini" @click="handleDelete(scope.row)">删除</el-button>
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
    <el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="500px" @close="resetForm">
      <el-form ref="deviceForm" :model="deviceForm" :rules="rules" label-width="100px">
        <el-form-item label="IP 地址" prop="ip">
          <el-input v-model="deviceForm.ip" placeholder="请输入设备 IP，如 192.168.56.100" />
        </el-form-item>
        <el-form-item label="设备类型" prop="type">
          <el-select v-model="deviceForm.type" placeholder="请选择设备类型" style="width: 100%;">
            <el-option v-for="t in DEVICE_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="SNMP 社区" prop="community">
          <el-input v-model="deviceForm.community" placeholder="默认 public" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="deviceForm.remark" placeholder="可选，设备显示备注名" />
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
import { getDeviceList, addDevice, updateDevice, deleteDevice } from '../api/device';

export default {
  name: 'DeviceManage',
  data() {
    // IP 地址校验
    const validateIp = (rule, value, callback) => {
      const reg = /^((25[0-5]|2[0-4]\d|[01]?\d?\d)\.){3}(25[0-5]|2[0-4]\d|[01]?\d?\d)$/;
      if (!value) {
        callback(new Error('请输入 IP 地址'));
      } else if (!reg.test(value)) {
        callback(new Error('IP 地址格式不正确'));
      } else {
        callback();
      }
    };
    return {
      loading: false,
      submitting: false,
      // 设备分类体系（与新增/编辑弹窗、类型标签颜色保持一致）
      DEVICE_TYPES: ['核心交换机', '交换机', '路由器', '防火墙', '服务器', '无线AP', '终端PC'],
      searchForm: { name: '', ip: '', type: '' },
      // 设备列表：来自后端真实接口
      deviceList: [],
      // 分页
      currentPage: 1,
      pageSize: 5,
      // 弹窗
      dialogVisible: false,
      isEdit: false,
      deviceForm: { id: null, ip: '', type: '', community: 'public', remark: '' },
      rules: {
        ip: [{ required: true, validator: validateIp, trigger: 'blur' }],
        type: [{ required: true, message: '请选择设备类型', trigger: 'change' }],
      },
    };
  },
  computed: {
    /** 分类卡片/筛选用的类型清单：预定义 7 类在前，数据中出现的未知类型自动追加 */
    typeList() {
      const list = this.DEVICE_TYPES.slice();
      this.deviceList.forEach(d => {
        if (d.type && list.indexOf(d.type) === -1) list.push(d.type);
      });
      return list;
    },
    // 全网在线设备总数
    onlineTotal() {
      return this.deviceList.filter(d => d.status === '在线').length;
    },
    // 按搜索条件过滤（名称 + IP + 类型分类）
    filteredList() {
      return this.deviceList.filter(item => {
        const matchName = (item.name || '').includes(this.searchForm.name);
        const matchIp = (item.ip || '').includes(this.searchForm.ip);
        const matchType = !this.searchForm.type || item.type === this.searchForm.type;
        return matchName && matchIp && matchType;
      });
    },
    // 当前页数据
    pagedList() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.filteredList.slice(start, start + this.pageSize);
    },
    dialogTitle() {
      return this.isEdit ? '编辑设备' : '新增设备';
    },
  },
  mounted() {
    this.fetchList();
  },
  methods: {
    /** 离线设备行置灰 */
    deviceRowClass({ row }) {
      return row.status === '离线' || row.status === 'offline' ? 'offline-row' : '';
    },
    // ===== 设备分类统计 =====
    /** 某类型设备总数 */
    typeCount(type) {
      return this.deviceList.filter(d => d.type === type).length;
    },
    /** 某类型在线设备数 */
    typeOnlineCount(type) {
      return this.deviceList.filter(d => d.type === type && d.status === '在线').length;
    },
    /** 点击分类卡片：切换筛选（再次点击同一分类取消） */
    toggleType(type) {
      this.searchForm.type = this.searchForm.type === type ? '' : type;
      this.currentPage = 1;
    },
    /** 下拉选择分类时回到第一页 */
    handleTypeChange() {
      this.currentPage = 1;
    },
    // 从后端拉取设备列表（后端会实时 SNMP 采集 name/status/upTime）
    fetchList() {
      this.loading = true;
      getDeviceList()
        .then(res => {
          this.deviceList = res.data || [];
        })
        .catch(() => {
          this.$message.error('获取设备列表失败，请确认后端服务已启动');
          this.deviceList = [];
        })
        .finally(() => {
          this.loading = false;
        });
    },
    // 设备类型对应的标签颜色
    typeTag(type) {
      const map = {
        '核心交换机': 'primary',
        '交换机': '',
        '路由器': 'warning',
        '防火墙': 'danger',
        '服务器': 'success',
        '无线AP': 'warning',
        '终端PC': 'info'
      };
      return map[type] || '';
    },
    handleSearch() {
      this.currentPage = 1;
    },
    handleReset() {
      this.searchForm.name = '';
      this.searchForm.ip = '';
      this.searchForm.type = '';
      this.currentPage = 1;
    },
    handleSizeChange(val) {
      this.pageSize = val;
      this.currentPage = 1;
    },
    handleCurrentChange(val) {
      this.currentPage = val;
    },
    // 新增
    handleAdd() {
      this.isEdit = false;
      this.deviceForm = { id: null, ip: '', type: '', community: 'public', remark: '' };
      this.dialogVisible = true;
    },
    // 编辑
    handleEdit(row) {
      this.isEdit = true;
      this.deviceForm = {
        id: row.id,
        ip: row.ip,
        type: row.type,
        community: row.community || 'public',
        remark: row.remark || '',
      };
      this.dialogVisible = true;
    },
    // 提交（新增/编辑）
    handleSubmit() {
      this.$refs.deviceForm.validate(valid => {
        if (!valid) return;
        this.submitting = true;
        const action = this.isEdit ? updateDevice(this.deviceForm) : addDevice(this.deviceForm);
        action
          .then(() => {
            this.$message.success(this.isEdit ? '设备更新成功' : '设备新增成功');
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
    // 删除
    handleDelete(row) {
      this.$confirm(`确认删除设备「${row.ip}」吗？`, '删除确认', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }).then(() => {
        deleteDevice(row.id)
          .then(() => {
            this.$message.success('删除成功');
            this.fetchList();
          })
          .catch(() => {
            this.$message.error('删除失败');
          });
      }).catch(() => {});
    },
    // 关闭弹窗时重置校验状态
    resetForm() {
      if (this.$refs.deviceForm) {
        this.$refs.deviceForm.resetFields();
      }
    },
  },
};
</script>

<style scoped>
.device-manage-container {
  padding: 20px;
  background-color: #f7f8fa;
  min-height: 100%;
}

/* 设备类型分类卡片 */
.type-card-row {
  margin-bottom: 16px;
}
.type-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 10px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  margin-bottom: 12px;
  user-select: none;
}
.type-card:hover {
  border-color: #a0c8f5;
  background: #fafcff;
}
.type-card.active {
  border-color: #409EFF;
  background: #f0f7ff;
}
.type-card.zero .tc-count,
.type-card.zero .tc-name {
  color: #c0c4cc;
}
.tc-name {
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tc-count {
  font-size: 26px;
  font-weight: bold;
  color: #303133;
  line-height: 1.3;
  margin: 2px 0;
}
.type-card.active .tc-count {
  color: #409EFF;
}
.tc-online {
  font-size: 11px;
  color: #67C23A;
}
.type-card.zero .tc-online {
  color: #c0c4cc;
}

::v-deep .el-table .offline-row td {
  background-color: rgba(245, 108, 108, 0.08) !important;
  color: #909399 !important;
}
::v-deep .el-table .offline-row .cell {
  color: #909399 !important;
}
</style>
