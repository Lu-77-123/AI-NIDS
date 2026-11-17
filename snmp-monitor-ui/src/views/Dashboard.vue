<template>
  <div class="dashboard-container">
    <!-- 首次加载骨架屏 -->
    <div v-if="firstLoading" class="skeleton-wrap">
      <el-row :gutter="20" class="metric-row">
        <el-col :span="6" v-for="i in 4" :key="i">
          <div class="skeleton-card"></div>
        </el-col>
      </el-row>
      <div class="skeleton-chart"></div>
    </div>

    <template v-else>
    <!-- 顶部标题栏 -->
    <div class="dashboard-header">
      <div class="header-title">区域网络流量实时监控大屏</div>
      <div class="header-time">{{ currentTime }}</div>
    </div>

    <!-- 顶部指标卡片 -->
    <el-row :gutter="20" class="metric-row">
      <el-col :span="6">
        <div class="metric-card metric-card-1">
          <div class="metric-icon"><i class="el-icon-monitor"></i></div>
          <div class="metric-info">
            <div class="metric-label">在线设备数</div>
            <div class="metric-value">{{ displayMetrics.online }} <span class="metric-total">/ {{ overview.totalDevices }}</span></div>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="metric-card metric-card-2">
          <div class="metric-icon"><i class="el-icon-bottom"></i></div>
          <div class="metric-info">
            <div class="metric-label">当前总入流量</div>
            <div class="metric-value">{{ formatBps(displayMetrics.inBps) }}</div>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="metric-card metric-card-3">
          <div class="metric-icon"><i class="el-icon-top"></i></div>
          <div class="metric-info">
            <div class="metric-label">当前总出流量</div>
            <div class="metric-value">{{ formatBps(displayMetrics.outBps) }}</div>
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="metric-card metric-card-4">
          <div class="metric-icon"><i class="el-icon-warning"></i></div>
          <div class="metric-info">
            <div class="metric-label">异常告警数</div>
            <div class="metric-value" :class="displayMetrics.anomaly > 0 ? 'pulse-red' : ''">{{ displayMetrics.anomaly }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 总流量 + 各设备流量 并排 -->
    <el-row :gutter="16">
      <el-col :span="12">
        <div class="chart-wrapper">
          <div class="chart-header">
            <span class="chart-title">总流量趋势</span>
            <span class="chart-sub">全网入/出流量汇总（bps）</span>
          </div>
          <div ref="trafficChart" class="traffic-chart"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-wrapper">
          <div class="chart-header">
            <span class="chart-title">各设备流量趋势</span>
            <span class="chart-sub">每台设备入流量（bps）· 点击图例切换</span>
          </div>
          <div ref="deviceTrafficChart" class="traffic-chart"></div>
        </div>
      </el-col>
    </el-row>

    <!-- AI 异常检测散点图 -->
    <div class="chart-wrapper">
      <div class="chart-header">
        <span class="chart-title">AI 异常检测散点图</span>
        <span class="chart-sub">稳健统计异常评分 · 红点为异常</span>
      </div>
      <div ref="anomalyChart" class="anomaly-chart"></div>
    </div>

    <!-- 所有设备最新流量列表 -->
    <div class="chart-wrapper table-wrapper">
      <div class="chart-header">
        <span class="chart-title">设备实时流量榜</span>
        <span class="chart-sub">按入速率降序 · WebSocket 秒级刷新</span>
      </div>
      <el-table :data="topDevices" size="mini" border stripe :row-class-name="deviceRowClass" style="width: 100%;">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="name" label="设备名称" min-width="200">
          <template slot-scope="s">{{ s.row.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="层级" width="90" align="center">
          <template slot-scope="s">
            <el-tag size="mini" :type="categoryTag(s.row.category)">{{ categoryName(s.row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入方向" width="130" align="center">
          <template slot-scope="s">{{ s.row.offline ? '—' : formatBps(s.row.inBps) }}</template>
        </el-table-column>
        <el-table-column label="出方向" width="130" align="center">
          <template slot-scope="s">{{ s.row.offline ? '—' : formatBps(s.row.outBps) }}</template>
        </el-table-column>
        <el-table-column label="异常评分" width="110" align="center">
          <template slot-scope="s">
            <span v-if="s.row.offline" style="color:#909399;">—</span>
            <span v-else :style="{ color: s.row.isAnomaly ? '#FF453A' : '#34C759', fontWeight: s.row.isAnomaly ? 'bold' : 'normal' }">
              {{ s.row.anomalyScore != null ? Number(s.row.anomalyScore).toFixed(4) : '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template slot-scope="s">
            <el-tag size="mini" :type="s.row.offline ? 'danger' : 'success'">{{ s.row.offline ? '离线' : '在线' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
    </template>
  </div>
</template>

<script>
import echarts from '../utils/echarts';
import WebSocketClient from '../utils/websocket';
import { getTrafficOverview, getHistoryTotalTraffic } from '../api/metrics';

function toPlain(obj) {
  try { return obj == null ? obj : JSON.parse(JSON.stringify(obj)); }
  catch (e) { return obj; }
}

export default {
  name: 'Dashboard',
  data() {
    return {
      chart: null,
      anomalyChart: null,
      deviceTrafficChart: null,
      wsClient: null,
      overviewTimer: null,
      timeTimer: null,
      currentTime: '',
      fetching: false,
      firstLoading: true,
      overview: {
        totalDevices: 0,
        onlineCount: 0,
        totalInBps: 0,
        totalOutBps: 0,
        anomalyCount: 0
      },
      timeAxis: [],
      inBpsSeries: [],
      outBpsSeries: [],
      anomalyScatterData: [],   // 异常散点图数据
      deviceTrafficData: {},    // { deviceId: { name, series: [bps, bps, ...] } }
      deviceTimeAxis: [],       // 设备趋势图独立时间轴（30 点窗口）
      maxPoints: 30,
      wsHandler: null,
      devices: [],
      // 指标卡片动画显示值（数字滚动）
      displayMetrics: { online: 0, inBps: 0, outBps: 0, anomaly: 0 },
      animFrame: null
    };
  },
  watch: {
    overview: {
      deep: true,
      handler(val) {
        this.animateMetrics(val);
      }
    }
  },
  computed: {
    topDevices() {
      const arr = (this.devices || []).slice();
      arr.sort((a, b) => Number(b.inBps || 0) - Number(a.inBps || 0));
      return arr;
    }
  },
  mounted() {
    this.fetchOverview();
    this.loadHistoryTotal();
    this.startPolling();
    this.connectWebSocket();
    this.updateTime();
    this.timeTimer = setInterval(this.updateTime, 1000);
    window.addEventListener('resize', this.handleResize);
  },
  beforeDestroy() {
    if (this.animFrame) cancelAnimationFrame(this.animFrame);
    if (this.overviewTimer) {
      clearInterval(this.overviewTimer);
      this.overviewTimer = null;
    }
    if (this.timeTimer) {
      clearInterval(this.timeTimer);
      this.timeTimer = null;
    }
    if (this.wsClient && this.wsHandler) {
      this.wsClient.offMessage(this.wsHandler);
    }
    this.wsHandler = null;
    this.wsClient = null;
    if (this.chart) {
      try { this.chart.dispose(); } catch (e) { /* ignore */ }
      this.chart = null;
    }
    if (this.deviceTrafficChart) {
      try { this.deviceTrafficChart.dispose(); } catch (e) { /* ignore */ }
      this.deviceTrafficChart = null;
    }
    if (this.anomalyChart) {
      try { this.anomalyChart.dispose(); } catch (e) { /* ignore */ }
      this.anomalyChart = null;
    }
    window.removeEventListener('resize', this.handleResize);
  },
  methods: {
    initChart() {
      this.chart = echarts.init(this.$refs.trafficChart, 'dark');
      const option = {
        backgroundColor: 'transparent',
        tooltip: {
          trigger: 'axis',
          backgroundColor: 'rgba(15, 35, 70, 0.92)',
          borderColor: '#1E90FF',
          borderWidth: 1,
          textStyle: { color: '#fff' },
          formatter: (params) => {
            let html = `<div style="font-weight:bold;margin-bottom:6px;">${params[0].axisValue}</div>`;
            params.forEach(p => {
              html += `<div>${p.marker} ${p.seriesName}: <b>${this.formatBps(p.value)}</b></div>`;
            });
            return html;
          }
        },
        legend: {
          data: ['入流量', '出流量'],
          textStyle: { color: '#c0d4e8' },
          top: 10,
          right: 20
        },
        grid: { top: 60, left: 70, right: 40, bottom: 60 },
        dataZoom: [
          { type: 'inside', start: 0, end: 100 },
          { type: 'slider', height: 16, bottom: 8, start: 0, end: 100,
            textStyle: { color: '#a9c0d8' } }
        ],
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.timeAxis.slice(),
          axisLine: { lineStyle: { color: '#2a4d7a' } },
          axisLabel: { color: '#a9c0d8' }
        },
        yAxis: {
          type: 'value',
          axisLine: { lineStyle: { color: '#2a4d7a' } },
          axisLabel: {
            color: '#a9c0d8',
            formatter: (val) => this.formatBps(val)
          },
          splitLine: { lineStyle: { color: 'rgba(42, 77, 122, 0.3)' } }
        },
        series: [
          {
            name: '入流量',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: this.inBpsSeries.slice(),
            lineStyle: { width: 3, color: '#00E5FF', shadowColor: '#00E5FF', shadowBlur: 12 },
            itemStyle: { color: '#00E5FF' },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(0, 229, 255, 0.35)' },
                { offset: 1, color: 'rgba(0, 229, 255, 0.02)' }
              ])
            }
          },
          {
            name: '出流量',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: this.outBpsSeries.slice(),
            lineStyle: { width: 3, color: '#FFB300', shadowColor: '#FFB300', shadowBlur: 12 },
            itemStyle: { color: '#FFB300' },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(255, 179, 0, 0.35)' },
                { offset: 1, color: 'rgba(255, 179, 0, 0.02)' }
              ])
            }
          }
        ]
      };
      this.chart.setOption(option);
    },
    fetchOverview() {
      if (this.fetching) return;
      this.fetching = true;
      getTrafficOverview()
        .then(res => {
          const data = res.data || {};
          this.overview.totalDevices = data.totalDevices != null ? Number(data.totalDevices) : this.overview.totalDevices;
          this.overview.onlineCount  = data.onlineCount  != null ? Number(data.onlineCount)  : 0;
          this.overview.totalInBps   = Number(data.totalInBps)  || 0;
          this.overview.totalOutBps  = Number(data.totalOutBps) || 0;
          this.overview.anomalyCount = data.anomalyCount != null ? Number(data.anomalyCount) : 0;
        })
        .catch(err => {
          console.warn('[Dashboard] overview 请求失败:', err && err.message);
        })
        .finally(() => {
          this.fetching = false;
          if (this.firstLoading) {
            this.firstLoading = false;
            // 骨架屏隐藏后 DOM 才渲染，需等下一帧再初始化 echarts
            this.$nextTick(() => {
              try {
                // 图表 DOM 此时才存在；清空初始化前 WS 已累积的缓冲，避免旧数据格式/长度错位
                this.anomalyScatterData = [];
                this.deviceTrafficData = {};
                this.deviceTimeAxis = [];
                this.initChart();
                this.initAnomalyChart();
                this.initDeviceTrafficChart();
              } catch (e) {
                console.warn('[Dashboard] 图表初始化失败:', e);
              }
            });
          }
        });
    },
    startPolling() {
      // 保险：创建前先清，避免多实例叠加
      if (this.overviewTimer) clearInterval(this.overviewTimer);
      this.overviewTimer = setInterval(() => this.fetchOverview(), 10000);
    },
    async loadHistoryTotal() {
      try {
        const res = await getHistoryTotalTraffic();
        const rows = res.data || [];
        if (rows.length === 0) return;
        // 用历史数据填充总流量图表
        this.timeAxis = rows.map(r => r.time);
        this.inBpsSeries = rows.map(r => Number(r.totalIn) || 0);
        this.outBpsSeries = rows.map(r => Number(r.totalOut) || 0);
        // 只保留最近 maxPoints 个点用于实时追加，但首次展示全部历史
        this.updateChart();
      } catch (e) {
        console.warn('[Dashboard] 加载历史总流量失败:', e);
      }
    },
    connectWebSocket() {
      this.wsClient = new WebSocketClient();
      this.wsHandler = (data) => this.handleWsMessage(data);
      this.wsClient.onMessage(this.wsHandler);
      this.wsClient.connect();
    },
    handleWsMessage(data) {
      if (!data) return;
      const devices = Array.isArray(data.devices) ? data.devices : [];
      let totalIn = 0;
      let totalOut = 0;
      let anomaly = 0;
      let online = 0;
      const mapped = devices.map(d => {
        const inBps  = Number(d.inBps)  || 0;
        const outBps = Number(d.outBps) || 0;
        const offline = (d.online === false);
        const anomalyScore = Number(d.anomalyScore) || 0;
        const isAnomaly = !!d.isAnomaly;
        totalIn  += inBps;
        totalOut += outBps;
        if (!offline) online += 1;
        if (isAnomaly) anomaly += 1;
        return {
          deviceId: d.deviceId,
          name: d.deviceName || (d.ip ? String(d.ip) : String(d.deviceId)),
          category: Number(d.category),
          inBps, outBps, offline,
          anomalyScore,
          isAnomaly
        };
      });
      // 按 id 去重合并到 devices 表
      const idMap = new Map(this.devices.map(x => [String(x.deviceId), x]));
      mapped.forEach(x => idMap.set(String(x.deviceId), x));
      this.devices = Array.from(idMap.values());

      // --- 更新概览卡（保证实时性，优先级低于服务端 HTTP 轮询，用最近 2 次 / HTTP 中较大的值） ---
      this.overview.totalInBps   = Math.max(this.overview.totalInBps  || 0, totalIn);
      this.overview.totalOutBps  = Math.max(this.overview.totalOutBps || 0, totalOut);
      this.overview.anomalyCount = anomaly;
      if (devices.length > 0) {
        // WS 的 online 是实时统计；totalDevices 以 HTTP 为准
        this.overview.onlineCount = online;
        if (!this.overview.totalDevices) this.overview.totalDevices = devices.length;
      }

      // --- 图表曲线 ---
      const timestamp = data.timestamp || new Date().getTime();
      const timeStr = this.formatTime(timestamp);
      const lastTime = this.timeAxis.length ? this.timeAxis[this.timeAxis.length - 1] : '';
      // 避免 WS 抖动把同一秒反复 push（造成"一条线多条点"的折线毛刺，也避免 30 个窗口很快被同一秒刷空）
      if (timeStr !== lastTime) {
        this.timeAxis.push(timeStr);
        this.inBpsSeries.push(totalIn);
        this.outBpsSeries.push(totalOut);
        // 总流量图保留更多历史数据（最多 500 个点）
        while (this.timeAxis.length > 500) {
          this.timeAxis.shift();
          this.inBpsSeries.shift();
          this.outBpsSeries.shift();
        }
        this.updateChart();
        // 更新各设备流量趋势图
        this.updateDeviceTrafficChart(mapped, timestamp);
        // 更新异常散点图
        this.updateAnomalyChart(mapped, timestamp);
      }
    },
    updateChart() {
      if (!this.chart) return;
      const patch = {
        xAxis: { data: this.timeAxis.slice() },
        series: [
          { data: this.inBpsSeries.slice() },
          { data: this.outBpsSeries.slice() }
        ]
      };
      try {
        this.chart.setOption(toPlain(patch), false);
      } catch (e) {
        console.warn('[Dashboard] chart setOption 失败:', e);
      }
    },
    initDeviceTrafficChart() {
      if (this.deviceTrafficChart) {
        try { this.deviceTrafficChart.dispose(); } catch (e) { /* ignore */ }
      }
      this.deviceTrafficChart = echarts.init(this.$refs.deviceTrafficChart, 'dark');
      this.renderDeviceTrafficChart();
    },
    // 构建设备趋势图的完整 option（每次全量重建，避免增量合并的状态错位）
    buildDeviceTrafficOption() {
      const colors = ['#00E5FF', '#FFB300', '#FF453A', '#34C759', '#BF5AF2',
                      '#FF9F0A', '#64D2FF', '#FF375F', '#5AC8FA', '#FFD60A',
                      '#32D74B', '#0A84FF'];
      const ids = Object.keys(this.deviceTrafficData);
      const series = ids.map((id, i) => {
        const color = colors[i % colors.length];
        return {
          name: this.deviceTrafficData[id].name,
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: this.deviceTrafficData[id].series.slice(),
          lineStyle: { width: 2, color },
          itemStyle: { color }
        };
      });
      const hasData = ids.length > 0 && this.deviceTimeAxis.length > 0;
      return {
        backgroundColor: 'transparent',
        tooltip: {
          trigger: 'axis',
          backgroundColor: 'rgba(15, 35, 70, 0.92)',
          borderColor: '#2a4d7a',
          textStyle: { color: '#fff' },
          formatter: (params) => {
            const arr = Array.isArray(params) ? params : [];
            let html = arr.length ? `<div style="font-weight:bold;margin-bottom:6px;">${arr[0].axisValue}</div>` : '';
            arr.forEach(p => {
              html += `<div>${p.marker} ${p.seriesName}: ${this.formatBps(p.value)}</div>`;
            });
            return html;
          }
        },
        legend: {
          type: 'scroll',
          textStyle: { color: '#c0d4e8', fontSize: 11 },
          top: 5,
          left: 'center',
          pageTextStyle: { color: '#c0d4e8' }
        },
        grid: { top: 50, left: 70, right: 40, bottom: 40 },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.deviceTimeAxis.slice(),
          axisLine: { lineStyle: { color: '#2a4d7a' } },
          axisLabel: { color: '#a9c0d8' }
        },
        yAxis: {
          type: 'value',
          axisLine: { lineStyle: { color: '#2a4d7a' } },
          axisLabel: {
            color: '#a9c0d8',
            formatter: (val) => this.formatBps(val)
          },
          splitLine: { lineStyle: { color: 'rgba(42, 77, 122, 0.3)' } }
        },
        series,
        // 无数据时显示占位提示，有数据后自动移除
        graphic: hasData ? [] : [{
          type: 'text',
          left: 'center',
          top: 'middle',
          style: {
            text: '正在接收实时流量数据...',
            fontSize: 14,
            fill: '#5a7a9e'
          }
        }]
      };
    },
    renderDeviceTrafficChart() {
      if (!this.deviceTrafficChart || !this.$refs.deviceTrafficChart) return;
      try {
        this.deviceTrafficChart.setOption(this.buildDeviceTrafficOption(), { notMerge: true });
      } catch (e) {
        // 自愈：实例损坏（如 DOM 被重建）时销毁重初始化再渲染一次
        console.warn('[Dashboard] deviceTrafficChart 渲染失败，重建实例:', e);
        try { this.deviceTrafficChart.dispose(); } catch (e2) { /* ignore */ }
        this.deviceTrafficChart = echarts.init(this.$refs.deviceTrafficChart, 'dark');
        try {
          this.deviceTrafficChart.setOption(this.buildDeviceTrafficOption(), { notMerge: true });
        } catch (e3) {
          console.warn('[Dashboard] deviceTrafficChart 重建后仍失败:', e3);
        }
      }
    },
    updateDeviceTrafficChart(devices, timestamp) {
      if (!this.deviceTrafficChart) return;
      const timeStr = this.formatTime(timestamp);
      // 设备趋势图使用独立时间轴（30 点滑动窗口）
      this.deviceTimeAxis.push(timeStr);
      while (this.deviceTimeAxis.length > this.maxPoints) {
        this.deviceTimeAxis.shift();
      }
      // 更新每台设备的数据
      devices.forEach(d => {
        const id = String(d.deviceId);
        if (!this.deviceTrafficData[id]) {
          this.deviceTrafficData[id] = { name: d.name || id, series: [] };
        }
        this.deviceTrafficData[id].name = d.name || id;
        this.deviceTrafficData[id].series.push(Number(d.inBps) || 0);
        while (this.deviceTrafficData[id].series.length > this.maxPoints) {
          this.deviceTrafficData[id].series.shift();
        }
      });
      // 中途加入的设备序列可能比时间轴短，头部补 null 保证与 x 轴对齐
      const axisLen = this.deviceTimeAxis.length;
      Object.keys(this.deviceTrafficData).forEach(id => {
        const info = this.deviceTrafficData[id];
        while (info.series.length < axisLen) {
          info.series.unshift(null);
        }
      });
      this.renderDeviceTrafficChart();
    },
    initAnomalyChart() {
      this.anomalyChart = echarts.init(this.$refs.anomalyChart, 'dark');
      const option = {
        backgroundColor: 'transparent',
        tooltip: {
          backgroundColor: 'rgba(15, 35, 70, 0.92)',
          borderColor: '#FF453A',
          borderWidth: 1,
          textStyle: { color: '#fff' },
          formatter: (params) => {
            const d = params.value;
            const time = this.formatTime(d[0]);
            const score = Number(d[1]).toFixed(4);
            const dev = d[2] || '-';
            const inBps = this.formatBps(d[3]);
            const outBps = this.formatBps(d[4]);
            const status = d[5] ? '<span style="color:#FF453A;font-weight:bold;">异常</span>' : '<span style="color:#34C759;">正常</span>';
            return `<div style="font-weight:bold;margin-bottom:6px;">${time}</div>` +
                   `<div>设备: ${dev}</div>` +
                   `<div>异常分: <b>${score}</b> (${status})</div>` +
                   `<div>入流量: ${inBps}</div>` +
                   `<div>出流量: ${outBps}</div>`;
          }
        },
        legend: {
          data: ['正常', '异常'],
          textStyle: { color: '#c0d4e8' },
          top: 10,
          right: 20
        },
        grid: { top: 60, left: 70, right: 40, bottom: 40 },
        xAxis: {
          type: 'value',
          name: '时间',
          nameTextStyle: { color: '#a9c0d8' },
          scale: true,
          axisLine: { lineStyle: { color: '#2a4d7a' } },
          axisLabel: {
            color: '#a9c0d8',
            formatter: (val) => this.formatTime(val)
          },
          splitLine: { lineStyle: { color: 'rgba(42, 77, 122, 0.3)' } }
        },
        yAxis: {
          type: 'value',
          name: '异常评分',
          nameTextStyle: { color: '#a9c0d8' },
          min: -0.5,
          max: 0.5,
          axisLine: { lineStyle: { color: '#2a4d7a' } },
          axisLabel: { color: '#a9c0d8' },
          splitLine: { lineStyle: { color: 'rgba(42, 77, 122, 0.3)' } }
        },
        series: [
          {
            name: '正常',
            type: 'scatter',
            symbolSize: 10,
            data: [],
            itemStyle: {
              color: '#34C759',
              opacity: 0.8,
              shadowBlur: 8,
              shadowColor: 'rgba(52, 199, 89, 0.6)'
            }
          },
          {
            name: '异常',
            type: 'scatter',
            symbolSize: 14,
            data: [],
            itemStyle: {
              color: '#FF453A',
              opacity: 1,
              shadowBlur: 15,
              shadowColor: 'rgba(255, 69, 58, 0.8)'
            },
            emphasis: {
              itemStyle: {
                borderColor: '#FFEB3B',
                borderWidth: 2
              }
            }
          }
        ]
      };
      this.anomalyChart.setOption(option);
    },
    updateAnomalyChart(devices, timestamp) {
      if (!this.anomalyChart) return;
      const normalData = [];
      const anomalyData = [];
      devices.forEach(d => {
        const score = Number(d.anomalyScore) || 0;
        const isAnomaly = !!d.isAnomaly;
        // ECharts 散点取数组前两位作 [X, Y]：X=时间戳, Y=异常评分；其余维度供 tooltip 使用
        const point = [
          timestamp,
          score,
          d.name || '-',
          Number(d.inBps) || 0,
          Number(d.outBps) || 0,
          isAnomaly
        ];
        if (isAnomaly) {
          anomalyData.push(point);
        } else {
          normalData.push(point);
        }
      });
      // 保存历史数据
      this.anomalyScatterData.push({ timestamp, normalData, anomalyData });
      while (this.anomalyScatterData.length > this.maxPoints) {
        this.anomalyScatterData.shift();
      }
      // 合并所有历史数据
      const allNormal = [];
      const allAnomaly = [];
      this.anomalyScatterData.forEach(item => {
        allNormal.push(...item.normalData);
        allAnomaly.push(...item.anomalyData);
      });
      const patch = {
        series: [
          { data: allNormal },
          { data: allAnomaly }
        ]
      };
      try {
        this.anomalyChart.setOption(toPlain(patch), false);
      } catch (e) {
        console.warn('[Dashboard] anomalyChart setOption 失败:', e);
      }
    },
    formatBps(value) {
      const v = Number(value) || 0;
      if (v < 1000) return v + ' bps';
      if (v < 1000000) return (v / 1000).toFixed(1) + ' Kbps';
      if (v < 1000000000) return (v / 1000000).toFixed(1) + ' Mbps';
      return (v / 1000000000).toFixed(2) + ' Gbps';
    },
    formatTime(ts) {
      let d;
      if (typeof ts === 'number') d = ts < 1e12 ? new Date(ts * 1000) : new Date(ts);
      else d = new Date(ts);
      const pad = (n) => (n < 10 ? '0' + n : '' + n);
      return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    },
    updateTime() {
      this.currentTime = this.formatTime(new Date().getTime());
    },
    handleResize() {
      if (this.chart) this.chart.resize();
      if (this.deviceTrafficChart) this.deviceTrafficChart.resize();
      if (this.anomalyChart) this.anomalyChart.resize();
    },
    /** 指标卡片数字滚动动画（easeOutCubic，600ms） */
    animateMetrics(target) {
      if (this.animFrame) cancelAnimationFrame(this.animFrame);
      const start = {
        online: this.displayMetrics.online,
        inBps: this.displayMetrics.inBps,
        outBps: this.displayMetrics.outBps,
        anomaly: this.displayMetrics.anomaly
      };
      const end = {
        online: Number(target.onlineCount) || 0,
        inBps: Number(target.totalInBps) || 0,
        outBps: Number(target.totalOutBps) || 0,
        anomaly: Number(target.anomalyCount) || 0
      };
      const duration = 600;
      const t0 = performance.now();
      const ease = (t) => 1 - Math.pow(1 - t, 3);
      const step = (now) => {
        const p = Math.min(1, (now - t0) / duration);
        const e = ease(p);
        this.displayMetrics = {
          online: Math.round(start.online + (end.online - start.online) * e),
          inBps: start.inBps + (end.inBps - start.inBps) * e,
          outBps: start.outBps + (end.outBps - start.outBps) * e,
          anomaly: Math.round(start.anomaly + (end.anomaly - start.anomaly) * e)
        };
        if (p < 1) {
          this.animFrame = requestAnimationFrame(step);
        } else {
          this.animFrame = null;
        }
      };
      this.animFrame = requestAnimationFrame(step);
    },
    categoryName(category) {
      const names = ['核心层', '汇聚层', '接入层', '终端层'];
      const c = Number(category);
      return names[c] || '-';
    },
    categoryTag(category) {
      const c = Number(category);
      return ['primary', 'success', 'warning', 'info'][c] || '';
    },
    /** 离线设备整行置灰 */
    deviceRowClass({ row }) {
      return row.offline ? 'offline-row' : '';
    }
  }
};
</script>

<style scoped>
.dashboard-container {
  min-height: 100%;
  background: linear-gradient(135deg, #0a1a3f 0%, #0d2a5c 50%, #08163a 100%);
  padding: 20px;
  box-sizing: border-box;
  color: #e6f0ff;
  overflow: auto;
}
.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding: 0 8px;
}
.header-title {
  font-size: 24px;
  font-weight: bold;
  letter-spacing: 2px;
  background: linear-gradient(90deg, #00E5FF, #4FC3F7);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.header-time {
  font-size: 16px;
  color: #7fb6e8;
  font-family: 'Consolas', monospace;
}
.metric-row { margin-bottom: 20px; }

/* 骨架屏 */
.skeleton-wrap .skeleton-card {
  height: 90px;
  border-radius: 12px;
  background: linear-gradient(90deg, #1a2a45 25%, #243a5e 50%, #1a2a45 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
}
.skeleton-wrap .skeleton-chart {
  height: 320px;
  margin-top: 20px;
  border-radius: 12px;
  background: linear-gradient(90deg, #1a2a45 25%, #243a5e 50%, #1a2a45 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
}
@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.metric-card {
  display: flex;
  align-items: center;
  padding: 18px 20px;
  border-radius: 8px;
  color: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(64, 158, 255, 0.3);
  background: rgba(20, 45, 90, 0.6);
  backdrop-filter: blur(4px);
  transition: transform 0.3s;
}
.metric-card:hover { transform: translateY(-4px); }
.metric-icon {
  width: 56px; height: 56px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 28px; margin-right: 16px; flex-shrink: 0;
}
.metric-card-1 .metric-icon { background: rgba(0, 229, 255, 0.2); color: #00E5FF; }
.metric-card-2 .metric-icon { background: rgba(102, 187, 106, 0.2); color: #66BB6A; }
.metric-card-3 .metric-icon { background: rgba(255, 179, 0, 0.2); color: #FFB300; }
.metric-card-4 .metric-icon { background: rgba(245, 108, 108, 0.2); color: #F56C6C; }
.metric-card-1 { border-color: rgba(0, 229, 255, 0.4); }
.metric-card-2 { border-color: rgba(102, 187, 106, 0.4); }
.metric-card-3 { border-color: rgba(255, 179, 0, 0.4); }
.metric-card-4 { border-color: rgba(245, 108, 108, 0.4); }
.metric-info { flex: 1; }
.metric-label { font-size: 14px; color: #a9c0d8; margin-bottom: 6px; }
.metric-value {
  font-size: 26px; font-weight: bold;
  font-family: 'Consolas', 'Microsoft YaHei', monospace;
}
.metric-total { color: #889bb8; font-size: 14px; font-weight: 400; }
.pulse-red {
  animation: pulse-red 1.2s infinite alternate;
  color: #ff8a8a !important;
}
@keyframes pulse-red {
  from { text-shadow: 0 0 2px rgba(255, 120, 120, 0.8); }
  to   { text-shadow: 0 0 10px rgba(255, 90, 90, 1); }
}
.chart-wrapper {
  background: rgba(12, 30, 65, 0.6);
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 8px;
  padding: 16px 20px 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
  margin-bottom: 20px;
}
.chart-header {
  display: flex;
  align-items: baseline;
  margin-bottom: 8px;
}
.chart-title {
  font-size: 16px;
  font-weight: bold;
  color: #e6f0ff;
  margin-right: 12px;
}
.chart-sub {
  font-size: 12px;
  color: #7fb6e8;
}
.traffic-chart {
  width: 100%;
  height: 380px;
}
.anomaly-chart {
  width: 100%;
  height: 320px;
}
.table-wrapper {
  padding-bottom: 20px;
}
.table-wrapper ::v-deep .el-table {
  background: transparent;
  color: #e6f0ff;
}
.table-wrapper ::v-deep .el-table th,
.table-wrapper ::v-deep .el-table tr,
.table-wrapper ::v-deep .el-table td {
  background: transparent !important;
  border-color: rgba(64, 158, 255, 0.15) !important;
  color: #e6f0ff !important;
}
.table-wrapper ::v-deep .el-table::before,
.table-wrapper ::v-deep .el-table--border::after {
  background-color: rgba(64, 158, 255, 0.2) !important;
}
/* 离线设备整行置灰，弱化数据、突出离线标签 */
.table-wrapper ::v-deep .el-table .offline-row td {
  background-color: rgba(144, 147, 153, 0.12) !important;
  color: #909399 !important;
}
.table-wrapper ::v-deep .el-table .offline-row .cell {
  color: #909399 !important;
}
</style>
