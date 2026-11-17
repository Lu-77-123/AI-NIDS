import { createRequest } from './request'

const request = createRequest()

/**
 * 检测引擎状态与参数
 * GET /api/ai/engine
 */
export function getEngineStatus() {
  return request.get('/api/ai/engine')
}

/**
 * 告警统计（今日告警/未处理/威胁等级分布）
 * GET /api/alerts/stats
 */
export function getAlertStats() {
  return request.get('/api/alerts/stats')
}

/**
 * 旁路抓包状态（Pcap4j 包级检测）
 * GET /api/capture/stats
 */
export function getCaptureStats() {
  return request.get('/api/capture/stats')
}

/**
 * 修改 AI 引擎运行期配置（仅管理员）
 * PUT /api/ai/config
 */
export function updateAiConfig(data) {
  return request.put('/api/ai/config', data)
}

/**
 * 恢复 AI 引擎出厂默认配置（仅管理员）
 * POST /api/ai/config/reset
 */
export function resetAiConfig() {
  return request.post('/api/ai/config/reset')
}
