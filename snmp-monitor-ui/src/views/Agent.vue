<template>
  <div class="agent-container">
    <!-- 小七身份横幅 -->
    <div class="agent-hero">
      <div class="hero-left">
        <div class="agent-avatar">
          <span class="avatar-ring"></span>
          <span class="avatar-text">七</span>
          <span class="avatar-badge"><i class="el-icon-success"></i></span>
        </div>
        <div class="agent-meta">
          <div class="agent-name-line">
            <span class="agent-name">智能体 · 小七</span>
            <el-tag size="mini" effect="dark" color="#0b7a3b" border-color="#0b7a3b">
              <span class="on-duty-dot"></span>值守中
            </el-tag>
            <span class="agent-version">{{ profile.version || 'v1.2' }}</span>
          </div>
          <div class="agent-slogan">{{ profile.slogan || '7×24 小时自主值守，先于攻击发现攻击' }}</div>
          <div class="agent-capabilities">
            <el-tag
              v-for="cap in (profile.capabilities || [])"
              :key="cap"
              size="mini"
              effect="plain"
              class="cap-tag"
            >{{ cap }}</el-tag>
          </div>
        </div>
      </div>
      <div class="hero-right">
        <div class="hero-metric">
          <div class="hm-value">{{ stats.adoptionRate || 0 }}%</div>
          <div class="hm-label">建议采纳率</div>
        </div>
        <div class="hero-divider"></div>
        <div class="hero-metric">
          <div class="hm-value">{{ Math.round((stats.avgConfidence || 0) * 100) }}%</div>
          <div class="hm-label">平均置信度</div>
        </div>
        <div class="hero-divider"></div>
        <div class="hero-metric">
          <div class="hm-value">{{ stats.autoExecutedCount || 0 }}</div>
          <div class="hm-label">自动执行</div>
        </div>
      </div>
    </div>

    <!-- 最近一条决策提示条 -->
    <div class="latest-bar" v-if="stats.latest">
      <i class="el-icon-chat-line-square latest-icon"></i>
      <span class="latest-label">小七最新研判：</span>
      <span class="latest-reason">{{ stripSign(stats.latest.reason) }}</span>
      <span class="latest-time">{{ formatTime(stats.latest.actionTime) }}</span>
    </div>

    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" style="margin-bottom: 20px;">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card stat-today">
          <div class="stat-value">{{ stats.todayCount || 0 }}</div>
          <div class="stat-label">今日决策</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card stat-pending">
          <div class="stat-value">{{ stats.pendingCount || 0 }}</div>
          <div class="stat-label">待处理</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card stat-executed">
          <div class="stat-value">{{ stats.executedCount || 0 }}</div>
          <div class="stat-label">已执行</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card stat-rejected">
          <div class="stat-value">{{ stats.rejectedCount || 0 }}</div>
          <div class="stat-label">已驳回</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 决策列表 -->
    <el-card shadow="never">
      <div slot="header">
        <span><i class="el-icon-magic-stick"></i> 小七的决策建议</span>
        <div style="float: right; display: flex; align-items: center; gap: 10px;">
          <el-button size="small" type="warning" plain icon="el-icon-set-up" @click="openPolicy">自动执行策略</el-button>
          <el-select v-model="filterStatus" placeholder="状态筛选" clearable size="small" style="width: 120px;" @change="fetchActions">
            <el-option label="待处理" :value="0" />
            <el-option label="已执行" :value="1" />
            <el-option label="已驳回" :value="2" />
            <el-option label="自动执行" :value="3" />
          </el-select>
          <el-button size="small" type="primary" @click="fetchActions" icon="el-icon-refresh">刷新</el-button>
        </div>
      </div>

      <el-table :data="actions" style="width: 100%;" v-loading="loading" stripe row-key="id">
        <el-table-column type="expand">
          <template slot-scope="scope">
            <div class="detail-expand">
              <div class="detail-title">
                <i class="el-icon-data-analysis"></i> 小七的研判依据
              </div>
              <div class="detail-grid" v-if="parseDetail(scope.row.detail).length">
                <div class="detail-item" v-for="item in parseDetail(scope.row.detail)" :key="item.label">
                  <span class="detail-label">{{ item.label }}</span>
                  <span class="detail-value">{{ item.value }}</span>
                </div>
              </div>
              <div class="detail-note" v-if="scope.row.note">
                <i class="el-icon-edit-outline"></i> 处理备注：{{ scope.row.note }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="actionTime" label="决策时间" width="160">
          <template slot-scope="scope">
            {{ formatTime(scope.row.actionTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="deviceName" label="目标设备" width="130" />
        <el-table-column prop="actionType" label="决策类型" width="120">
          <template slot-scope="scope">
            <el-tag :type="actionTagType(scope.row.actionType)" size="small">
              {{ actionLabel(scope.row.actionType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="severity" label="严重程度" width="90">
          <template slot-scope="scope">
            <el-tag :type="severityTag(scope.row.severity)" size="mini">
              {{ severityLabel(scope.row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="130">
          <template slot-scope="scope">
            <el-progress
              v-if="scope.row.confidence !== null && scope.row.confidence !== undefined"
              :percentage="confidencePercent(scope.row.confidence)"
              :color="confidenceColor(scope.row.confidence)"
              :stroke-width="10"
              :text-inside="true"
            ></el-progress>
            <span v-else style="color: #c0c4cc; font-size: 12px;">历史数据 · —</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="决策原因" show-overflow-tooltip min-width="260">
          <template slot-scope="scope">
            <i class="el-icon-service reason-icon"></i>{{ stripSign(scope.row.reason) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template slot-scope="scope">
            <el-tag :type="statusTagType(scope.row.status)" size="mini">
              {{ statusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template slot-scope="scope">
            <template v-if="scope.row.status === 0">
              <el-button size="mini" type="success" @click="executeAction(scope.row.id)">执行</el-button>
              <el-button size="mini" type="danger" @click="rejectAction(scope.row.id)">驳回</el-button>
            </template>
            <span v-else style="color: #909399; font-size: 12px;">
              {{ scope.row.note || '已处理' }}
            </span>
          </template>
        </el-table-column>

        <template slot="empty">
          <el-empty description="暂无决策建议"></el-empty>
          <div class="empty-tip">小七：网络一切平稳，我正在持续观察中…</div>
        </template>
      </el-table>
    </el-card>

    <!-- ===== 自动执行策略弹窗 ===== -->
    <el-dialog title="小七 · 自动执行策略" :visible.sync="policyVisible" width="720px" custom-class="policy-dialog">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 14px;"
        title="开启后，该类决策一产生即由小七自动放行执行，无需人工确认；关闭则进入待处理列表。"
        description="小七还会学习你的驳回反馈：某类建议被驳回越多，触发门槛越高、重复建议的冷却越长（冷却 5→30 分钟）。"
      ></el-alert>

      <div v-loading="policyLoading">
        <div class="policy-row" v-for="row in policyRows" :key="row.actionType">
          <div class="policy-main">
            <div class="policy-title-line">
              <el-tag :type="actionTagType(row.actionType)" size="small">{{ actionLabel(row.actionType) }}</el-tag>
              <el-switch
                v-model="policyDraft[row.actionType]"
                active-color="#13ce66"
                inactive-color="#c0ccda"
                :disabled="row.actionType === 'ADJUST_THRESHOLD'"
              ></el-switch>
              <span v-if="row.actionType === 'ADJUST_THRESHOLD'" class="policy-soon">规则预留，暂未启用</span>
            </div>
            <div class="policy-desc">{{ POLICY_DESC[row.actionType] }}</div>
          </div>
          <div class="policy-feedback">
            <div class="pf-item">
              <span class="pf-label">生效门槛</span>
              <span class="pf-value">{{ row.thresholdText || '—' }}</span>
            </div>
            <div class="pf-item">
              <span class="pf-label">去重冷却</span>
              <span class="pf-value" :class="{ raised: row.cooldownMin > 5 }">{{ row.cooldownMin }} 分钟</span>
            </div>
            <div class="pf-item">
              <span class="pf-label">采纳率</span>
              <span class="pf-value">{{ row.adoptionRate }}%</span>
            </div>
            <div class="pf-item">
              <span class="pf-label">已驳回</span>
              <span class="pf-value" :class="{ rejected: row.rejected > 0 }">{{ row.rejected }} 次</span>
            </div>
          </div>
        </div>
      </div>

      <span slot="footer">
        <el-button @click="policyVisible = false">取 消</el-button>
        <el-button type="primary" :loading="policySaving" @click="savePolicy">保存策略</el-button>
      </span>
    </el-dialog>

    <!-- ===== 小七悬浮问答窗 ===== -->
    <transition name="chat-pop">
      <div class="chat-panel" v-if="chatVisible">
        <div class="chat-header">
          <div class="chat-header-left">
            <div class="chat-avatar-sm">七</div>
            <div>
              <div class="chat-title">小七 · 安全问答</div>
              <div class="chat-sub"><span class="on-duty-dot"></span>在线，随时为你研判</div>
            </div>
          </div>
          <i class="el-icon-close chat-close" @click="chatVisible = false"></i>
        </div>

        <div class="chat-body" ref="chatBody">
          <div v-for="(msg, idx) in chatMessages" :key="idx" :class="['msg-row', msg.role === 'user' ? 'msg-user' : 'msg-bot']">
            <div class="msg-avatar" v-if="msg.role === 'bot'">七</div>
            <div class="msg-bubble">
              <template v-if="msg.loading">
                <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
              </template>
              <template v-else>
                <div v-if="msg.engine === 'deepseek'" class="msg-ai-tag">AI 驱动 · DeepSeek V4</div>
                <div>{{ msg.text }}</div>
              </template>
            </div>
          </div>

          <!-- 推荐问题 -->
          <div class="chat-suggestions" v-if="!chatLoading && chatSuggestions.length">
            <span
              class="suggest-chip"
              v-for="(s, i) in chatSuggestions"
              :key="i"
              @click="sendChat(s)"
            >{{ s }}</span>
          </div>
        </div>

        <div class="chat-footer">
          <el-input
            v-model="chatInput"
            placeholder="问小七点什么，如：谁离线了？"
            size="small"
            @keyup.enter.native="sendChat()"
            :disabled="chatLoading"
          ></el-input>
          <el-button
            type="primary"
            size="small"
            icon="el-icon-position"
            :loading="chatLoading"
            @click="sendChat()"
          >发送</el-button>
        </div>
      </div>
    </transition>

    <!-- 悬浮唤起按钮 -->
    <div class="chat-fab" v-if="!chatVisible" @click="openChat">
      <span class="fab-ring"></span>
      <span class="fab-text">七</span>
      <span class="fab-label">问问小七</span>
    </div>
  </div>
</template>

<script>
import { createRequest } from '../api/request';

const request = createRequest();

// detail JSON 字段中文名映射
const DETAIL_LABELS = {
  attackType: '攻击类型',
  recentCount: '近期命中次数',
  anomalyScore: '异常评分',
  outBps: '出站流量',
  inBps: '入站流量',
  aiConfidence: 'AI 置信度',
  recentAlertCount: '近期告警数',
  window: '统计窗口'
};

export default {
  name: 'Agent',
  data() {
    return {
      actions: [],
      stats: {},
      profile: {},
      loading: false,
      filterStatus: null,
      // 小七问答窗
      chatVisible: false,
      chatInput: '',
      chatMessages: [],
      chatLoading: false,
      chatSuggestions: ['现在哪些设备离线了？', '最近有什么攻击？', '小七给了哪些处理建议？', '全网流量怎么样？'],
      // 自动执行策略弹窗
      policyVisible: false,
      policyLoading: false,
      policySaving: false,
      policyDraft: {},
      policyRows: [],
      POLICY_DESC: {
        ISOLATE: '同一设备 5 分钟内连续命中 DDoS/蠕虫攻击即生成隔离建议。自动开启后小七将直接执行隔离，高危操作建议保留人工确认。',
        DISCONNECT: '检测到 critical 级数据外泄行为时建议断网。自动开启后无需确认立即断网。',
        ADJUST_THRESHOLD: '全网异常率超阈值时建议调整 AI 检测阈值（规则预留）。',
        ALERT_ESCALATE: '出现 critical 级告警时自动升级通知管理员。风险低，默认自动执行。',
        RETRAIN_MODEL: '10 分钟内高危告警密集时建议重训 AI 模型。自动开启后小七将直接调用训练。'
      }
    };
  },
  mounted() {
    this.fetchProfile();
    this.fetchStats();
    this.fetchActions();
    this.timer = setInterval(() => {
      this.fetchStats();
      this.fetchActions();
    }, 10000);
  },
  beforeDestroy() {
    if (this.timer) clearInterval(this.timer);
  },
  methods: {
    async fetchProfile() {
      try {
        const res = await request.get('/api/agent/profile');
        this.profile = res.data || {};
      } catch (e) {
        console.error('获取小七档案失败:', e);
      }
    },
    async fetchActions() {
      this.loading = true;
      try {
        const params = {};
        if (this.filterStatus !== null && this.filterStatus !== '') {
          params.status = this.filterStatus;
        }
        const res = await request.get('/api/agent/actions', { params });
        this.actions = res.data || [];
      } catch (e) {
        console.error('获取决策列表失败:', e);
      } finally {
        this.loading = false;
      }
    },
    async fetchStats() {
      try {
        const res = await request.get('/api/agent/stats');
        this.stats = res.data || {};
      } catch (e) {
        console.error('获取统计失败:', e);
      }
    },
    // ===== 自动执行策略 =====
    async openPolicy() {
      this.policyVisible = true;
      this.policyLoading = true;
      this.policyDraft = {};
      this.policyRows = [];
      try {
        const [polRes, statsRes] = await Promise.all([
          request.get('/api/agent/policies'),
          request.get('/api/agent/stats')
        ]);
        (polRes.data.policies || []).forEach(p => {
          this.$set(this.policyDraft, p.actionType, !!p.autoExecute);
        });
        this.stats = statsRes.data || {};
        // 以统计接口的固定类型顺序为准（含门槛/驳回明细）
        this.policyRows = (this.stats.byType || []).map(r => Object.assign({}, r));
        // 统计缺失的类型用策略补齐
        (polRes.data.policies || []).forEach(p => {
          if (!this.policyRows.find(r => r.actionType === p.actionType)) {
            this.policyRows.push({
              actionType: p.actionType, thresholdText: '—', cooldownMin: 5,
              adoptionRate: 0, rejected: 0
            });
          }
        });
      } catch (e) {
        this.$message.error('获取小七策略失败');
        console.error('获取策略失败:', e);
      } finally {
        this.policyLoading = false;
      }
    },
    async savePolicy() {
      this.policySaving = true;
      try {
        let username = 'admin';
        try {
          const u = JSON.parse(localStorage.getItem('smart_monitor_user') || '{}');
          if (u.username) username = u.username;
        } catch (e) { /* ignore */ }
        const res = await request.put('/api/agent/policies', {
          updatedBy: username,
          policies: this.policyDraft
        });
        if (res.data.success) {
          this.$message.success('策略已保存，小七已按新策略值守');
          this.policyVisible = false;
          this.fetchStats();
        } else {
          this.$message.error(res.data.message || '保存失败');
        }
      } catch (e) {
        this.$message.error('保存策略失败');
        console.error('保存策略失败:', e);
      } finally {
        this.policySaving = false;
      }
    },
    // ===== 小七问答 =====
    openChat() {
      this.chatVisible = true;
      if (this.chatMessages.length === 0) {
        this.chatMessages.push({
          role: 'bot',
          text: '你好，我是小七，网络安全决策助手。可以直接问我：谁离线了、最近有什么攻击、全网流量怎么样，或点击下方推荐问题。'
        });
      }
      this.$nextTick(this.scrollChatToBottom);
    },
    async sendChat(presetText) {
      const text = (typeof presetText === 'string' ? presetText : this.chatInput).trim();
      if (!text || this.chatLoading) return;
      this.chatMessages.push({ role: 'user', text });
      this.chatInput = '';
      this.chatLoading = true;
      this.chatMessages.push({ role: 'bot', loading: true });
      this.$nextTick(this.scrollChatToBottom);
      try {
        const res = await request.post('/api/agent/chat', { message: text });
        this.chatMessages.pop();
        this.chatMessages.push({
          role: 'bot',
          text: res.data.reply || '（小七暂时没有组织好语言）',
          engine: res.data.engine || 'rule'
        });
        if (Array.isArray(res.data.suggestions)) {
          this.chatSuggestions = res.data.suggestions;
        }
      } catch (e) {
        this.chatMessages.pop();
        this.chatMessages.push({ role: 'bot', text: '小七与后台的连接出现了一点问题，请稍后再试。' });
        console.error('小七问答失败:', e);
      } finally {
        this.chatLoading = false;
        this.$nextTick(this.scrollChatToBottom);
      }
    },
    scrollChatToBottom() {
      const el = this.$refs.chatBody;
      if (el) el.scrollTop = el.scrollHeight;
    },
    async executeAction(id) {
      try {
        const { value } = await this.$prompt('小七已完成研判，确认执行该决策？可填写执行备注（选填）', '小七 · 执行决策', {
          confirmButtonText: '确认执行',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputPlaceholder: '例如：已通知现场运维人员，同意隔离',
          inputValue: '',
          type: 'warning'
        });
        await request.put(`/api/agent/actions/${id}/execute`, null, {
          params: { note: value || '' }
        });
        this.$message.success('小七的决策已执行');
        this.fetchActions();
        this.fetchStats();
      } catch (e) {
        if (e !== 'cancel' && e !== 'close') console.error('执行失败:', e);
      }
    },
    async rejectAction(id) {
      try {
        const { value } = await this.$prompt('确认驳回小七的该条决策？请填写驳回原因（选填）', '小七 · 驳回决策', {
          confirmButtonText: '确认驳回',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputPlaceholder: '例如：误报，该设备正在进行压力测试',
          inputValue: '',
          type: 'warning'
        });
        await request.put(`/api/agent/actions/${id}/reject`, null, {
          params: { note: value || '' }
        });
        this.$message.success('决策已驳回，小七会记录该反馈');
        this.fetchActions();
        this.fetchStats();
      } catch (e) {
        if (e !== 'cancel' && e !== 'close') console.error('驳回失败:', e);
      }
    },
    formatTime(t) {
      if (!t) return '—';
      return String(t).replace('T', ' ').substring(0, 19);
    },
    /** 去掉原因文案中的【小七】署名前缀（表格里避免重复） */
    stripSign(text) {
      if (!text) return '';
      return String(text).replace(/^【小七】/, '');
    },
    confidencePercent(c) {
      if (c === null || c === undefined) return 0;
      return Math.round(c * 100);
    },
    confidenceColor(c) {
      const pct = this.confidencePercent(c);
      if (pct >= 85) return '#67C23A';
      if (pct >= 60) return '#409EFF';
      return '#E6A23C';
    },
    /** 解析决策详情 JSON 为中文名键值对 */
    parseDetail(detail) {
      if (!detail) return [];
      let obj;
      try {
        obj = typeof detail === 'string' ? JSON.parse(detail) : detail;
      } catch (e) {
        return [];
      }
      return Object.keys(obj).map(key => {
        let value = obj[key];
        if (key === 'outBps' || key === 'inBps') {
          value = this.formatBps(value);
        } else if ((key === 'anomalyScore' || key === 'aiConfidence') && typeof value === 'number') {
          value = value.toFixed(4);
        }
        return { label: DETAIL_LABELS[key] || key, value };
      });
    },
    formatBps(bps) {
      const n = Number(bps) || 0;
      if (n >= 1e9) return (n / 1e9).toFixed(2) + ' Gbps';
      if (n >= 1e6) return (n / 1e6).toFixed(2) + ' Mbps';
      if (n >= 1e3) return (n / 1e3).toFixed(2) + ' Kbps';
      return n + ' bps';
    },
    actionLabel(type) {
      const map = {
        ISOLATE: '建议隔离',
        DISCONNECT: '建议断网',
        ADJUST_THRESHOLD: '调整阈值',
        ALERT_ESCALATE: '告警升级',
        RETRAIN_MODEL: '模型重训'
      };
      return map[type] || type;
    },
    actionTagType(type) {
      const map = {
        ISOLATE: 'danger',
        DISCONNECT: 'danger',
        ADJUST_THRESHOLD: 'warning',
        ALERT_ESCALATE: 'warning',
        RETRAIN_MODEL: 'info'
      };
      return map[type] || '';
    },
    severityLabel(s) {
      const map = { critical: '严重', high: '高危', medium: '中危', low: '低危' };
      return map[s] || s;
    },
    severityTag(s) {
      const map = { critical: 'danger', high: 'danger', medium: 'warning', low: 'info' };
      return map[s] || 'info';
    },
    statusLabel(s) {
      const map = { 0: '待处理', 1: '已执行', 2: '已驳回', 3: '自动执行' };
      return map[s] || '未知';
    },
    statusTagType(s) {
      const map = { 0: 'warning', 1: 'success', 2: 'info', 3: 'success' };
      return map[s] || 'info';
    }
  }
};
</script>

<style scoped>
.agent-container {
  padding: 20px;
}

/* ===== 小七身份横幅 ===== */
.agent-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 28px;
  margin-bottom: 16px;
  border-radius: 10px;
  background: linear-gradient(120deg, #1f2d3d 0%, #274059 55%, #1b3a4b 100%);
  box-shadow: 0 4px 16px rgba(31, 45, 61, 0.25);
  color: #fff;
}
.hero-left {
  display: flex;
  align-items: center;
}
.agent-avatar {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #3a7bd5;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 20px;
  box-shadow: 0 2px 10px rgba(31, 45, 61, 0.3);
  flex-shrink: 0;
}
.avatar-text {
  font-size: 34px;
  font-weight: bold;
  color: #fff;
  text-shadow: 0 2px 6px rgba(0, 0, 0, 0.25);
}
.avatar-ring {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid rgba(0, 212, 255, 0.5);
  animation: avatar-pulse 2.4s ease-out infinite;
}
@keyframes avatar-pulse {
  0% { transform: scale(0.95); opacity: 0.9; }
  70% { transform: scale(1.18); opacity: 0; }
  100% { transform: scale(1.18); opacity: 0; }
}
.avatar-badge {
  position: absolute;
  right: -2px;
  bottom: -2px;
  font-size: 20px;
  color: #67C23A;
  background: #fff;
  border-radius: 50%;
  line-height: 1;
}
.agent-name-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
.agent-name {
  font-size: 22px;
  font-weight: bold;
  letter-spacing: 1px;
}
.agent-version {
  font-size: 12px;
  color: #8fb3d9;
  border: 1px solid rgba(143, 179, 217, 0.4);
  border-radius: 10px;
  padding: 1px 8px;
}
.on-duty-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67ff9e;
  margin-right: 4px;
  animation: ws-pulse 1.6s infinite;
}
@keyframes ws-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}
.agent-slogan {
  margin-top: 8px;
  font-size: 13px;
  color: #b8cce4;
}
.agent-capabilities {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.cap-tag {
  background: rgba(64, 158, 255, 0.12) !important;
  border-color: rgba(64, 158, 255, 0.45) !important;
  color: #9dccff !important;
}
.hero-right {
  display: flex;
  align-items: center;
}
.hero-metric {
  text-align: center;
  padding: 0 22px;
}
.hm-value {
  font-size: 26px;
  font-weight: bold;
  color: #00e5ff;
}
.hm-label {
  font-size: 12px;
  color: #8fb3d9;
  margin-top: 4px;
}
.hero-divider {
  width: 1px;
  height: 36px;
  background: rgba(255, 255, 255, 0.15);
}

/* ===== 最新研判提示条 ===== */
.latest-bar {
  display: flex;
  align-items: center;
  background: #ecf5ff;
  border-left: 3px solid #409EFF;
  border-radius: 4px;
  padding: 10px 14px;
  margin-bottom: 16px;
  font-size: 13px;
}
.latest-icon {
  color: #409EFF;
  font-size: 16px;
  margin-right: 8px;
}
.latest-label {
  color: #303133;
  font-weight: bold;
  white-space: nowrap;
}
.latest-reason {
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.latest-time {
  color: #909399;
  font-size: 12px;
  margin-left: 12px;
  white-space: nowrap;
}

/* ===== 统计卡片 ===== */
.stat-card {
  text-align: center;
  padding: 10px 0;
}
.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409EFF;
}
.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 4px;
}
.stat-today .stat-value { color: #409EFF; }
.stat-pending .stat-value { color: #E6A23C; }
.stat-executed .stat-value { color: #67C23A; }
.stat-rejected .stat-value { color: #909399; }

/* ===== 研判依据展开区 ===== */
.detail-expand {
  padding: 12px 24px 16px 50px;
  background: #fafbfc;
}
.detail-title {
  font-size: 13px;
  font-weight: bold;
  color: #303133;
  margin-bottom: 10px;
}
.detail-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 28px;
}
.detail-item {
  font-size: 13px;
}
.detail-label {
  color: #909399;
  margin-right: 8px;
}
.detail-value {
  color: #303133;
  font-weight: 600;
}
.detail-note {
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}
.reason-icon {
  color: #409EFF;
  margin-right: 4px;
}
.empty-tip {
  text-align: center;
  color: #909399;
  font-size: 13px;
  margin-top: -10px;
}

/* ===== 小七悬浮问答窗 ===== */
.chat-fab {
  position: fixed;
  right: 32px;
  bottom: 36px;
  width: 62px;
  height: 62px;
  border-radius: 50%;
  background: #3a7bd5;
  box-shadow: 0 4px 14px rgba(31, 45, 61, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 1000;
  transition: transform 0.2s;
}
.chat-fab:hover { transform: translateY(-3px) scale(1.05); }
.fab-text {
  font-size: 26px;
  font-weight: bold;
  color: #fff;
  z-index: 2;
}
.fab-label {
  position: absolute;
  bottom: -22px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: #409EFF;
  white-space: nowrap;
  font-weight: 600;
}
.fab-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 2px solid rgba(0, 212, 255, 0.55);
  animation: avatar-pulse 2.4s ease-out infinite;
}
.chat-panel {
  position: fixed;
  right: 32px;
  bottom: 36px;
  width: 380px;
  height: 540px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(31, 45, 61, 0.28);
  z-index: 1001;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-pop-enter-active, .chat-pop-leave-active {
  transition: all 0.22s ease;
  transform-origin: bottom right;
}
.chat-pop-enter, .chat-pop-leave-to {
  opacity: 0;
  transform: scale(0.85) translateY(12px);
}
.chat-header {
  background: linear-gradient(120deg, #1f2d3d 0%, #274059 70%);
  color: #fff;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.chat-header-left {
  display: flex;
  align-items: center;
}
.chat-avatar-sm {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: #3a7bd5;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 10px;
}
.chat-title { font-size: 15px; font-weight: bold; }
.chat-sub { font-size: 11px; color: #9dccff; margin-top: 2px; }
.chat-close {
  cursor: pointer;
  font-size: 18px;
  color: #b8cce4;
}
.chat-close:hover { color: #fff; }
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: #f5f7fa;
}
.msg-row {
  display: flex;
  margin-bottom: 12px;
  align-items: flex-start;
}
.msg-user {
  flex-direction: row-reverse;
}
.msg-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #3a7bd5;
  color: #fff;
  font-size: 14px;
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 8px;
  flex-shrink: 0;
}
.msg-bubble {
  max-width: 76%;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-bot .msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #ebeef5;
  border-top-left-radius: 2px;
}
.msg-ai-tag {
  display: inline-block;
  font-size: 11px;
  color: #7c5cff;
  background: #f2effd;
  border: 1px solid #d8ceff;
  border-radius: 3px;
  padding: 1px 6px;
  margin-bottom: 6px;
}
.msg-user .msg-bubble {
  background: #409EFF;
  color: #fff;
  border-top-right-radius: 2px;
}
.typing-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #b3b8bf;
  margin: 0 2px;
  animation: typing-bounce 1.2s infinite ease-in-out;
}
.typing-dot:nth-child(2) { animation-delay: 0.18s; }
.typing-dot:nth-child(3) { animation-delay: 0.36s; }
@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
  30% { transform: translateY(-5px); opacity: 1; }
}
.chat-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-left: 38px;
  margin-bottom: 4px;
}
.suggest-chip {
  font-size: 12px;
  color: #409EFF;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 12px;
  padding: 3px 10px;
  cursor: pointer;
  transition: all 0.15s;
}
.suggest-chip:hover {
  background: #409EFF;
  color: #fff;
}
.chat-footer {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #ebeef5;
  background: #fff;
}

/* ===== 自动执行策略弹窗 ===== */
.policy-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 8px;
  border-bottom: 1px dashed #ebeef5;
}
.policy-row:last-child {
  border-bottom: none;
}
.policy-main {
  flex: 1;
  min-width: 0;
  padding-right: 18px;
}
.policy-title-line {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.policy-soon {
  font-size: 12px;
  color: #909399;
}
.policy-desc {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.policy-feedback {
  display: flex;
  gap: 18px;
  flex-shrink: 0;
}
.pf-item {
  text-align: center;
  min-width: 58px;
}
.pf-label {
  display: block;
  font-size: 11px;
  color: #909399;
  margin-bottom: 4px;
  white-space: nowrap;
}
.pf-value {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
}
.pf-value.raised {
  color: #E6A23C;
}
.pf-value.rejected {
  color: #F56C6C;
}
</style>
