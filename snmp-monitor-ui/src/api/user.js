import { createRequest } from './request'

// 后端 API：Docker 下由 nginx 反向代理，dev 下用 vue.config.js proxy
const request = createRequest()

// 查询全部用户
export function getUserList() {
  return request.get('/api/user')
}

// 新增用户
export function addUser(data) {
  return request.post('/api/user', data)
}

// 修改用户
export function updateUser(data) {
  return request.put('/api/user', data)
}

// 删除用户
export function deleteUser(id) {
  return request.delete('/api/user/' + id)
}

// 重置密码为 123456
export function resetUserPassword(id) {
  return request.post('/api/user/' + id + '/reset-password')
}

// 登录
export function userLogin(username, password) {
  return request.post('/api/user/login', { username, password })
}
