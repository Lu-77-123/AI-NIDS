<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <i class="el-icon-monitor login-icon"></i>
        <h1 class="login-title">网络智能监控系统</h1>
        <p class="login-subtitle">基于SNMP与Pcap4j的区域网络流量监控与恶意流量检测系统</p>
      </div>

      <el-form ref="loginForm" :model="loginForm" :rules="rules" label-width="0" @submit.native.prevent="handleLogin">
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            prefix-icon="el-icon-user"
            placeholder="用户名"
            size="medium"
            clearable
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            prefix-icon="el-icon-lock"
            type="password"
            placeholder="密码"
            size="medium"
            show-password
            @keyup.enter.native="handleLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="medium"
          style="width: 100%;"
          :loading="loading"
          @click="handleLogin"
        >登 录</el-button>
      </el-form>
    </div>
  </div>
</template>

<script>
import { userLogin } from '../api/user'
import { setCurrentUser, setToken } from '../utils/auth'

export default {
  name: 'Login',
  data() {
    return {
      loading: false,
      loginForm: {
        username: '',
        password: ''
      },
      rules: {
        username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      }
    }
  },
  methods: {
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (!valid) return
        this.loading = true
        userLogin(this.loginForm.username, this.loginForm.password)
          .then(res => {
            const data = res.data || {}
            if (data.success && data.user) {
              setCurrentUser(data.user)
              setToken(data.token || '')
              this.$message.success('登录成功，欢迎 ' + (data.user.realName || data.user.username))
              this.$router.push('/dashboard')
            } else {
              this.$message.error(data.message || '用户名或密码错误')
            }
          })
          .catch(() => {
            this.$message.error('登录失败，请检查后端服务是否启动')
          })
          .finally(() => {
            this.loading = false
          })
      })
    }
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  width: 100%;
  background: #304156;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-box {
  width: 380px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.18);
  padding: 40px 36px;
}
.login-header {
  text-align: center;
  margin-bottom: 30px;
}
.login-icon {
  font-size: 44px;
  color: #3a7bd5;
}
.login-title {
  margin: 10px 0 4px;
  font-size: 21px;
  color: #303133;
}
.login-subtitle {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
