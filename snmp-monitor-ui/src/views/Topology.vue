<template>
  <div class="topology-container">
    <!-- 顶部工具栏 -->
    <div class="topo-toolbar">
      <span class="topo-title">网络拓扑图</span>
      <el-autocomplete
        class="node-search"
        v-model="searchKeyword"
        :fetch-suggestions="querySearch"
        placeholder="搜索设备名称 / IP"
        prefix-icon="el-icon-search"
        size="small"
        clearable
        value-key="label"
        @select="handleSearchSelect"
      ></el-autocomplete>
      <div class="topo-actions">
        <el-button-group>
          <el-button
            size="small"
            :type="layout === 'tree' ? 'primary' : ''"
            icon="el-icon-share"
            @click="toggleLayout('tree')"
          >树形布局</el-button>
          <el-button
            size="small"
            :type="layout === 'force' ? 'primary' : ''"
            icon="el-icon-connection"
            @click="toggleLayout('force')"
          >力导向布局</el-button>
        </el-button-group>
        <el-button
          size="small"
          icon="el-icon-refresh"
          @click="loadTopology"
          style="margin-left: 10px;"
        >刷新</el-button>
      </div>
    </div>

    <!-- 拓扑图主体 -->
    <div ref="topoChart" class="topo-chart"></div>

    <!-- 图例（点击可按层级 / 离线状态筛选） -->
    <div class="topo-legend">
      <span
        v-for="(c, idx) in categories"
        :key="c.name"
        :class="['legend-item', { off: hiddenCategories.indexOf(idx) !== -1 }]"
        @click="toggleCategory(idx)"
      >
        <i class="dot" :class="dotClass(idx)"></i>{{ c.name }}
        <em class="legend-count">{{ categoryCount(idx) }}</em>
      </span>
      <span class="legend-sep"></span>
      <span :class="['legend-item', { off: hideOffline }]" @click="toggleOffline">
        <i class="dot dot-offline"></i>离线
        <em class="legend-count">{{ offlineCount() }}</em>
      </span>
    </div>

    <!-- 设备详情抽屉 -->
    <el-drawer
      title="设备详情"
      :visible.sync="drawerVisible"
      direction="rtl"
      size="360px"
    >
      <div class="device-detail" v-if="selectedNode">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="设备名称">{{ selectedNode.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="设备ID">{{ selectedNode.id }}</el-descriptions-item>
          <el-descriptions-item label="设备类型">{{ selectedNode.deviceType || selectedNode.type || '-' }}</el-descriptions-item>
          <el-descriptions-item label="IP地址">{{ selectedNode.ip || '-' }}</el-descriptions-item>
          <el-descriptions-item label="所属层级">{{ categoryName(selectedNode.category) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="selectedNode.offline ? 'danger' : 'success'">
              {{ selectedNode.offline ? '离线' : '在线' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="入流量">{{ formatBps(selectedNode.inBps) }}</el-descriptions-item>
          <el-descriptions-item label="出流量">{{ formatBps(selectedNode.outBps) }}</el-descriptions-item>
          <el-descriptions-item label="异常评分">
            <span v-if="selectedNode.anomalyScore != null"
              :style="{ color: selectedNode.isAnomaly ? '#F56C6C' : '#67C23A', fontWeight: selectedNode.isAnomaly ? 'bold' : 'normal' }">
              {{ Number(selectedNode.anomalyScore).toFixed(4) }}
            </span>
            <span v-else style="color: #909399;">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="检测状态">
            <el-tag v-if="selectedNode.isAnomaly" size="small" type="danger">异常</el-tag>
            <el-tag v-else size="small" type="success">正常</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import echarts from '../utils/echarts';
import { getTopology } from '../api/topology';
import WebSocketClient from '../utils/websocket';

/** 解除 Vue 响应式（避免 echarts 内部访问 node.data.edge.__original 时报 undefined） */
function toPlain(obj) {
  try {
    if (obj == null) return obj;
    return JSON.parse(JSON.stringify(obj));
  } catch (e) {
    return obj;
  }
}

export default {
  name: 'Topology',
  data() {
    return {
      chart: null,
      wsClient: null,
      wsHandler: null,
      layout: 'force',
      loading: false,
      searchKeyword: '',
      rawNodes: [],
      rawLinks: [],
      // 离线集合（deviceId）
      offlineSet: new Set(),
      // 层级筛选：被隐藏的 category 下标（0 核心 / 1 汇聚 / 2 接入 / 3 终端）
      hiddenCategories: [],
      // 是否隐藏离线节点
      hideOffline: false,
      drawerVisible: false,
      selectedNode: null,
      categories: [
        { name: '核心层' },
        { name: '汇聚层' },
        { name: '接入层' },
        { name: '终端层' }
      ]
    };
  },
  mounted() {
    this.initChart();
    this.loadTopology();
    this.connectWebSocket();
    window.addEventListener('resize', this.handleResize);
  },
  beforeDestroy() {
    if (this._topoReloadTimer) { clearTimeout(this._topoReloadTimer); this._topoReloadTimer = null; }
    if (this.wsClient && this.wsHandler) {
      this.wsClient.offMessage(this.wsHandler);
    }
    this.wsHandler = null;
    this.wsClient = null;
    if (this.chart) {
      try { this.chart.dispose(); } catch (e) { /* ignore */ }
      this.chart = null;
    }
    window.removeEventListener('resize', this.handleResize);
  },
  methods: {
    initChart() {
      this.chart = echarts.init(this.$refs.topoChart);
      this.chart.on('click', (params) => {
        if (params.dataType === 'node') {
          this.selectedNode = params.data;
          this.drawerVisible = true;
        }
      });
    },
    loadTopology() {
      this.loading = true;
      getTopology()
        .then(res => {
          const data = res.data || {};
          this.rawNodes = Array.isArray(data.nodes) ? data.nodes : [];
          this.rawLinks = Array.isArray(data.links) ? data.links : [];
          // 只渲染能对上节点 id 的 links，避免 tree 布局 orphan edge 崩溃
          const idSet = new Set(this.rawNodes.map(n => String(n.id)));
          this.rawLinks = this.rawLinks.filter(l => idSet.has(String(l.source)) && idSet.has(String(l.target)));
          this.renderChart();
        })
        .catch(() => {
          this.$message.error('获取拓扑数据失败');
          this.rawNodes = [];
          this.rawLinks = [];
          this.renderChart();
        })
        .finally(() => {
          this.loading = false;
        });
    },
    buildRenderData() {
      // 深拷贝 → plain object，不传给 Vue 响应式封装
      const offlineSet = this.offlineSet;

      // ---- 分类筛选：按图层开关 + 离线开关过滤节点，连线仅保留两端均可见的 ----
      const visibleNodes = this.rawNodes.filter(n => {
        if (this.hiddenCategories.indexOf(this.resolveCategory(n)) !== -1) return false;
        if (this.hideOffline && this.isNodeOffline(n)) return false;
        return true;
      });
      const visibleIdSet = new Set(visibleNodes.map(n => String(n.id)));
      let visibleLinks = this.rawLinks.filter(l =>
        visibleIdSet.has(String(l.source)) && visibleIdSet.has(String(l.target))
      );

      // ---- tree 布局专用：构建合法树结构（不修改原始 links） ----
      let workingNodes = visibleNodes;
      let workingLinks = visibleLinks;

      if (this.layout === 'tree') {
        // 1. 每个节点只能有一个父节点（保留第一条入边）
        const seenTargets = new Set();
        workingLinks = visibleLinks.filter(l => {
          const k = String(l.target);
          if (seenTargets.has(k)) return false;
          seenTargets.add(k);
          return true;
        });

        // 2. 找出多根节点和孤立节点
        const hasParent = new Set(workingLinks.map(l => String(l.target)));
        const inLinks = new Set();
        workingLinks.forEach(l => {
          inLinks.add(String(l.source));
          inLinks.add(String(l.target));
        });
        const roots = visibleNodes.filter(n => !hasParent.has(String(n.id)));
        const disconnected = visibleNodes.filter(n => !inLinks.has(String(n.id)));

        // 3. 多根或孤立节点 → 创建虚拟根节点
        if (roots.length > 1 || disconnected.length > 0) {
          workingNodes = [...visibleNodes, {
            id: 'virtual-root',
            name: '网络拓扑',
            type: 'core',
            category: -1,
            ip: '',
            _virtual: true
          }];
          const vLinks = [];
          roots.forEach(r => vLinks.push({ source: 'virtual-root', target: String(r.id) }));
          disconnected.forEach(r => vLinks.push({ source: 'virtual-root', target: String(r.id) }));
          workingLinks = [...workingLinks, ...vLinks];
        }
      }

      const nodes = workingNodes.map(n => {
        const raw = Object.assign({}, n);
        const nodeType = String(raw.type || raw.deviceType || '').toLowerCase();
        // 优先用后端下发的标准图标键（router/switch/server/pc/ap），中文设备类型映射不到图标
        const symbolKey = raw.symbol || nodeType;
        const symbol = raw._virtual ? 'emptyCircle' : this.getSymbol(symbolKey);
        const symbolSize = raw._virtual ? 18 : this.getSymbolSize(symbolKey);
        const category = this.resolveCategory(raw);
        const offline = this.isNodeOffline(raw);
        const color = raw._virtual ? '#b3bcc9' : (offline ? '#b6bcc6' : this.getCategoryColor(category));
        return {
          id: String(raw.id),
          name: raw.name || raw.deviceName || String(raw.id),
          deviceType: raw.deviceType || raw.type || '',
          ip: raw.ip || '',
          category: category,
          inBps: raw.inBps,
          outBps: raw.outBps,
          offline: offline,
          symbol: symbol,
          symbolSize: symbolSize,
          itemStyle: {
            color: color,
            borderColor: offline ? '#C03639' : '#ffffff',
            borderWidth: 2
          },
          label: {
            show: true,
            position: 'bottom',
            fontSize: 11,
            color: offline ? '#909399' : '#303133'
          }
        };
      });
      const links = workingLinks.map(l => ({
        source: String(l.source),
        target: String(l.target),
        linkType: l.linkType,
        bandwidth: l.bandwidth,
        lineStyle: {
          color: '#b6bfcc',
          width: 1.5,
          curveness: 0.08,
          opacity: 0.7
        }
      }));
      return { nodes, links };
    },
    renderChart() {
      if (!this.chart) return;
      try {
        try { this.chart.dispose(); } catch (e) { /* ignore */ }
        this.chart = echarts.init(this.$refs.topoChart);
        this.chart.on('click', (params) => {
          if (params.dataType === 'node') {
            this.selectedNode = params.data;
            this.drawerVisible = true;
          }
        });
        const { nodes, links } = this.buildRenderData();
        const isTree = this.layout === 'tree';
        let option;
        if (isTree) {
          // tree 布局：将扁平 nodes/links 转为嵌套树结构，用 type:'tree' 渲染
          const treeData = this.buildTreeData(nodes, links);
          option = {
            backgroundColor: '#f7f8fa',
            tooltip: {
              formatter: (params) => {
                const d = params.data || {};
                const status = d.offline ? '离线' : '在线';
                const statusColor = d.offline ? '#F56C6C' : '#67C23A';
                let html = `<div style="font-size:14px;font-weight:bold;margin-bottom:6px;">${d.name || '-'}</div>`;
                html += `<div style="margin-bottom:2px;">设备类型：${d.deviceType || '-'}</div>`;
                html += `<div style="margin-bottom:2px;">IP 地址：${d.ip || '-'}</div>`;
                html += `<div>状态：<span style="color:${statusColor};font-weight:bold;">● ${status}</span></div>`;
                if (d.inBps != null) {
                  const inMbps = (d.inBps / 1000000).toFixed(2);
                  html += `<div style="margin-top:2px;">入站速率：${inMbps} Mbps</div>`;
                }
                if (d.outBps != null) {
                  const outMbps = (d.outBps / 1000000).toFixed(2);
                  html += `<div>出站速率：${outMbps} Mbps</div>`;
                }
                return html;
              }
            },
            series: [{
              type: 'tree',
              data: treeData,
              top: '5%', left: '15%', bottom: '5%', right: '20%',
              layout: 'orthogonal',
              orient: 'LR',
              symbolSize: 35,
              initialTreeDepth: -1,
              label: {
                position: 'left',
                verticalAlign: 'middle',
                align: 'right',
                fontSize: 11,
                color: '#303133'
              },
              leaves: {
                label: {
                  position: 'right',
                  verticalAlign: 'middle',
                  align: 'left'
                }
              },
              emphasis: { focus: 'descendant' },
              expandAndCollapse: true,
              animationDuration: 550,
              animationDurationUpdate: 750,
              lineStyle: { color: '#b6bfcc', width: 1.5, curveness: 0.5 },
              itemStyle: {
                borderColor: '#ffffff',
                borderWidth: 2
              }
            }]
          };
        } else {
          // force 布局：用 type:'graph'
          option = {
            backgroundColor: '#f7f8fa',
            tooltip: {
              formatter: (params) => {
                if (params.dataType === 'node') {
                  const d = params.data || {};
                  const status = d.offline ? '离线' : '在线';
                  const statusColor = d.offline ? '#F56C6C' : '#67C23A';
                  let html = `<div style="font-size:14px;font-weight:bold;margin-bottom:6px;">${d.name || '-'}</div>`;
                  html += `<div style="margin-bottom:2px;">设备类型：${d.deviceType || '-'}</div>`;
                  html += `<div style="margin-bottom:2px;">IP 地址：${d.ip || '-'}</div>`;
                  html += `<div>状态：<span style="color:${statusColor};font-weight:bold;">● ${status}</span></div>`;
                  if (d.inBps != null) {
                    const inMbps = (d.inBps / 1000000).toFixed(2);
                    html += `<div style="margin-top:2px;">入站速率：${inMbps} Mbps</div>`;
                  }
                  if (d.outBps != null) {
                    const outMbps = (d.outBps / 1000000).toFixed(2);
                    html += `<div>出站速率：${outMbps} Mbps</div>`;
                  }
                  return html;
                } else if (params.dataType === 'edge') {
                  const d = params.data || {};
                  const mb = d.bandwidth ? ` (${d.bandwidth >= 1000 ? (d.bandwidth / 1000) + ' Gbps' : d.bandwidth + ' Mbps'})` : '';
                  return `${d.source} → ${d.target}${mb}<br/>${d.linkType || ''}`;
                }
                return '';
              }
            },
            animationDurationUpdate: 500,
            animationEasingUpdate: 'cubicInOut',
            series: [{
              type: 'graph',
              roam: true,
              draggable: true,
              label: { show: true, position: 'bottom', fontSize: 11 },
              edgeSymbol: ['none', 'arrow'],
              edgeSymbolSize: [0, 8],
              data: nodes,
              links: links,
              categories: toPlain(this.categories),
              lineStyle: { color: '#b6bfcc', width: 1.5, curveness: 0.08 },
              layout: 'force',
              force: { repulsion: 260, edgeLength: 110, gravity: 0.08 },
              emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }
            }]
          };
        }
        const plainOption = toPlain(option);
        this.chart.setOption(plainOption, true);
      } catch (e) {
        console.error('[Topology] render 失败:', e);
        if (!this._renderErrorShown) {
          this._renderErrorShown = true;
          this.$message.error('拓扑渲染异常，已尝试降级为力导向布局');
          setTimeout(() => { this._renderErrorShown = false; }, 5000);
        }
        try {
          if (this.layout !== 'force') {
            this.layout = 'force';
            setTimeout(() => this.renderChart(), 30);
          }
        } catch (_) { /* ignore */ }
      }
    },
    buildTreeData(nodes, links) {
      // 构建父子映射
      const nodeMap = {};
      nodes.forEach(n => { nodeMap[n.id] = Object.assign({}, n, { children: [] }); });
      const childSet = new Set();
      links.forEach(l => {
        const src = String(l.source);
        const tgt = String(l.target);
        if (nodeMap[src] && nodeMap[tgt]) {
          // 每个节点只保留第一个父节点（避免环）
          if (!childSet.has(tgt)) {
            nodeMap[src].children.push(nodeMap[tgt]);
            childSet.add(tgt);
          }
        }
      });
      // 找根节点（没有父节点的）
      const roots = nodes.filter(n => !childSet.has(n.id)).map(n => nodeMap[n.id]);
      // 清理空 children 数组（叶子节点不需要 children）
      const cleanChildren = (node) => {
        if (node.children && node.children.length === 0) {
          delete node.children;
        } else if (node.children) {
          node.children.forEach(c => cleanChildren(c));
        }
      };
      roots.forEach(r => cleanChildren(r));
      // 如果有多个根，创建虚拟根
      if (roots.length > 1) {
        return [{
          name: '网络拓扑',
          deviceType: '',
          ip: '',
          itemStyle: { color: '#9aa5b4' },
          children: roots
        }];
      }
      return roots;
    },
    toggleLayout(type) {
      if (this.layout === type) return;
      this.layout = type;
      this.renderChart();
    },
    /** 设备搜索建议（按名称或 IP 模糊匹配） */
    querySearch(queryString, cb) {
      const kw = (queryString || '').trim().toLowerCase();
      if (!kw) {
        cb([]);
        return;
      }
      const results = this.rawNodes
        .filter(n => {
          const name = String(n.name || n.deviceName || '').toLowerCase();
          const ip = String(n.ip || '').toLowerCase();
          return name.includes(kw) || ip.includes(kw);
        })
        .slice(0, 8)
        .map(n => ({
          label: `${n.name || n.deviceName || n.id}${n.ip ? '  (' + n.ip + ')' : ''}`,
          value: n
        }));
      cb(results);
    },
    /** 选中搜索结果：打开详情抽屉并在图上高亮定位 */
    handleSearchSelect(item) {
      if (!item || !item.value) return;
      const rawNode = item.value;
      // 从渲染数据中取到带完整样式的节点
      let target = null;
      try {
        const { nodes } = this.buildRenderData();
        target = nodes.find(n => n.id === String(rawNode.id)) || rawNode;
      } catch (e) {
        target = rawNode;
      }
      this.selectedNode = target;
      this.drawerVisible = true;
      // 力导向图上高亮并聚焦该节点
      if (this.chart && this.layout === 'force') {
        try {
          this.chart.dispatchAction({
            type: 'focus',
            seriesIndex: 0,
            dataId: String(rawNode.id)
          });
          this.chart.dispatchAction({
            type: 'highlight',
            seriesIndex: 0,
            dataId: String(rawNode.id)
          });
          // 2 秒后取消高亮，恢复全图
          setTimeout(() => {
            if (this.chart) {
              this.chart.dispatchAction({ type: 'downplay', seriesIndex: 0 });
            }
          }, 2000);
        } catch (e) {
          console.warn('[Topology] 节点高亮失败:', e);
        }
      }
    },
    connectWebSocket() {
      this.wsClient = new WebSocketClient();
      this.wsHandler = (data) => this.handleWsMessage(data);
      this.wsClient.onMessage(this.wsHandler);
      this.wsClient.connect();
    },
    handleWsMessage(data) {
      if (!data || !Array.isArray(data.devices)) return;
      let changed = false;
      let unknownDevice = false;
      const ids = new Set(this.rawNodes.map(n => String(n.id)));
      data.devices.forEach(d => {
        const did = d.deviceId;
        const key = String(did);
        if (!ids.has(key)) { unknownDevice = true; return; }
        const isOffline = (d.online === false);
        const wasOffline = this.offlineSet.has(did) || this.offlineSet.has(key);
        if (isOffline !== wasOffline) {
          if (isOffline) this.offlineSet.add(did); else this.offlineSet.delete(did);
          changed = true;
        }
        const node = this.rawNodes.find(n => String(n.id) === key);
        if (node) {
          node.inBps = d.inBps;
          node.outBps = d.outBps;
          node.offline = isOffline;
          node.anomalyScore = d.anomalyScore;
          node.isAnomaly = d.isAnomaly;
          changed = true;
        }
      });
      // 出现拓扑里没有的新设备（网络发现新纳管）→ 防抖重载拓扑结构；
      // 记录已触发过的 id，避免后端拓扑接口未收录该设备时反复重载
      if (unknownDevice) {
        if (!this._unknownReloaded) this._unknownReloaded = new Set();
        const pending = data.devices.some(d => {
          const key = String(d.deviceId);
          return !ids.has(key) && !this._unknownReloaded.has(key);
        });
        if (pending) {
          data.devices.forEach(d => {
            const key = String(d.deviceId);
            if (!ids.has(key)) this._unknownReloaded.add(key);
          });
          if (!this._topoReloadTimer) {
            this._topoReloadTimer = setTimeout(() => {
              this._topoReloadTimer = null;
              this.loadTopology();
            }, 10000);
          }
        }
      }
      if (!changed) return;
      try {
        const { nodes, links } = this.buildRenderData();
        let patch;
        if (this.layout === 'tree') {
          // 树形布局：WS 推送后重建嵌套树数据再更新，否则树视图不会实时变化
          const treeData = this.buildTreeData(nodes, links);
          patch = toPlain({ series: [{ data: treeData }] });
        } else {
          patch = toPlain({ series: [{ data: nodes }] });
        }
        if (this.chart) this.chart.setOption(patch, false);
      } catch (e) {
        console.warn('[Topology] WS 更新失败，走全量重绘:', e);
        this.renderChart();
      }
    },
    getSymbol(type) {
      switch (type) {
        case 'core': return 'circle';
        case 'router': return 'rect';
        case 'switch': return 'roundRect';
        case 'pc': return 'circle';
        case 'server': return 'rect';
        case 'ap': return 'circle';
        case 'printer': return 'triangle';
        default: return 'circle';
      }
    },
    getSymbolSize(type) {
      switch (type) {
        case 'core': return 55;
        case 'router': return 42;
        case 'switch': return 36;
        case 'server': return 36;
        default: return 30;
      }
    },
    getCategoryByType(type) {
      switch (type) {
        case 'core': return 0;
        case 'router': return 1;
        case 'switch': return 2;
        case 'ap': return 2;
        case 'pc':
        case 'server':
        case 'printer': return 3;
        default: return 3;
      }
    },
    getCategoryColor(category) {
      const colors = ['#3a7bd5', '#52a55c', '#e3a21f', '#94a0b4'];
      return colors[category] || '#3a7bd5';
    },
    /** 解析节点所属层级（后端 category 优先，否则按设备类型推断；虚拟根为 -1） */
    resolveCategory(raw) {
      if (raw._virtual) return -1;
      if (raw.category != null && raw.category !== '') return Number(raw.category);
      return this.getCategoryByType(String(raw.type || raw.deviceType || '').toLowerCase());
    },
    /** 节点是否离线（兼容 id 多种类型，与 WS 离线集合对齐） */
    isNodeOffline(raw) {
      return !!raw.offline
        || this.offlineSet.has(raw.id)
        || this.offlineSet.has(String(raw.id))
        || this.offlineSet.has(Number(raw.id));
    },
    /** 点击图例：切换某一层级显隐并重绘 */
    toggleCategory(idx) {
      const i = this.hiddenCategories.indexOf(idx);
      if (i === -1) this.hiddenCategories.push(idx);
      else this.hiddenCategories.splice(i, 1);
      this.renderChart();
    },
    /** 切换离线节点显隐并重绘 */
    toggleOffline() {
      this.hideOffline = !this.hideOffline;
      this.renderChart();
    },
    dotClass(idx) {
      return ['dot-core', 'dot-agg', 'dot-access', 'dot-terminal'][idx] || '';
    },
    /** 各层级节点数（图例统计） */
    categoryCount(idx) {
      return this.rawNodes.filter(n => this.resolveCategory(n) === idx).length;
    },
    /** 离线节点数 */
    offlineCount() {
      return this.rawNodes.filter(n => this.isNodeOffline(n)).length;
    },
    categoryName(category) {
      const names = ['核心层', '汇聚层', '接入层', '终端层'];
      return names[category] || '-';
    },
    formatBps(value) {
      const v = Number(value) || 0;
      if (v < 1000) return v + ' bps';
      if (v < 1000000) return (v / 1000).toFixed(1) + ' Kbps';
      if (v < 1000000000) return (v / 1000000).toFixed(1) + ' Mbps';
      return (v / 1000000000).toFixed(2) + ' Gbps';
    },
    handleResize() {
      if (this.chart) this.chart.resize();
    }
  }
};
</script>

<style scoped>
.topology-container {
  position: relative;
  width: 100%;
  height: 100%;
  background-color: #f7f8fa;
  display: flex;
  flex-direction: column;
}
.topo-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  z-index: 2;
}
.topo-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.node-search {
  width: 240px;
}
.topo-actions {
  display: flex;
  align-items: center;
}
.topo-chart {
  flex: 1;
  width: 100%;
  min-height: 560px;
}
.topo-legend {
  position: absolute;
  bottom: 16px;
  left: 20px;
  display: flex;
  align-items: center;
  background: #fff;
  padding: 4px 8px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  z-index: 2;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  font-size: 12px;
  color: #606266;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s, opacity 0.15s;
}
.legend-item:hover {
  background: #f5f7fa;
}
.legend-item.off {
  opacity: 0.38;
}
.legend-item .dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 5px;
}
.legend-count {
  font-style: normal;
  font-size: 11px;
  color: #909399;
  margin-left: 4px;
}
.legend-sep {
  width: 1px;
  height: 14px;
  background: #ebeef5;
  margin: 0 4px;
}
.dot-core { background: #3a7bd5; }
.dot-agg { background: #52a55c; }
.dot-access { background: #e3a21f; }
.dot-terminal { background: #94a0b4; }
.dot-offline { background: #c6ccd6; border: 2px solid #C03639; box-sizing: border-box; }
.device-detail {
  padding: 0 20px 20px;
}
</style>
