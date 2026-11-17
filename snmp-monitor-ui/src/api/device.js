import { createRequest } from './request'

// 后端 API：Docker 下由 nginx 反向代理，dev 下用 vue.config.js proxy
const request = createRequest()

// 查询全部设备（含 SNMP 实时采集）
export function getDeviceList() {
  return request.get('/api/device')
}

// 新增设备
export function addDevice(data) {
  return request.post('/api/device', data)
}

// 修改设备
export function updateDevice(data) {
  return request.put('/api/device', data)
}

// 删除设备
export function deleteDevice(id) {
  return request.delete('/api/device/' + id)
}
