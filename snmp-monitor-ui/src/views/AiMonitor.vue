<template>
  <div class="ai-monitor-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <span class="header-title">智能检测引擎</span>
      <el-button size="small" icon="el-icon-refresh" @click="loadAll" :loading="loading">刷新</el-button>
    </div>

    <!-- 引擎状态卡片 -->
    <el-row :gutter="20" style="margin-bottom: 20px;">
      <el-col :span="8">
        <div class="status-card status-ok">
          <div class="status-icon"><i class="el-icon-success"></i></div>
          <div class="status-info">
            <div class="status-label">异常检测引擎</div>
            <div class="status-value">运行中 · 内置统计检测</div>
          </div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="status-card" :class="engine.deepseekEnabled ? 'status-ok' : 'status-down'">
          <div class="status-icon">
            <i :class="engine.deepseekEnabled ? 'el-icon-cpu' : 'el-icon-warning'"></i>
          </div>
          <div class="status-info">
            <div class="status-label">DeepSeek 告警研判</div>
            <div class="status-value">{{ engine.deepseekEnabled ? '已接入 · ' + engine.deepseekModel : '未配置 API Key' }}</div>
          </div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="status-card status-info-card">
          <div class="status-icon"><i class="el-icon-time"></i></div>
          <div class="status-info">
            <div class="status-label">告警冷却窗口</div>
            <div class="status-value">{{ engine.cooldownMinutes || '-' }} 分钟 / 同设备同类型</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- AI 配置中心 -->
    <el-card shadow="never" style="margin-bottom: 20px;">
      <div slot="header">
        <span>AI 配置中心</span>
        <el-tag size="mini" :type="isAdmin ? 'success' : 'info'" style="margin-left: 8px;">
          {{ isAdmin ? '管理员 · 保存后立即生效' : '只读 · 仅管理员可修改' }}
        </el-tag>
        <span v-if="engine.updateTime" class="update-meta">
          上次修改：{{ engine.updatedBy }} @ {{ engine.updateTime }}
        </span>
      </div>

      <el-form label-width="130px" size="small" :disabled="!isAdmin" class="config-form">
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="突增灵敏度阈值">
              <el-input-number v-model="form.zThreshold" :min="0.5" :max="10" :step="0.1" :precision="1" @change="dirty = true" />
              <span class="param-hint">zScore 超过该值判为突增（0.5~10，默认 {{ engine.defaults ? engine.defaults.zThreshold : 2 }}，越小越灵敏）</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="绝对流量下限">
              <el-input-number v-model="form.minAnomalyBps" :min="1000" :max="100000000" :step="1000" @change="dirty = true" />
              <span class="param-hint">bps，低于该量级不判异常（当前 ≈ {{ formatBps(form.minAnomalyBps) }}）</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="基线滑动窗口">
              <el-input-number v-model="form.windowSize" :min="10" :max="200" :step="5" @change="dirty = true" />
              <span class="param-hint">采样点数（10~200），采集周期 10s ≈ {{ (form.windowSize * 10 / 60).toFixed(1) }} 分钟</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="基线最少样本">
              <el-input-number v-model="form.minSamples" :min="3" :max="50" @change="dirty = true" />
              <span class="param-hint">条（3~50 且 ≤ 窗口），历史不足时不判定</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="告警冷却时间">
              <el-input-number v-model="form.cooldownMinutes" :min="0" :max="1440" @change="dirty = true" />
              <span class="param-hint">分钟（0~1440），同设备同攻击类型窗口内只告警一次</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="研判模型覆盖">
              <el-input v-model="form.deepseekModelOverride" placeholder="留空 = 使用默认（deepseek-flash）" maxlength="64" clearable @input="dirty = true" style="max-width: 260px;" />
              <span class="param-hint">仅替换模型名；API Key 由服务器环境变量配置，不下发前端</span>
            </el-form-item>
          </el-col>
        </el-row>
        <div v-if="isAdmin" class="config-actions">
          <el-button type="primary" size="small" icon="el-icon-check" :loading="saving" @click="saveConfig">保存修改</el-button>
          <el-button size="small" icon="el-icon-refresh-left" :loading="resetting" @click="resetConfig">恢复默认</el-button>
          <span v-if="dirty" style="color: #E6A23C; font-size: 12px; margin-left: 8px;">有未保存的修改</span>
        </div>
      </el-form>

      <el-descriptions :column="2" border size="medium" style="margin-top: 6px;">
        <el-descriptions-item label="检测方式">{{ engine.detector || '-' }}</el-descriptions-item>
        <el-descriptions-item label="基线算法">{{ engine.baselineAlgorithm || '-' }}（{{ engine.scaleFactor }}×MAD ≈ 正态标准差）</el-descriptions-item>
        <el-descriptions-item label="研判大模型">{{ engine.deepseekModel || '-' }}{{ engine.deepseekModelOverride ? '（配置中心覆盖）' : '（yml 默认）' }}</el-descriptions-item>
        <el-descriptions-item label="调用协议">OpenAI 兼容 /chat/completions（缓存去重，单条告警仅首次研判计费）</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 旁路抓包（Pcap4j 包级检测） -->
    <el-card shadow="never" style="margin-bottom: 20px;">
      <div slot="header">
        <span>旁路抓包（Pcap4j 包级检测）</span>
        <el-tag size="mini" :type="capture.running ? 'success' : 'info'" style="margin-left: 8px;">
          {{ capture.running ? '运行中' : '未运行' }}
        </el-tag>
      </div>
      <el-descriptions :column="3" border size="medium">
        <el-descriptions-item label="抓包网卡">{{ capture.interface || '未启动' }}</el-descriptions-item>
        <el-descriptions-item label="监听 IP">{{ capture.interfaceIp || '-' }}</el-descriptions-item>
        <el-descriptions-item label="实时包速率">{{ capture.latest ? capture.latest.pps + ' pps' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="TCP / SYN">{{ capture.latest ? capture.latest.tcpPps + ' / ' + capture.latest.synPps + ' pps' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="UDP">{{ capture.latest ? capture.latest.udpPps + ' pps' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="检出威胁">{{ (capture.threats || []).length }} 条</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="(capture.threats || []).length" :data="capture.threats" size="mini" style="margin-top: 12px;">
        <el-table-column prop="time" label="时间" width="190" />
        <el-table-column prop="attackType" label="攻击类型" width="120" />
        <el-table-column prop="severity" label="等级" width="90" />
        <el-table-column prop="description" label="描述" />
      </el-table>
      <div v-else style="margin-top: 10px; color: #909399; font-size: 12px;">
        当前未检出包级威胁（SYN Flood / UDP Flood / 端口扫描）
      </div>
    </el-card>

    <!-- 告警统计 -->
    <el-row :gutter="20" style="margin-bottom: 20px;">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value">{{ stats.todayCount != null ? stats.todayCount : '-' }}</div>
          <div class="stat-label">今日告警</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value" style="color: #F56C6C;">{{ stats.unhandledCount != null ? stats.unhandledCount : '-' }}</div>
          <div class="stat-label">未处理告警</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="stat-card">
          <div class="sev-row">
            <span class="sev-item"><b style="color: #B91C1C;">{{ stats.critical != null ? stats.critical : 0 }}</b> 紧急</span>
            <span class="sev-item"><b style="color: #F56C6C;">{{ stats.high != null ? stats.high : 0 }}</b> 高危</span>
            <span class="sev-item"><b style="color: #E6A23C;">{{ stats.medium != null ? stats.medium : 0 }}</b> 中危</span>
            <span class="sev-item"><b style="color: #409EFF;">{{ stats.low != null ? stats.low : 0 }}</b> 低危</span>
          </div>
          <div class="stat-label">最近 50 条告警威胁等级分布</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 检测流程说明 -->
    <el-card shadow="never" style="margin-bottom: 20px;">
      <div slot="header"><span>检测与研判流程</span></div>
      <div class="flow-box">
        <div class="flow-step">
          <div class="flow-num">1</div>
          <div class="flow-text">
            <div class="flow-title">流量采集</div>
            <div class="flow-desc">每 10 秒通过 SNMP 协议采集在线设备的 ifInOctets / ifOutOctets，计算 bps 速率</div>
          </div>
        </div>
        <div class="flow-arrow"><i class="el-icon-bottom"></i></div>
        <div class="flow-step">
          <div class="flow-num">2</div>
          <div class="flow-text">
            <div class="flow-title">稳健基线构建</div>
            <div class="flow-desc">取每台设备最近 {{ engine.windowSize || 30 }} 个采样点，以中位数 + MAD 计算流量基线，攻击尖峰不会污染基线</div>
          </div>
        </div>
        <div class="flow-arrow"><i class="el-icon-bottom"></i></div>
        <div class="flow-step">
          <div class="flow-num">3</div>
          <div class="flow-text">
            <div class="flow-title">突增异常判定</div>
            <div class="flow-desc">入向流量向上突增超过 {{ engine.zThreshold != null ? engine.zThreshold : 2 }} 倍稳健标准差，且绝对流量 ≥ {{ formatBps(engine.minAnomalyBps) }}（攻击量级下限）才判为异常</div>
          </div>
        </div>
        <div class="flow-arrow"><i class="el-icon-bottom"></i></div>
        <div class="flow-step">
          <div class="flow-num">4</div>
          <div class="flow-text">
            <div class="flow-title">攻击分类与告警</div>
            <div class="flow-desc">规则引擎按流量特征分类攻击类型与威胁等级，同设备同类型 {{ engine.cooldownMinutes != null ? engine.cooldownMinutes : 5 }} 分钟冷却抑制重复告警风暴</div>
          </div>
        </div>
        <div class="flow-arrow"><i class="el-icon-bottom"></i></div>
        <div class="flow-step">
          <div class="flow-num">5</div>
          <div class="flow-text">
            <div class="flow-title">大模型研判与推送</div>
            <div class="flow-desc">DeepSeek V4.1-Flash 对单条告警首次出现时进行语义研判（威胁判断/成因/处置建议），结果入库缓存，WebSocket 实时推送前端</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script>
import { getEngineStatus, getAlertStats, getCaptureStats, updateAiConfig, resetAiConfig } from '../api/ai';
import { getCurrentUser } from '../utils/auth';

export default {
  name: 'AiMonitor',
  data() {
    return {
      loading: false,
      saving: false,
      resetting: false,
      engine: {},
      stats: {},
      capture: {},
      timer: null,
      dirty: false,
      form: {
        zThreshold: 2.0,
        minAnomalyBps: 50000,
        windowSize: 30,
        minSamples: 5,
        cooldownMinutes: 5,
        deepseekModelOverride: ''
      }
    };
  },
  computed: {
    isAdmin() {
      const u = getCurrentUser();
      return !!u && u.role === 'admin';
    }
  },
  mounted() {
    this.loadAll();
    this.timer = setInterval(() => this.loadAll(), 30000);
  },
  beforeDestroy() {
    if (this.timer) clearInterval(this.timer);
  },
  methods: {
    async loadAll() {
      this.loading = true;
      try {
        const [engineRes, statsRes, captureRes] = await Promise.all([
          getEngineStatus(),
          getAlertStats(),
          getCaptureStats()
        ]);
        this.engine = engineRes.data || {};
        this.stats = statsRes.data || {};
        this.capture = captureRes.data || {};
        if (!this.dirty) this.applyConfig(this.engine);
      } catch (e) {
        console.error('加载检测引擎信息失败:', e);
      } finally {
        this.loading = false;
      }
    },
    /** 用后端返回的生效值同步表单（不触发 dirty） */
    applyConfig(cfg) {
      if (!cfg) return;
      if (cfg.zThreshold != null) this.form.zThreshold = cfg.zThreshold;
      if (cfg.minAnomalyBps != null) this.form.minAnomalyBps = cfg.minAnomalyBps;
      if (cfg.windowSize != null) this.form.windowSize = cfg.windowSize;
      if (cfg.minSamples != null) this.form.minSamples = cfg.minSamples;
      if (cfg.cooldownMinutes != null) this.form.cooldownMinutes = cfg.cooldownMinutes;
      this.form.deepseekModelOverride = cfg.deepseekModelOverride || '';
    },
    async saveConfig() {
      this.saving = true;
      try {
        const res = await updateAiConfig({
          zThreshold: this.form.zThreshold,
          minAnomalyBps: this.form.minAnomalyBps,
          windowSize: this.form.windowSize,
          minSamples: this.form.minSamples,
          cooldownMinutes: this.form.cooldownMinutes,
          deepseekModel: this.form.deepseekModelOverride || ''
        });
        const d = res.data || {};
        if (d.ok) {
          this.$message.success(d.message || '配置已保存并立即生效');
          this.dirty = false;
          this.applyConfig(d);
          this.engine = { ...this.engine, ...d };
        } else {
          this.$message.error(d.message || '保存失败');
        }
      } catch (e) {
        const msg = e && e.response && e.response.data && e.response.data.message
          ? e.response.data.message : '保存失败，请检查参数或稍后重试';
        this.$message.error(msg);
      } finally {
        this.saving = false;
      }
    },
    async resetConfig() {
      try {
        await this.$confirm('将全部参数恢复为出厂默认值（z=2.0 / 50Kbps / 窗口30 / 样本5 / 冷却5分钟 / 模型回退yml），是否继续？', '恢复默认', { type: 'warning' });
      } catch (e) { return; }
      this.resetting = true;
      try {
        const res = await resetAiConfig();
        const d = res.data || {};
        if (d.ok) {
          this.$message.success(d.message || '已恢复出厂默认配置');
          this.dirty = false;
          this.applyConfig(d);
          this.engine = { ...this.engine, ...d };
        } else {
          this.$message.error(d.message || '恢复失败');
        }
      } catch (e) {
        const msg = e && e.response && e.response.data && e.response.data.message
          ? e.response.data.message : '恢复失败，请稍后重试';
        this.$message.error(msg);
      } finally {
        this.resetting = false;
      }
    },
    formatBps(bps) {
      if (bps == null) return '-';
      if (bps >= 1000000) return (bps / 1000000).toFixed(1) + ' Mbps';
      if (bps >= 1000) return (bps / 1000).toFixed(0) + ' Kbps';
      return bps + ' bps';
    }
  }
};
</script>

<style scoped>
.ai-monitor-container {
  padding: 20px;
  background-color: #f7f8fa;
  min-height: 100%;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

/* 状态卡片：白底 + 语义色图标块 */
.status-card {
  display: flex;
  align-items: center;
  padding: 16px 18px;
  border-radius: 6px;
  background: #fff;
  border: 1px solid #ebeef5;
  color: #303133;
  min-height: 80px;
}
.status-icon {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 14px;
  font-size: 22px;
  flex-shrink: 0;
}
.status-ok .status-icon { background: #f0f9eb; color: #67C23A; }
.status-down .status-icon { background: #fef0f0; color: #F56C6C; }
.status-info-card .status-icon { background: #ecf5ff; color: #3a7bd5; }
.status-label {
  font-size: 13px;
  color: #909399;
}
.status-value {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-top: 4px;
}

/* AI 配置中心 */
.update-meta {
  float: right;
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}
.config-form .el-form-item {
  margin-bottom: 14px;
}
.param-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
.config-actions {
  padding-left: 130px;
  margin-top: 4px;
}

/* 告警统计卡片 */
.stat-card {
  text-align: center;
}
.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}
.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
}
.sev-row {
  display: flex;
  justify-content: space-around;
  font-size: 14px;
}
.sev-item b {
  font-size: 22px;
  margin-right: 4px;
}

/* 检测流程 */
.flow-box {
  padding: 10px 20px;
}
.flow-step {
  display: flex;
  align-items: flex-start;
  padding: 8px 0;
}
.flow-num {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #ecf5ff;
  color: #3a7bd5;
  text-align: center;
  line-height: 28px;
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
  margin-right: 12px;
}
.flow-title {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
}
.flow-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.6;
}
.flow-arrow {
  text-align: center;
  color: #C0C4CC;
  font-size: 18px;
  margin: 2px 0 2px 14px;
}
</style>
