/**
 * 登录态管理工具
 * 统一登录态来源：localStorage 中的 smart_monitor_user（用户信息）与 smart_monitor_token（JWT）
 * 用户信息存在即视为已登录（路由守卫用）；JWT 随每个 API 请求自动携带
 */

const STORAGE_KEY = 'smart_monitor_user'
const TOKEN_KEY = 'smart_monitor_token'

/** 获取当前登录用户 */
export function getCurrentUser() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

/** 是否已登录 */
export function isLoggedIn() {
  return !!getCurrentUser()
}

/** 获取 JWT */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

/** 保存 JWT */
export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 登录成功后保存用户信息 */
export function setCurrentUser(user) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
}

/** 退出登录：清除登录态与令牌（单一来源，避免残留 flag） */
export function logout() {
  localStorage.removeItem(STORAGE_KEY)
  localStorage.removeItem(TOKEN_KEY)
}
