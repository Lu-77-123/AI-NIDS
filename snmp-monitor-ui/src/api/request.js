/**
 * 统一 axios 实例工厂
 *  - 请求自动携带 Authorization: Bearer <token>
 *  - 响应 401 时清除登录态并跳转登录页（令牌过期/伪造/缺失）
 * 所有 src/api/*.js 及组件内的私有 axios 实例都应使用本工厂创建
 */
import axios from 'axios'
import { Message } from 'element-ui'
import { getToken, logout } from '../utils/auth'

/**
 * 网络层错误统一提示（后端不可达/超时/断网）。
 * 带响应状态码的错误（4xx/5xx）交给各页面自行处理，避免双重弹窗。
 * 5 秒冷却，防止后端宕机时轮询页面刷屏。
 */
let lastNetErrorAt = 0
function notifyNetworkError(err) {
  if (err.response) return
  const now = Date.now()
  if (now - lastNetErrorAt < 5000) return
  lastNetErrorAt = now
  Message.error(err.code === 'ECONNABORTED' ? '请求超时，请检查后端服务' : '无法连接后端服务，请确认服务已启动')
}

/** 统一的响应错误拦截逻辑 */
function handleResponseError(err) {
  if (err.response && err.response.status === 401) {
    logout()
    // hash 路由模式下直接改 hash，避免与 router 实例产生循环依赖
    if (!window.location.hash.startsWith('#/login')) {
      window.location.hash = '#/login'
    }
  }
  notifyNetworkError(err)
  return Promise.reject(err)
}

export function createRequest(timeout = 10000) {
  const instance = axios.create({
    baseURL: process.env.VUE_APP_API_BASE || '',
    timeout
  })

  // 请求拦截：注入 JWT
  instance.interceptors.request.use(config => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  }, err => Promise.reject(err))

  // 响应拦截：401 统一收口 + 网络错误提示
  instance.interceptors.response.use(res => res, handleResponseError)

  return instance
}

/**
 * 给任意 axios 实例（含全局 axios）安装同一套 JWT 拦截器。
 * 供直接 `import axios from 'axios'` 的历史页面使用。
 */
export function attachAuthInterceptors(target) {
  target.interceptors.request.use(config => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  }, err => Promise.reject(err))

  target.interceptors.response.use(res => res, handleResponseError)
}
