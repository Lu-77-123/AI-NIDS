<template>
  <div class="alert-container">
    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" style="margin-bottom: 20px;">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card stat-today">
          <div class="stat-value">{{ stats.todayCount || 0 }}</div>
          <div class="stat-label">今日告警</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card stat-unhandled">
          <div class="stat-value">{{ stats.unhandledCount || 0 }}</div>
          <div class="stat-label">待处理</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card stat-critical">
          <div class="stat-value">{{ stats.critical || 0 }}</div>
          <div class="stat-label">严重威胁</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card stat-high">
          <div class="stat-value">{{ stats.high || 0 }}</div>
          <div class="stat-label">高危告警</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 攻击类型分布 -->
    <el-card shadow="never" style="margin-bottom: 20px;" v-if="attackTypeList.length > 0">
      <div slot="header"><span>攻击类型分布</span></div>
      <el-tag v-for="item in attackTypeList" :key="item.type"
        :type="severityTag(item.type)"
        style="margin-right: 12px; margin-bottom: 8px; font-size: 13px;">
        {{ item.type }}：{{ item.count }} 次
      </el-tag>
    </el-card>

    <!-- 告警列表 -->
    <el-card shadow="never">
      <div slot="header">
        <span>入侵告警列表</span>
        <div style="float: right; display: flex; align-items: center; gap: 10px;">
          <el-select v-model="filterSeverity" placeholder="威胁等级" clearable size="small" style="width: 120px;">
            <el-option label="严重" value="critical" />
            <el-option label="高危" value="high" />
            <el-option label="中危" value="medium" />
            <el-option label="低危" value="low" />
          </el-select>
          <el-select v-model="filterStatus" placeholder="处理状态" clearable size="small" style="width: 120px;">
            <el-option label="未处理" :value="0" />
            <el-option label="已确认" :value="1" />
            <el-option label="误报" :value="2" />
            <el-option label="已处理" :value="3" />
          </el-select>
          <el-button size="small" type="success" plain icon="el-icon-check"
            :disabled="selectedUnhandled.length === 0" :loading="batchLoading"
            @click="handleBatch(3)">批量处理 ({{ selectedUnhandled.length }})</el-button>
          <el-button size="small" icon="el-icon-refresh" @click="loadAlerts">刷新</el-button>
        </div>
      </div>
      <el-table :data="pagedList" border stripe style="width: 100%;" v-loading="loading"
        :empty-text="loading ? '加载中...' : '暂无告警记录，系统运行正常'"
        @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="45" align="center"
          :selectable="canSelect" />
        <el-table-column type="index" label="序号" width="55" align="center" />

        <el-table-column prop="alertTime" label="告警时间" width="160" align="center">
          <template slot-scope="scope">{{ formatTime(scope.row.alertTime) }}</template>
        </el-table-column>

        <el-table-column prop="deviceName" label="设备名称" min-width="140" />
        <el-table-column prop="deviceIp" label="设备IP" width="120" />

        <el-table-column prop="attackType" label="攻击类型" width="120" align="center">
          <template slot-scope="scope">
            <el-tag :type="severityTag(scope.row.attackType)" size="small">
              {{ scope.row.attackType }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="severity" label="威胁等级" width="100" align="center">
          <template slot-scope="scope">
            <el-tag :type="severityLevel(scope.row.severity)" size="small" effect="dark">
              {{ severityText(scope.row.severity) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="anomalyScore" label="异常评分" width="100" align="center">
          <template slot-scope="scope">
            <span :style="{ color: scope.row.anomalyScore < -0.3 ? '#F56C6C' : '#67C23A' }">
              {{ scope.row.anomalyScore != null ? scope.row.anomalyScore.toFixed(4) : '-' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column prop="confidence" label="置信度" width="90" align="center">
          <template slot-scope="scope">
            {{ scope.row.confidence != null ? (scope.row.confidence * 100).toFixed(0) + '%' : '-' }}
          </template>
        </el-table-column>

        <el-table-column label="流量(Mbps)" width="140" align="center">
          <template slot-scope="scope">
            <span style="color: #409EFF;">↓{{ toMbps(scope.row.inBps) }}</span> /
            <span style="color: #67C23A;">↑{{ toMbps(scope.row.outBps) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />

        <el-table-column prop="status" label="状态" width="90" align="center">
          <template slot-scope="scope">
            <el-tag :type="statusTag(scope.row.status)" size="small">{{ statusText(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="290" align="center" fixed="right">
          <template slot-scope="scope">
            <el-button v-if="scope.row.status === 0" type="success" size="mini"
              @click="handleAlert(scope.row, 3)">处理</el-button>
            <el-button v-if="scope.row.status === 0" type="warning" size="mini"
              @click="handleAlert(scope.row, 2)">误报</el-button>
            <el-button v-if="scope.row.status === 0" type="primary" size="mini"
              @click="handleAlert(scope.row, 1)">确认</el-button>
            <el-button type="text" size="mini"
              :loading="aiLoadingId === scope.row.id"
              @click="analyzeAlert(scope.row)">
              {{ scope.row.aiAnalysis ? '查看研判' : 'AI研判' }}
            </el-button>
            <el-button v-if="isAdmin" type="text" size="mini" style="color: #F56C6C;"
              :loading="deleteLoadingId === scope.row.id"
              @click="deleteAlert(scope.row)">删除</el-button>
            <el-tag v-if="scope.row.status !== 0" type="success" size="mini" effect="plain"
              style="margin-left: 6px;">已处理</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        style="margin-top: 20px; text-align: right;"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="filteredList.length"
        :page-sizes="[10, 20, 50, 100]"
        :page-size="pageSize"
        :current-page="currentPage"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </el-card>

    <!-- AI 研判结果弹窗 -->
    <el-dialog :title="'AI 智能研判 · ' + (aiResult.deviceName || '')"
      :visible.sync="aiDialogVisible" width="600px" append-to-body>
      <div style="margin-bottom: 12px;">
        <el-tag v-if="aiResult.ai" type="success" size="small" effect="dark">DeepSeek {{ aiResult.model }}</el-tag>
        <el-tag v-else type="info" size="small" effect="plain">规则引擎兜底</el-tag>
        <el-tag v-if="aiResult.cached" type="warning" size="small" effect="plain" style="margin-left: 6px;">历史研判缓存</el-tag>
        <span v-if="aiResult.analysisTime" style="margin-left: 10px; color: #909399; font-size: 12px;">
          研判时间：{{ aiResult.analysisTime }}
        </span>
      </div>
      <div class="ai-analysis-text">{{ aiResult.analysis }}</div>
      <span slot="footer">
        <el-button size="small" @click="aiDialogVisible = false">关 闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import axios from 'axios';
import { getCurrentUser } from '../utils/auth';

export default {
  name: 'Alert',
  data() {
    return {
      loading: false,
      alertList: [],
      stats: {},
      timer: null,
      filterSeverity: '',
      filterStatus: null,
      currentPage: 1,
      pageSize: 10,
      selectedRows: [],
      batchLoading: false,
      aiDialogVisible: false,
      aiLoadingId: null,
      deleteLoadingId: null,
      aiResult: {}
    };
  },
  computed: {
    isAdmin() {
      const u = getCurrentUser();
      return !!u && u.role === 'admin';
    },
    attackTypeList() {
      const types = this.stats.attackTypes || {};
      return Object.keys(types).map(t => ({ type: t, count: types[t] }));
    },
    filteredList() {
      return this.alertList.filter(item => {
        const matchSeverity = !this.filterSeverity || item.severity === this.filterSeverity;
        const matchStatus = this.filterStatus === null || this.filterStatus === '' || item.status === this.filterStatus;
        return matchSeverity && matchStatus;
      });
    },
    pagedList() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.filteredList.slice(start, start + this.pageSize);
    },
    selectedUnhandled() {
      return this.selectedRows.filter(r => r.status === 0);
    }
  },
  watch: {
    filterSeverity() { this.currentPage = 1; },
    filterStatus() { this.currentPage = 1; }
  },
  mounted() {
    this.loadAlerts();
    this.loadStats();
    this.timer = setInterval(() => {
      this.loadAlerts();
      this.loadStats();
    }, 15000);
  },
  beforeDestroy() {
    if (this.timer) clearInterval(this.timer);
  },
  methods: {
    async loadAlerts() {
      this.loading = true;
      try {
        const res = await axios.get('/api/alerts/list');
        this.alertList = res.data;
      } catch (e) {
        console.error('加载告警失败:', e);
      } finally {
        this.loading = false;
      }
    },
    async loadStats() {
      try {
        const res = await axios.get('/api/alerts/stats');
        this.stats = res.data;
      } catch (e) {
        console.error('加载统计失败:', e);
      }
    },
    async handleAlert(row, status) {
      const text = status === 1 ? '确认' : status === 2 ? '标记为误报' : '标记为已处理';
      try {
        await this.$confirm(`确定要${text}该告警吗？`, '提示', { type: 'warning' });
        await axios.put(`/api/alerts/${row.id}/handle?status=${status}`);
        this.$message.success('操作成功');
        this.loadAlerts();
        this.loadStats();
      } catch (e) {
        if (e !== 'cancel') this.$message.error('操作失败');
      }
    },
    toMbps(bps) {
      if (!bps) return '0.0';
      return (bps / 1000000).toFixed(1);
    },
    formatTime(t) {
      if (!t) return '-';
      const d = new Date(t);
      const pad = n => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    },
    severityTag(type) {
      const map = { 'DDoS': 'danger', '数据外泄': 'danger', '蠕虫传播': 'danger',
        '端口扫描': 'warning', '慢速DoS': 'warning', '未知异常': 'info' };
      return map[type] || 'info';
    },
    severityLevel(level) {
      const map = { 'critical': 'danger', 'high': 'danger', 'medium': 'warning', 'low': 'info' };
      return map[level] || 'info';
    },
    severityText(level) {
      const map = { 'critical': '严重', 'high': '高危', 'medium': '中危', 'low': '低危' };
      return map[level] || level || '-';
    },
    statusTag(status) {
      const map = { 0: 'danger', 1: 'primary', 2: 'info', 3: 'success' };
      return map[status] || 'info';
    },
    statusText(status) {
      const map = { 0: '未处理', 1: '已确认', 2: '误报', 3: '已处理' };
      return map[status] || '未知';
    },
    handleSizeChange(val) {
      this.pageSize = val;
      this.currentPage = 1;
    },
    handleCurrentChange(val) {
      this.currentPage = val;
    },
    canSelect(row) {
      return row.status === 0;
    },
    handleSelectionChange(rows) {
      this.selectedRows = rows || [];
    },
    async handleBatch(status) {
      const targets = this.selectedUnhandled;
      if (targets.length === 0) return;
      try {
        await this.$confirm(`确定批量处理选中的 ${targets.length} 条未处理告警吗？`, '批量处理', { type: 'warning' });
      } catch (e) {
        return;
      }
      this.batchLoading = true;
      let success = 0;
      for (const row of targets) {
        try {
          await axios.put(`/api/alerts/${row.id}/handle?status=${status}`);
          success++;
        } catch (e) {
          // 单条失败继续处理其余
        }
      }
      this.batchLoading = false;
      this.selectedRows = [];
      this.$message.success(`批量处理完成：成功 ${success} / ${targets.length} 条`);
      this.loadAlerts();
      this.loadStats();
    },
    async deleteAlert(row) {
      try {
        await this.$confirm(
          `确定删除该告警吗？删除后不可恢复。\n设备：${row.deviceName}　类型：${row.attackType}`,
          '删除告警', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
        );
      } catch (e) {
        return;
      }
      this.deleteLoadingId = row.id;
      try {
        await axios.delete(`/api/alerts/${row.id}`);
        this.$message.success('告警已删除');
        if (this.pagedList.length === 1 && this.currentPage > 1) this.currentPage--;
        await this.loadAlerts();
        this.loadStats();
      } catch (e) {
        const msg = e.response && e.response.data && e.response.data.message;
        this.$message.error(msg || '删除失败，请稍后重试');
      } finally {
        this.deleteLoadingId = null;
      }
    },
    async analyzeAlert(row) {
      // 已研判过的告警：直接弹出缓存内容，不重复请求后端
      if (row.aiAnalysis) {
        this.aiResult = {
          deviceName: row.deviceName,
          analysis: row.aiAnalysis,
          ai: true,
          cached: true,
          model: row.aiModel || 'deepseek',
          analysisTime: this.formatTime(row.aiAnalysisTime) !== '-' ? this.formatTime(row.aiAnalysisTime) : ''
        };
        this.aiDialogVisible = true;
        return;
      }
      this.aiLoadingId = row.id;
      try {
        const res = await axios.post(`/api/alerts/${row.id}/analyze`);
        this.aiResult = Object.assign({ deviceName: row.deviceName }, res.data);
        this.aiDialogVisible = true;
        if (res.data.ai) {
          // 同步到当前行，刷新列表后按钮变为「查看研判」
          this.$set(row, 'aiAnalysis', res.data.analysis);
          this.$set(row, 'aiAnalysisTime', res.data.analysisTime);
          this.$set(row, 'aiModel', res.data.model);
          this.$message.success(res.data.cached ? '已加载历史 AI 研判' : 'AI 研判完成');
        } else {
          this.$message.warning('AI 服务未启用或暂不可用，已展示规则引擎兜底结论');
        }
      } catch (e) {
        console.error('AI 研判失败:', e);
        this.$message.error('AI 研判请求失败，请稍后重试');
      } finally {
        this.aiLoadingId = null;
      }
    }
  }
};
</script>

<style scoped>
.alert-container {
  padding: 20px;
  background-color: #f7f8fa;
  min-height: 100%;
}
.stat-card {
  text-align: center;
  padding: 10px 0;
}
.stat-value {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
.stat-today .stat-value { color: #3a7bd5; }
.stat-unhandled .stat-value { color: #e3a21f; }
.stat-critical .stat-value { color: #F56C6C; }
.stat-high .stat-value { color: #f09f3a; }
.ai-analysis-text {
  white-space: pre-line;
  line-height: 1.8;
  font-size: 14px;
  color: #303133;
  background: #f5f7fa;
  border-left: 3px solid #409EFF;
  border-radius: 4px;
  padding: 12px 16px;
  max-height: 420px;
  overflow-y: auto;
}
</style>
