import Vue from 'vue';
import VueRouter from 'vue-router';
import { isLoggedIn } from '../utils/auth';

// 路由级懒加载：每个页面独立异步 chunk，首屏只加载登录页所需代码
const Dashboard = () => import(/* webpackChunkName: "dashboard" */ '../views/Dashboard.vue');
const Topology = () => import(/* webpackChunkName: "topology" */ '../views/Topology.vue');
const DeviceManage = () => import(/* webpackChunkName: "device" */ '../views/DeviceManage.vue');
const Discovery = () => import(/* webpackChunkName: "discovery" */ '../views/Discovery.vue');
const Alert = () => import(/* webpackChunkName: "alert" */ '../views/Alert.vue');
const Agent = () => import(/* webpackChunkName: "agent" */ '../views/Agent.vue');
const AiMonitor = () => import(/* webpackChunkName: "ai" */ '../views/AiMonitor.vue');
const UserManage = () => import(/* webpackChunkName: "user" */ '../views/UserManage.vue');
const Login = () => import(/* webpackChunkName: "login" */ '../views/Login.vue');

Vue.use(VueRouter);

const routes = [
  {
    path: '/login',
    name: 'login',
    component: Login,
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: Dashboard,
    meta: { title: '监控大屏', requiresAuth: true }
  },
  {
    path: '/topology',
    name: 'topology',
    component: Topology,
    meta: { title: '网络拓扑', requiresAuth: true }
  },
  {
    path: '/discovery',
    name: 'discovery',
    component: Discovery,
    meta: { title: '网络发现', requiresAuth: true }
  },
  {
    path: '/alerts',
    name: 'alerts',
    component: Alert,
    meta: { title: '入侵告警', requiresAuth: true }
  },
  {
    path: '/agent',
    name: 'agent',
    component: Agent,
    meta: { title: '智能小七', requiresAuth: true }
  },
  {
    path: '/ai',
    name: 'ai',
    component: AiMonitor,
    meta: { title: '智能检测引擎', requiresAuth: true }
  },
  {
    path: '/device',
    name: 'device',
    component: DeviceManage,
    meta: { title: '设备管理', requiresAuth: true }
  },
  {
    path: '/user',
    name: 'user',
    component: UserManage,
    meta: { title: '用户管理', requiresAuth: true }
  },
  {
    path: '/',
    redirect: '/dashboard'
  }
];

const router = new VueRouter({
  mode: 'hash',
  routes
});

// 全局路由守卫：未登录访问需要鉴权的页面，统一跳转到 /login
router.beforeEach((to, from, next) => {
  const loggedIn = isLoggedIn();
  // 已登录用户访问登录页：跳转到首页
  if (to.path === '/login' && loggedIn) {
    next('/dashboard');
    return;
  }
  // 未登录用户访问非登录页：跳转到登录页
  if (to.path !== '/login' && !loggedIn) {
    next('/login');
    return;
  }
  next();
});

export default router;
