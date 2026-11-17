<template>
  <div class="discovery-container">
    <!-- 标题 -->
    <div class="page-header">
      <div class="header-title">网络自动发现</div>
      <div class="header-desc">扫描内网网段，自动发现 SNMP 设备并加入管理</div>
    </div>

    <!-- 配置区 -->
    <el-card class="config-card" shadow="never">
      <el-form :inline="true" label-width="80px">
        <el-form-item label="扫描网段">
          <el-input v-model="segment" placeholder="如 172.18.0.0/24" style="width: 220px;" :disabled="scanning" />
        </el-form-item>
        <el-form-item label="社区名">
          <el-input v-model="community" placeholder="public" style="width: 120px;" :disabled="scanning" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" :loading="scanning" @click="startScan">
            {{ scanning ? '扫描中...' : '开始扫描' }}
          </el-button>
          <el-button icon="el-icon-refresh" @click="detectSegment" :disabled="scanning">自动检测网段</el-button>
        </el-form-item>
      </el-form>

      <!-- 进度条 -->
      <div v-if="scanning || progress.total > 0" class="progress-section">
        <el-progress
          :percentage="progressPercentage"
          :status="scanning ? '' : 'success'"
          :stroke-width="20"
          :text-inside="true"
        />
        <div class="progress-info">
          <span>已扫描: {{ progress.current }} / {{ progress.total }}</span>
          <span>已发现: <b style="color: #67C23A;">{{ foundDevices.length }}</b> 台</span>
        </div>
      </div>
    </el-card>

    <!-- 发现设备列表 -->
    <el-card class="result-card" shadow="never">
      <div slot="header" class="result-header">
        <span>发现的设备（{{ foundDevices.length }} 台）</span>
        <div v-if="foundDevices.length > 0 && !scanning">
          <el-button type="success" size="small" icon="el-icon-plus" @click="addAllNew">
            一键加入未管理设备 ({{ newDeviceCount }} 台)
          </el-button>
        </div>
      </div>

      <el-table
        :data="foundDevices"
        v-loading="scanning"
        element-loading-text="正在扫描网络..."
        stripe
        border
        style="width: 100%;"
        empty-text="暂无数据，请点击「开始扫描」"
      >
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="ip" label="IP 地址" width="150" />
        <el-table-column prop="sysName" label="设备名称" min-width="180">
          <template slot-scope="s">
            <span>{{ s.row.sysName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="deviceType" label="设备类型" width="120" align="center">
          <template slot-scope="s">
            <el-tag size="small" :type="typeTag(s.row.deviceType)">{{ s.row.deviceType || '未知' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="vendor" label="厂商" width="100" align="center">
          <template slot-scope="s">{{ s.row.vendor || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sysDescr" label="设备描述" min-width="250" show-overflow-tooltip>
          <template slot-scope="s">{{ s.row.sysDescr || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template slot-scope="s">
            <el-tag size="small" :type="s.row.existing ? 'info' : 'success'">
              {{ s.row.existing ? '已管理' : '新发现' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center" v-if="!scanning">
          <template slot-scope="s">
            <el-button
              v-if="!s.row.existing"
              type="text"
              size="small"
              icon="el-icon-plus"
              @click="addOne(s.row)"
            >加入</el-button>
            <span v-else style="color: #909399;">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { detectSegment, startScan, addDiscoveredDevices, getScanResult } from '../api/discovery';
import WebSocketClient from '../utils/websocket';

export default {
  name: 'Discovery',
  data() {
    return {
      segment: '',
      community: 'public',
      scanning: false,
      progress: { current: 0, total: 0 },
      foundDevices: [],
      wsClient: null,
      wsHandler: null
    };
  },
  computed: {
    progressPercentage() {
      if (this.progress.total === 0) return 0;
      return Math.round((this.progress.current / this.progress.total) * 100);
    },
    newDeviceCount() {
      return this.foundDevices.filter(d => !d.existing).length;
    }
  },
  async mounted() {
    this.detectSegment();
    this.connectWebSocket();
    // 进入页面时先拉取后端缓存结果（避免刷新/晚进入错过 WebSocket 推送）
    try {
      const res = await getScanResult();
      this.applyCachedResult(res.data);
    } catch (e) {
      // 忽略，等用户点扫描
    }
  },
  beforeDestroy() {
    if (this.wsClient && this.wsHandler) {
      this.wsClient.offMessage(this.wsHandler);
    }
    this.wsClient = null;
  },
  methods: {
    async detectSegment() {
      try {
        const res = await detectSegment();
        if (res.data && res.data.segment) {
          this.segment = res.data.segment;
        }
      } catch (e) {
        this.segment = '172.18.0.0/24';
      }
    },
    async startScan() {
      if (!this.segment) {
        this.$message.warning('请输入扫描网段');
        return;
      }
      try {
        // 先尝试调用
        const res = await startScan(this.segment, this.community);
        if (res.data && res.data.success === false) {
          // 后端还在扫描中，不清空数据，改为拉取最新缓存同步状态
          this.$message.warning(res.data.message || '扫描正在进行中，请等待完成');
          try {
            const latest = await getScanResult();
            this.applyCachedResult(latest.data);
          } catch (_) { /* ignore */ }
          return;
        }
        // 正常启动新扫描：清空数据
        this.foundDevices = [];
        this.progress = { current: 0, total: 0 };
        this.scanning = true;
        this.$message.success('扫描已启动');
      } catch (e) {
        this.scanning = false;
        this.$message.error('启动扫描失败: ' + (e.response?.data?.message || e.message));
      }
    },
    /** 应用后端缓存结果（进入页面 / 启动被拒绝时调用）*/
    applyCachedResult(data) {
      if (!data) return;
      this.scanning = !!data.scanning;
      if (data.progress && typeof data.progress === 'object') {
        this.progress = {
          current: Number(data.progress.current) || 0,
          total: Number(data.progress.total) || 0
        };
      }
      if (Array.isArray(data.devices)) {
        // 拷贝一份，避免引用污染
        this.foundDevices = data.devices.map(d => ({
          ip: d.ip,
          sysName: d.sysName,
          sysDescr: d.sysDescr,
          deviceType: d.deviceType,
          vendor: d.vendor,
          existing: !!d.existing
        }));
      }
    },
    connectWebSocket() {
      this.wsClient = new WebSocketClient();
      this.wsHandler = (data) => this.handleWsMessage(data);
      this.wsClient.onMessage(this.wsHandler);
      this.wsClient.connect();
    },
    handleWsMessage(data) {
      if (!data || !data.type) return;

      switch (data.type) {
        case 'discovery_start':
          this.progress = { current: 0, total: data.total || 254 };
          this.$message.info('开始扫描网段: ' + data.segment);
          break;

        case 'discovery_progress':
          this.progress.current = data.current || 0;
          this.progress.total = data.total || 254;
          break;

        case 'discovery_device': {
          const dev = {
            ip: data.ip,
            sysName: data.sysName,
            sysDescr: data.sysDescr,
            deviceType: data.deviceType,
            vendor: data.vendor,
            existing: data.existing
          };
          // 去重
          const idx = this.foundDevices.findIndex(d => d.ip === dev.ip);
          if (idx >= 0) {
            this.foundDevices.splice(idx, 1, dev);
          } else {
            this.foundDevices.push(dev);
          }
          break;
        }

        case 'discovery_done':
          this.scanning = false;
          this.progress.current = data.total;
          this.$notify({
            title: '扫描完成',
            message: `共发现 ${data.found} 台 SNMP 设备`,
            type: 'success'
          });
          break;

        case 'discovery_error':
          this.scanning = false;
          this.$message.error('扫描异常: ' + data.message);
          break;
      }
    },
    async addOne(device) {
      try {
        const res = await addDiscoveredDevices([device.ip], this.community);
        device.existing = true;
        this.$message.success(res.data.message || '加入成功');
      } catch (e) {
        this.$message.error('加入失败: ' + (e.response?.data?.message || e.message));
      }
    },
    async addAllNew() {
      const newIps = this.foundDevices.filter(d => !d.existing).map(d => d.ip);
      if (newIps.length === 0) {
        this.$message.info('没有新设备需要加入');
        return;
      }
      try {
        const res = await addDiscoveredDevices(newIps, this.community);
        this.foundDevices.forEach(d => { d.existing = true; });
        this.$message.success(res.data.message || '加入成功');
      } catch (e) {
        this.$message.error('加入失败: ' + (e.response?.data?.message || e.message));
      }
    },
    typeTag(type) {
      const map = {
        '路由器': 'primary',
        '交换机': 'success',
        '防火墙': 'danger',
        '服务器': 'info',
        '无线AP': 'warning',
        '网络设备': '',
        '未知设备': ''
      };
      return map[type] || '';
    }
  }
};
</script>

<style scoped>
.discovery-container {
  padding: 20px;
  background-color: #f7f8fa;
  min-height: 100%;
}

.page-header {
  margin-bottom: 16px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.header-desc {
  font-size: 13px;
  color: #909399;
  margin-top: 5px;
}

.config-card {
  margin-bottom: 20px;
}

.progress-section {
  margin-top: 15px;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}

.result-card {
  margin-bottom: 20px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
</style>
