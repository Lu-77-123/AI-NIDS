<template>
  <!-- 未登录：只渲染登录页（不显示侧边栏布局） -->
  <router-view v-if="!loggedIn" />

  <!-- 已登录：显示完整管理后台布局 -->
  <el-container v-else style="height: 100vh;">
    <!-- 左侧导航栏 -->
    <el-aside width="220px" style="background-color: #304156; display: flex; flex-direction: column;">
      <div style="color: white; font-size: 18px; text-align: center; padding: 20px; font-weight: bold;">
        SNMP 流量监控
      </div>
      <el-menu
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
        :default-active="activeMenu"
        :default-openeds="['g-monitor', 'g-security', 'g-system']"
        :collapse-transition="false"
        @select="handleMenuSelect"
        style="flex: 1; border-right: none;"
      >
        <!-- 分组一：态势感知 -->
        <el-submenu index="g-monitor">
          <template slot="title">
            <i class="el-icon-view"></i>
            <span slot="title">态势感知</span>
          </template>
          <el-menu-item index="dashboard">
            <i class="el-icon-monitor"></i>
            <span slot="title">实时监控大屏</span>
          </el-menu-item>
          <el-menu-item index="topology">
            <i class="el-icon-share"></i>
            <span slot="title">网络拓扑</span>
          </el-menu-item>
          <el-menu-item index="discovery">
            <i class="el-icon-search"></i>
            <span slot="title">网络发现</span>
          </el-menu-item>
        </el-submenu>

        <!-- 分组二：安全响应 -->
        <el-submenu index="g-security">
          <template slot="title">
            <i class="el-icon-bell"></i>
            <span slot="title">安全响应</span>
          </template>
          <el-menu-item index="alerts">
            <i class="el-icon-warning"></i>
            <span slot="title">入侵告警</span>
          </el-menu-item>
          <el-menu-item index="agent">
            <i class="el-icon-magic-stick"></i>
            <span slot="title">智能小七</span>
          </el-menu-item>
          <el-menu-item index="ai">
            <i class="el-icon-cpu"></i>
            <span slot="title">智能检测引擎</span>
          </el-menu-item>
        </el-submenu>

        <!-- 分组三：系统管理 -->
        <el-submenu index="g-system">
          <template slot="title">
            <i class="el-icon-setting"></i>
            <span slot="title">系统管理</span>
          </template>
          <el-menu-item index="device">
            <i class="el-icon-s-platform"></i>
            <span slot="title">设备管理</span>
          </el-menu-item>
          <el-menu-item index="user">
            <i class="el-icon-user"></i>
            <span slot="title">用户管理</span>
          </el-menu-item>
        </el-submenu>
      </el-menu>
      <!-- WebSocket 连接状态指示器 -->
      <div class="ws-status-bar">
        <span class="ws-dot" :class="wsConnected ? 'ws-online' : 'ws-offline'"></span>
        <span class="ws-text">{{ wsConnected ? '实时连接' : '断开重连中' }}</span>
      </div>
    </el-aside>

    <!-- 右侧主内容区 -->
    <el-container style="flex-direction: column;">
      <!-- 顶部栏：用户信息 + 退出登录 -->
      <header class="app-header">
        <span class="header-title">基于SNMP与Pcap4j的区域网络流量监控与恶意流量检测系统</span>
        <div class="header-user">
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <i class="el-icon-user-solid user-avatar"></i>
              <span class="user-name">{{ currentUser.realName || currentUser.username }}</span>
              <el-tag size="mini" :type="roleTag(currentUser.role)" effect="plain">{{ roleText(currentUser.role) }}</el-tag>
              <i class="el-icon-arrow-down"></i>
            </span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item disabled>
                <i class="el-icon-postcard"></i> {{ currentUser.username }}
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <i class="el-icon-switch-button"></i> 退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </header>

      <el-main style="background-color: #f7f8fa; padding: 0; overflow-y: auto;">
        <!-- 路由切换进度条 -->
        <div class="route-progress" v-if="routeLoading">
          <div class="route-progress-bar"></div>
        </div>
        <transition name="page-fade" mode="out-in">
          <router-view />
        </transition>
      </el-main>
    </el-container>
  </el-container>
</template>

<script>
import { getCurrentUser, isLoggedIn, logout } from './utils/auth'

export default {
  name: 'App',
  data() {
    return {
      wsConnected: false,
      wsTimer: null,
      currentUser: null,
      loggedIn: false,
      routeLoading: false
    };
  },
  computed: {
    activeMenu() {
      const path = this.$route.path;
      if (path.indexOf('/topology') === 0) return 'topology';
      if (path.indexOf('/discovery') === 0) return 'discovery';
      if (path.indexOf('/alerts') === 0) return 'alerts';
      if (path.indexOf('/agent') === 0) return 'agent';
      if (path.indexOf('/ai') === 0) return 'ai';
      if (path.indexOf('/device') === 0) return 'device';
      if (path.indexOf('/user') === 0) return 'user';
      return 'dashboard';
    }
  },
  watch: {
    // 路由变化时同步登录态 + 触发进度条
    '$route.path'(to, from) {
      this.syncAuth();
      if (to !== from) {
        this.routeLoading = true;
        this.$nextTick(() => {
          setTimeout(() => { this.routeLoading = false; }, 350);
        });
      }
    }
  },
  created() {
    this.syncAuth();
  },
  mounted() {
    this.checkWsStatus();
    this.wsTimer = setInterval(() => this.checkWsStatus(), 3000);
  },
  beforeDestroy() {
    if (this.wsTimer) clearInterval(this.wsTimer);
  },
  methods: {
    handleMenuSelect(index) {
      this.$router.push('/' + index);
    },
    checkWsStatus() {
      try {
        const inst = window.__smartMonitorWsSingleton__;
        this.wsConnected = inst ? inst.isConnected() : false;
      } catch (e) {
        this.wsConnected = false;
      }
    },
    roleTag(role) {
      const map = { admin: 'danger', operator: 'warning', viewer: 'info' };
      return map[role] || '';
    },
    roleText(role) {
      const map = { admin: '管理员', operator: '操作员', viewer: '只读用户' };
      return map[role] || role;
    },
    handleCommand(cmd) {
      if (cmd === 'logout') {
        this.$confirm('确认退出登录吗？', '退出登录', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        }).then(() => {
          logout();
          this.currentUser = null;
          this.loggedIn = false;
          this.$message.success('已退出登录');
          this.$router.push('/login');
        }).catch(() => {});
      }
    },
    /** 同步登录态：从 localStorage 读取并更新响应式变量 */
    syncAuth() {
      this.loggedIn = isLoggedIn();
      this.currentUser = this.loggedIn ? getCurrentUser() : null;
    }
  }
};
</script>

<style>
body { margin: 0; padding: 0; }

/* 顶部栏 */
.app-header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: relative;
  z-index: 10;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.header-user {
  display: flex;
  align-items: center;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 4px;
  transition: background 0.2s;
}
.user-info:hover {
  background: #f5f7fa;
}
.user-avatar {
  font-size: 18px;
  color: #409EFF;
}
.user-name {
  font-size: 14px;
  color: #303133;
}
.user-info .el-icon-arrow-down {
  font-size: 12px;
  color: #909399;
}

/* WebSocket 状态指示器 */
.ws-status-bar {
  padding: 10px 20px;
  display: flex;
  align-items: center;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.ws-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 8px;
  display: inline-block;
}
.ws-online {
  background: #67C23A;
  box-shadow: 0 0 6px rgba(103, 194, 58, 0.6);
  animation: ws-pulse 2s infinite;
}
.ws-offline {
  background: #F56C6C;
}
@keyframes ws-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
.ws-text {
  color: #bfcbd9;
  font-size: 12px;
}

/* 路由切换顶部进度条 */
.route-progress {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 3px;
  z-index: 3000;
  background: transparent;
  overflow: hidden;
}
.route-progress-bar {
  height: 100%;
  width: 40%;
  background: #3a7bd5;
  border-radius: 0 3px 3px 0;
  animation: route-progress-slide 0.35s ease-out forwards;
}
@keyframes route-progress-slide {
  from { width: 0; opacity: 0.8; }
  to { width: 100%; opacity: 0; }
}

/* 页面切换淡入 */
.page-fade-enter-active { transition: opacity 0.2s ease; }
.page-fade-leave-active { transition: opacity 0.15s ease; }
.page-fade-enter, .page-fade-leave-to { opacity: 0; }

/* 侧边栏分组菜单（深色主题适配） */
.el-menu--inline { background-color: #263445 !important; }
.el-submenu .el-menu-item {
  padding-left: 50px !important;
  min-width: auto;
}
.el-submenu .el-menu-item:hover { background-color: #1f2d3d !important; }
.el-submenu.is-active .el-submenu__title { color: #409EFF; }
.el-submenu__title i { color: inherit; }
</style>
