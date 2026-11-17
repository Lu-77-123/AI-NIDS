const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
  lintOnSave: false,
  // 生产包不再输出 .map（此前 ui-dist 里 11MB 的 sourcemap 纯属白送）
  productionSourceMap: false,
  configureWebpack: {
    performance: { hints: false }
  },
  devServer: {
    port: 8080,
    open: true
  }
})
