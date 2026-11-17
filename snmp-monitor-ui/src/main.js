import Vue from 'vue';
import VueRouter from 'vue-router';
import axios from 'axios';
import App from './App.vue';
import ElementUI from 'element-ui';
import 'element-ui/lib/theme-chalk/index.css';
import router from './router';
import { attachAuthInterceptors } from './api/request';

Vue.use(ElementUI); // 启用 Element UI
Vue.use(VueRouter); // 启用 Vue Router
Vue.config.productionTip = false;

// 全局 axios（Alert.vue 等直接 import axios 的页面）统一注入 JWT、401 收口
attachAuthInterceptors(axios);

new Vue({
  router,
  render: h => h(App),
}).$mount('#app');
