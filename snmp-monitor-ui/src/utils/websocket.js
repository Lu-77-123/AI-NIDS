/**
 * WebSocket 客户端工具（全局单例）
 *   - URL: ws://{location.host}/ws/metrics
 *   - 自动重连（断开 5 秒后重连）
 *   - 三个页面(Dashboard / Topology / Device)共享同一个连接
 *     避免每个页面 mount 时重复创建 WebSocket，引发回调 3 倍执行 & 渲染死循环
 */
const INSTANCE_KEY = '__smartMonitorWsSingleton__';
if (typeof window !== 'undefined' && !window[INSTANCE_KEY]) {
  window[INSTANCE_KEY] = createInstance();
}

function createInstance() {
  const state = {
    ws: null,
    callbacks: [],
    reconnectTimer: null,
    isManualClose: false,
    reconnectInterval: 5000,
    url: (() => {
      if (typeof window === 'undefined') return '';
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      return `${proto}//${window.location.host}/ws/metrics`;
    })()
  };

  function scheduleReconnect() {
    if (state.reconnectTimer) return;
    console.log(`[WebSocket] ${state.reconnectInterval / 1000}s 后重连...`);
    state.reconnectTimer = setTimeout(() => {
      state.reconnectTimer = null;
      connect();
    }, state.reconnectInterval);
  }

  function connect() {
    if (state.ws) {
      try { state.ws.close(); } catch (e) { /* ignore */ }
      state.ws = null;
    }
    state.isManualClose = false;
    try {
      state.ws = new WebSocket(state.url);
    } catch (e) {
      console.error('[WebSocket] 创建失败:', e);
      scheduleReconnect();
      return;
    }

    state.ws.onopen = () => {
      console.log('[WebSocket] 连接建立:', state.url);
    };

    state.ws.onmessage = (event) => {
      let data = event.data;
      try {
        data = JSON.parse(event.data);
      } catch (e) { /* 非 JSON, 保留原内容 */ }
      // 拷贝 callbacks 数组，避免回调里注销导致的遍历异常
      const snap = state.callbacks.slice();
      for (const cb of snap) {
        try { cb(data); }
        catch (err) { console.error('[WebSocket] 回调异常:', err); }
      }
    };

    state.ws.onerror = (err) => {
      console.error('[WebSocket] 错误:', err);
    };

    state.ws.onclose = () => {
      console.warn('[WebSocket] 关闭');
      if (!state.isManualClose) scheduleReconnect();
    };
  }

  function disconnect() {
    state.isManualClose = true;
    if (state.reconnectTimer) {
      clearTimeout(state.reconnectTimer);
      state.reconnectTimer = null;
    }
    if (state.ws) {
      try { state.ws.close(); } catch (e) { /* ignore */ }
      state.ws = null;
    }
  }

  function onMessage(cb) {
    if (typeof cb === 'function' && state.callbacks.indexOf(cb) === -1) {
      state.callbacks.push(cb);
    }
  }

  function offMessage(cb) {
    state.callbacks = state.callbacks.filter(x => x !== cb);
  }

  function isConnected() {
    return !!(state.ws && state.ws.readyState === 1);
  }

  return { connect, disconnect, onMessage, offMessage, isConnected };
}

export default class WebSocketClient {
  /** 对外暴露的轻量门面，语义与原来一致，实际复用全局单例 */
  constructor() { this._s = (typeof window !== 'undefined' && window[INSTANCE_KEY]) || createInstance(); }
  connect() { if (!this._s.isConnected()) this._s.connect(); }
  disconnect() { /* 全局单例不清连接；只 offMessage */ }
  onMessage(cb) { this._s.onMessage(cb); this.connect(); }
  offMessage(cb) { this._s.offMessage(cb); }
}
