import { createRequest } from './request'

const request = createRequest(15000)

/** 自动检测网段 */
export function detectSegment() {
  return request.get('/api/discovery/segment')
}

/** 启动网络扫描 */
export function startScan(segment, community) {
  return request.post('/api/discovery/scan', { segment, community })
}

/** 查询扫描状态 */
export function getScanStatus() {
  return request.get('/api/discovery/status')
}

/** 获取最近一次扫描结果（进度 + 设备列表 + 状态）*/
export function getScanResult() {
  return request.get('/api/discovery/result')
}

/** 将发现的设备批量加入管理 */
export function addDiscoveredDevices(ips, community) {
  return request.post('/api/discovery/add', { ips, community })
}
