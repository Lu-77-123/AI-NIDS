package com.smartmonitor.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket 处理器，负责管理前端连接并广播实时遥测数据。
 * 后端定时任务每次采集完流量后调用 broadcast() 推送给所有连接的前端。
 */
@Component
public class MetricsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(MetricsWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        logger.info("WebSocket 连接建立: {} (当前连接数: {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        logger.info("WebSocket 连接关闭: {} (当前连接数: {})", session.getId(), sessions.size());
    }

    /**
     * 向所有连接的前端广播消息（JSON 字符串）。
     * 
     * 注意：Spring WebSocket 的 session.sendMessage() 不是线程安全的，
     * 多线程并发推送（比如发现扫描的 8 线程池）时会抛出：
     *   "IllegalStateException: The remote endpoint was in state [TEXT_FULL_WRITING]"
     * 这里用 synchronized (session) 对单个 session 加细粒度锁，避免并发写冲突。
     */
    public void broadcast(Object data) {
        if (sessions.isEmpty()) {
            return;
        }
        final String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.error("WebSocket 序列化消息失败", e);
            return;
        }
        final TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            // 对每个 session 独立加锁，避免多线程并发写入同一个远程端点
            synchronized (session) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                } catch (IllegalStateException e) {
                    // 典型：TEXT_FULL_WRITING / 远程端点忙
                    logger.debug("WebSocket session {} 忙，跳过本次推送: {}", session.getId(), e.getMessage());
                } catch (IOException e) {
                    logger.warn("WebSocket 发送失败 (session={}): {}", session.getId(), e.getMessage());
                } catch (Exception e) {
                    logger.warn("WebSocket 推送异常 (session={}): {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    public int getConnectionCount() {
        return sessions.size();
    }
}
