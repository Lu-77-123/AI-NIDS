import { createRequest } from './request'

const request = createRequest()

/**
 * 获取流量总览（在线设备数、总入流量、总出流量、异常告警数等）
 * GET /api/traffic/overview
 */
export function getTrafficOverview() {
  return request.get('/api/traffic/overview')
}

/**
 * 获取最新一条流量数据（所有设备当前 inBps / outBps）
 * GET /api/traffic/latest
 */
export function getLatestTraffic() {
  return request.get('/api/traffic/latest')
}

/**
 * 根据设备 ID 获取该设备的历史流量数据
 * GET /api/traffic/history/{deviceId}
 * @param {string|number} deviceId 设备 ID
 */
export function getTrafficHistory(deviceId) {
  return request.get(`/api/traffic/history/${deviceId}`)
}

/**
 * 获取全网历史总流量（从系统启动至今）
 * GET /api/traffic/history-total
 */
export function getHistoryTotalTraffic() {
  return request.get('/api/traffic/history-total')
}
