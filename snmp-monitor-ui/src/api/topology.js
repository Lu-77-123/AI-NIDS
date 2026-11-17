import { createRequest } from './request'

const request = createRequest()

/**
 * 获取网络拓扑数据（节点 + 链路）
 * GET /api/topology
 */
export function getTopology() {
  return request.get('/api/topology')
}
