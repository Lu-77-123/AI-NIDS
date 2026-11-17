# 基于 SNMP 与 Pcap4j 的区域网络流量监控与恶意流量检测系统 - 项目文档

## 〇、项目基本信息

| 项 | 内容 |
| :--- | :--- |
| 题目 | 基于 SNMP 与 Pcap4j 的区域网络流量监控与恶意流量检测系统 |
| 学生 | 卢梁敏 |
| 学号 | 202309504234 |
| 学院 | 电子信息与人工智能学院 |
| 专业 | 计算机科学与技术（23 级 2 班） |
| 指导教师 | 姚旭艺 |
| 学校 | 梧州学院 |

---

## 一、项目概述

本项目是一个基于 Spring Boot、SNMP4J、Spring Data JPA 与 WebSocket 的区域网络流量监控与恶意流量检测系统，用于对交换机、路由器等网络设备进行集中式流量监控与状态采集，并通过 WebSocket 实时推送至前端可视化界面。系统融合 SNMP 宏观统计与 Pcap4j 微观抓包的协同检测方案，在保持低开销状态巡检的同时，实现数据包级的深度分析，弥补单一监控手段的不足。

### 1.1 技术栈

| 分类 | 技术 | 版本 |
| :--- | :--- | :--- |
| 语言 | Java | 11 |
| 后端框架 | Spring Boot | 2.7.18 |
| SNMP 库 | SNMP4J | 3.7.7 |
| 持久层 | Spring Data JPA + Hibernate | - |
| 数据库 | MySQL | 8.0 |
| 实时通信 | Spring WebSocket | - |
| 前端框架 | Vue | 2.7.16 |
| 前端可视化 | ECharts | 5.6 |
| 前端 UI | Element UI | 2.15 |
| 前端路由 | vue-router | 3.6 |
| 前端 HTTP | axios | 1.6 |
| 构建工具 | Maven / Vue CLI | - |

### 1.2 研究背景

随着区域网络规模持续扩大，网络拥塞与恶意攻击（如 DDoS、端口扫描）频发，严重影响网络稳定性与安全性。多数中小型区域网络仍采用人工巡检或简单 Ping 命令进行状态检查，存在以下问题：

1. **传统 SNMP 监控局限**：仅能获取流量统计值，无法识别 DDoS、端口扫描等恶意行为的具体特征，导致安全事件响应滞后。
2. **缺乏历史数据支撑**：容量规划与故障回溯困难。
3. **商业网管平台成本高**：部署成本高、学习门槛大，不适合中小型网络使用。

SNMP 作为 TCP/IP 网络管理的行业标准（RFC 1157），被几乎所有网络设备厂商支持，为低成本网络监控提供了技术基础。Pcap4j 作为 Java 生态中高性能的包捕获库，能够深入数据包内部进行协议解析与特征提取，为恶意流量深度检测提供了可能。

### 1.3 研究意义

本课题提出融合 SNMP 宏观统计与 Pcap4j 微观抓包的协同检测方案，在保持低开销状态巡检的同时，实现数据包级的深度分析。系统能够自动采集区域内网络设备的端口流量数据，以可视化图表展示实时状态和历史趋势，并在流量异常或检测到恶意攻击时发出告警。该研究可弥补单一监控手段的不足，为网络异常快速定界与攻击溯源提供技术支撑，帮助管理员从"被动救火"转向"主动运维"，提高区域网络的可用性和管理效率。

### 1.4 功能特性

#### 已实现模块

- 通过 SNMP v2c 协议采集设备信息（sysName / sysUpTime / 在线状态）
- 通过 SNMP 周期性采集接口流量计数器（ifInOctets / ifOutOctets），计算实时速率（bps）
- 多设备统一管理（设备增删改查，支持至少 10 台设备）
- 流量数据持久化至 MySQL，支持历史趋势查询（最近 30 条）
- 网络拓扑数据接口（12 节点 + 11 链路），供前端 ECharts Graph 渲染
- 流量概览统计（总入/出速率、在线数、异常数）
- 基于 WebSocket 的实时遥测数据广播，前端无需轮询
- Counter32 回绕处理，保证长时间运行计数正确性

#### 规划中模块（开题报告核心内容）

- **混合采集架构**：基于 SNMP4J 的状态采集器与基于 Pcap4j 的旁路抓包引擎协同工作
- **动态基线异常检测**：基于时间序列的滑动窗口算法，根据历史流量特征动态生成安全基线（μ±2σ），自动发现流量突发异常
- **攻击特征识别引擎**：利用 Pcap4j 进行旁路抓包，解析 TCP/IP 协议栈，设计多模式匹配规则库，精准识别 SYN Flood、UDP Flood 及端口扫描行为
- **告警阈值配置**：支持自定义流量阈值与端口告警阈值
- **历史数据高效存储**：支持至少 30 天历史数据查询

---

## 二、项目结构

```
Kuangjia/
├── src/main/java/com/smartmonitor/
│   ├── SmartNetMonitorApplication.java     # 启动类（开启 @EnableScheduling）
│   ├── config/
│   │   ├── SnmpConfig.java                 # 跨域配置（/api/**）
│   │   └── WebSocketConfig.java           # WebSocket 配置，注册 /ws/metrics
│   ├── controller/
│   │   ├── DeviceController.java          # 设备 CRUD（/api/device）
│   │   ├── TopologyController.java        # 网络拓扑数据（/api/topology）
│   │   └── TrafficController.java          # 流量查询（/api/traffic）
│   ├── entity/
│   │   ├── Device.java                    # 设备实体（JPA 持久化）
│   │   ├── DeviceInfo.java                # SNMP 实时采集结果 DTO
│   │   └── TrafficData.java               # 流量数据实体（JPA 持久化）
│   ├── repository/
│   │   ├── DeviceRepository.java          # 设备 JPA 仓储
│   │   └── TrafficDataRepository.java     # 流量数据 JPA 仓储
│   ├── scheduler/
│   │   └── MonitorScheduler.java          # 定时任务调度器
│   ├── service/
│   │   ├── DeviceService.java             # 设备服务接口
│   │   ├── SnmpService.java               # SNMP 服务接口
│   │   └── impl/
│   │       ├── DeviceServiceImpl.java     # 设备服务实现
│   │       └── SnmpServiceImpl.java       # SNMP 服务实现
│   └── websocket/
│       └── MetricsWebSocketHandler.java   # WebSocket 广播处理器
├── src/main/resources/
│   ├── application.yml                     # 本地配置
│   └── application-docker.yml              # Docker 环境配置
├── docker/
│   ├── init.sql                            # MySQL 初始化脚本
│   └── snmp-simulator/                     # SNMP 模拟器（Python）
├── snmp-monitor-ui/                        # Vue 前端
│   └── src/
│       ├── api/                            # 接口封装（device/metrics/topology）
│       ├── router/                         # 路由配置
│       ├── utils/websocket.js              # WebSocket 客户端
│       └── views/                          # Dashboard / Topology / DeviceManage
└── pom.xml                                 # Maven 配置
```

---

## 三、核心代码说明

### 3.1 pom.xml - Maven 依赖配置

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
</parent>

<dependencies>
    <!-- Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- SNMP4J -->
    <dependency>
        <groupId>org.snmp4j</groupId>
        <artifactId>snmp4j</artifactId>
        <version>3.7.7</version>
    </dependency>
    <!-- Spring Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <!-- MySQL 驱动 -->
    <dependency>
        <groupId>mysql</groupId>
        <artifactId>mysql-connector-java</artifactId>
        <version>8.0.33</version>
        <scope>runtime</scope>
    </dependency>
    <!-- WebSocket -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
</dependencies>
```

**依赖说明**：

| 依赖 | 作用 |
| :--- | :--- |
| `spring-boot-starter-web` | RESTful API 支持 |
| `snmp4j` | SNMP v2c 协议通信 |
| `spring-boot-starter-data-jpa` | ORM 持久层（Hibernate） |
| `mysql-connector-java` | MySQL JDBC 驱动 |
| `spring-boot-starter-websocket` | WebSocket 实时推送 |

### 3.2 application.yml - 本地配置

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/smart_monitor?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
    username: root
    password: your_password      # 请改为本机 MySQL 密码
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update           # 启动时自动建表/更新表结构
    show-sql: true

snmp:
  timeout: 2000                 # 请求超时(毫秒)
  retries: 2                    # 重试次数
```

> Docker 环境使用 `application-docker.yml`，MySQL 主机名改为 `mysql`，密码通过 `${MYSQL_PASSWORD:123456}` 注入。

### 3.3 SmartNetMonitorApplication.java - 启动类

```java
@SpringBootApplication
@EnableScheduling   // 开启定时任务
public class SmartNetMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartNetMonitorApplication.class, args);
        System.out.println("🚀 局域网监控系统启动成功！");
    }
}
```

### 3.4 配置层

#### SnmpConfig.java - 跨域配置

> 注意：此类名为 `SnmpConfig`，实际职责是配置 CORS 跨域，允许前端（`http://localhost:8082`）访问后端 `/api/**` 接口。

```java
@Configuration
public class SnmpConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

#### WebSocketConfig.java - WebSocket 配置

注册 WebSocket 端点 `/ws/metrics`，由 `MetricsWebSocketHandler` 处理。

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(metricsWebSocketHandler, "/ws/metrics")
                .setAllowedOrigins("*");
    }
}
```

### 3.5 实体层

#### Device.java - 设备实体（JPA 持久化）

| 字段 | 类型 | 持久化 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | Long | 是 | 主键，自增 |
| `ip` | String | 是 | 设备 IP，唯一 |
| `type` | String | 是 | 设备类型 |
| `community` | String | 是 | SNMP 社区字符串，默认 `public` |
| `remark` | String | 是 | 备注 |
| `name` | String | 否（@Transient） | SNMP 实时采集的 sysName |
| `status` | String | 否（@Transient） | 在线 / 离线 |
| `upTime` | String | 否（@Transient） | SNMP 实时采集的 sysUpTime |

#### DeviceInfo.java - SNMP 实时采集结果 DTO

承载 `name` / `upTime` / `status` 三个字段，由 `SnmpService.collectDeviceInfo()` 返回。

#### TrafficData.java - 流量数据实体（JPA 持久化）

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | Long | 主键，自增 |
| `deviceId` | Long | 关联设备 ID |
| `deviceIp` | String | 采集时设备 IP |
| `deviceName` | String | 采集时设备名 |
| `inOctets` | Long | SNMP 累计入字节数（ifInOctets） |
| `outOctets` | Long | SNMP 累计出字节数（ifOutOctets） |
| `inBps` | Long | 入方向速率（bps），由两次差值计算 |
| `outBps` | Long | 出方向速率（bps） |
| `isAnomaly` | Integer | 0=正常，1=异常 |
| `collectTime` | LocalDateTime | 采集时间 |

#### AgentAction.java - 智能体「小七」决策记录实体

智能体命名为「小七」，7×24 小时值守，根据告警历史自动生成响应决策并落库。

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | Long | 主键，自增 |
| `deviceId` | Long | 关联设备 ID（全网级决策为 null） |
| `deviceName` | String | 设备名称 |
| `actionTime` | LocalDateTime | 决策时间 |
| `actionType` | String | ISOLATE / DISCONNECT / ADJUST_THRESHOLD / ALERT_ESCALATE / RETRAIN_MODEL |
| `reason` | String | 决策原因（以【小七】署名） |
| `detail` | String | JSON 格式研判依据（攻击类型、命中次数、异常评分等） |
| `severity` | String | low / medium / high / critical |
| `confidence` | Double | 决策置信度 0.00~1.00，小七按证据强度评估 |
| `status` | Integer | 0=待处理，1=已执行，2=已驳回，3=自动执行 |
| `executeTime` | LocalDateTime | 执行/驳回时间 |
| `note` | String | 管理员处理备注（自动执行时为"按自动执行策略放行"） |

#### AgentPolicy.java - 小七自动执行策略实体

每类决策一条配置，控制该类决策生成后是否免人工确认自动执行。

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | Long | 主键，自增 |
| `actionType` | String | 决策类型，唯一 |
| `autoExecute` | Boolean | 是否自动执行 |
| `updatedTime` | LocalDateTime | 最后更新时间 |
| `updatedBy` | String | 最后更新人（system=启动默认初始化） |

### 3.6 仓储层

#### DeviceRepository.java

```java
public interface DeviceRepository extends JpaRepository<Device, Long> {
}
```

#### TrafficDataRepository.java

```java
public interface TrafficDataRepository extends JpaRepository<TrafficData, Long> {
    TrafficData findFirstByDeviceIdOrderByCollectTimeDesc(Long deviceId);
    List<TrafficData> findTop30ByDeviceIdOrderByCollectTimeAsc(Long deviceId);

    @Query("SELECT t FROM TrafficData t WHERE t.collectTime = " +
           "(SELECT MAX(t2.collectTime) FROM TrafficData t2 WHERE t2.deviceId = t.deviceId)")
    List<TrafficData> findLatestForAllDevices();
}
```

#### AgentActionRepository.java

```java
public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {
    List<AgentAction> findByStatusOrderByActionTimeDesc(Integer status);
    List<AgentAction> findAllByOrderByActionTimeDesc();
    List<AgentAction> findByDeviceIdAndStatusOrderByActionTimeDesc(Long deviceId, Integer status);
    long countByActionTimeAfter(LocalDateTime time);
    long countByDeviceIdAndActionTypeAndActionTimeAfter(Long deviceId, String actionType, LocalDateTime time);
}
```

### 3.7 服务层

#### SnmpService 接口

```java
public interface SnmpService {
    DeviceInfo collectDeviceInfo(String ip, String community);
    long[] collectTraffic(String ip, String community);  // {inOctets, outOctets}
}
```

#### SnmpServiceImpl 实现

- `collectDeviceInfo`：一次 SNMP GET 同时获取 sysName（OID `1.3.6.1.2.1.1.5.0`）和 sysUpTime（OID `1.3.6.1.2.1.1.3.0`），返回 `DeviceInfo`。设备不可达时返回状态为"离线"的 `DeviceInfo`。
- `collectTraffic`：一次 SNMP GET 获取 ifInOctets（OID `1.3.6.1.2.1.2.2.1.10.1`）和 ifOutOctets（OID `1.3.6.1.2.1.2.2.1.16.1`），返回 `long[]{inOctets, outOctets}`，设备不可达时返回 `null`。

**SNMP 采集流程**：

| 步骤 | 操作 | 说明 |
| :--- | :--- | :--- |
| 1 | 创建目标地址 | `udp:{ip}/161` |
| 2 | 配置 CommunityTarget | 社区字符串、超时、重试次数、v2c |
| 3 | 创建 UDP Transport | `DefaultUdpTransportMapping` |
| 4 | 构造 PDU | 添加 OID，类型为 GET |
| 5 | 发送请求 | `snmp.send(pdu, target)` |
| 6 | 解析响应 | 按 OID 匹配提取 VariableBinding 值 |
| 7 | 资源释放 | finally 中关闭 Snmp |

#### DeviceService 接口与 DeviceServiceImpl 实现

设备 CRUD（listAll / getById / add / update / delete），在查询时调用 `SnmpService.collectDeviceInfo()` 实时填充 `name` / `status` / `upTime` 三个瞬时字段。

```java
private Device fillRealtime(Device device) {
    DeviceInfo info = snmpService.collectDeviceInfo(device.getIp(), device.getCommunity());
    device.setName(info.getName());
    device.setStatus(info.getStatus());
    device.setUpTime(info.getUpTime());
    return device;
}
```

#### AgentDecisionEngine - 智能体「小七」决策引擎

由告警入库流程调用 `evaluate(AlertInfo)`，基于规则生成响应决策，去重冷却避免重复建议：

| 规则 | 触发条件 | 决策类型 | 置信度 | 动态门槛（驳回反哺） |
| :--- | :--- | :--- | :--- | :--- |
| 1 | 同设备 5 分钟内高危 DDoS/蠕虫 ≥3 次 | ISOLATE 建议隔离 | 0.72 起，每多 1 次 +0.08，封顶 0.98 | 每被驳回 2 次门槛 +1（3→5） |
| 2 | critical 数据外泄（DATA_EXFIL） | DISCONNECT 建议断网 | 0.95 | — |
| 3 | 任意 critical 告警 | ALERT_ESCALATE 告警升级 | 0.88 | — |
| 4 | 10 分钟内全网 high/critical 告警 ≥10 条 | RETRAIN_MODEL 模型重训 | 0.80 | 每被驳回 1 次门槛 +2 条（10→20） |

**驳回反哺**：每类决策去重冷却 = 5 分钟 + 该类历史驳回次数 × 5 分钟，封顶 30 分钟（`countByActionTypeAndStatus(type,2)` 实时统计）。**自动执行**：每条决策生成时调用 `AgentPolicyService.isAutoExecute(type)`，为 true 则直接置 status=3 并备注"按自动执行策略放行"。

其他方法：`executeAction/rejectAction` 更新状态与备注；`getStats()` 返回今日/待处理/已执行/已驳回/自动执行/采纳率/平均置信度/最近研判，以及 `byType` 按类型明细（各类型待处理/执行/自动/驳回数、采纳率、当前自动执行开关、生效冷却分钟数与门槛文案）；`getProfile()` 返回小七档案。

#### AgentPolicyService - 自动执行策略服务

`@PostConstruct initDefaults()` 在 JPA 建表后初始化 5 类默认策略（仅 ALERT_ESCALATE 默认自动执行）；`listPolicies()` 惰性补齐缺失类型；`isAutoExecute(type)` 供引擎查询；`updatePolicies(Map<类型, 开关>, 操作人)` 批量更新并记录 updatedBy。

#### AgentChatService - 小七对话问答服务

`POST /api/agent/chat`（请求体 `{"message": "..."}`）背后的轻量意图引擎，基于中文关键词规则识别意图并实时聚合各仓储数据，返回 `{reply, intent, suggestions}`：

| intent | 触发示例 | 回答内容 |
| :--- | :--- | :--- |
| intro / help / greeting | 你是谁 / 帮助 / 你好 | 自我介绍、能力清单、问候 |
| offline | 谁离线了 / 设备掉线 | 30 秒窗口内无流量上报的设备名+IP 清单 |
| status | 设备都正常吗 / 几台在线 | 纳管总数、在线/离线数、异常设备数 |
| alerts | 最近有什么攻击 | 今日告警数、攻击类型分布（countByAttackType）、高危未处理数、最近 3 条 |
| actions | 小七给了哪些建议 | 待处理决策数、类型分布、Top3 高优先级建议及置信度 |
| traffic | 全网流量怎么样 | 实时总入/出速率、流量 Top3 设备 |
| model | AI 怎么检测的 | Transformer 70% + IsolationForest 30% 混合检测说明 |
| device | 直接带设备名/IP（优先匹配，名称≥3字符） | 该设备在线状态、实时流量、异常标记、最近一条告警 |
| fallback | 无法识别 | 能力提示与引导 |

### 3.8 控制层

#### DeviceController - 设备管理 `/api/device`

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/device` | 查询全部设备（含 SNMP 实时采集） |
| GET | `/api/device/{id}` | 查询单个设备 |
| POST | `/api/device` | 新增设备 |
| PUT | `/api/device` | 修改设备 |
| DELETE | `/api/device/{id}` | 删除设备 |

#### TopologyController - 网络拓扑 `/api/topology`

返回 12 个网络节点 + 11 条链路，模拟中小型企业网（核心层 → 汇聚层 → 接入层 → 终端层），供前端 ECharts Graph 渲染。

节点字段：`id` / `name` / `deviceType` / `ip` / `category`（0-3）/ `symbol`（core/router/switch/pc/server/ap/printer）。

链路字段：`source` / `target` / `linkType`（千兆光纤/千兆以太网/百兆以太网）/ `bandwidth`（Mbps）。

#### TrafficController - 流量查询 `/api/traffic`

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/traffic/latest` | 所有设备最新流量 |
| GET | `/api/traffic/history/{deviceId}` | 设备历史趋势（最近 30 条，升序） |
| GET | `/api/traffic/overview` | 流量概览（总入/出速率、在线数、异常数） |

#### AgentController - 智能体「小七」`/api/agent`

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/agent/profile` | 小七档案（名称、版本、职责、能力标签） |
| GET | `/api/agent/policies` | 查询 5 类决策的自动执行策略 |
| PUT | `/api/agent/policies` | 更新策略（body: `{updatedBy, policies:{类型:布尔}}`） |
| POST | `/api/agent/chat` | 小七对话问答（body: `{message}` → reply/intent/suggestions） |
| GET | `/api/agent/actions?status=` | 决策列表（可按状态过滤） |
| GET | `/api/agent/stats` | 统计（今日/待处理/已执行/已驳回/自动执行/采纳率/平均置信度/最近研判） |
| PUT | `/api/agent/actions/{id}/execute?note=` | 执行决策（可带备注） |
| PUT | `/api/agent/actions/{id}/reject?note=` | 驳回决策（可带备注） |

### 3.9 定时任务调度器 MonitorScheduler

包含两个 `@Scheduled` 任务：

| 任务 | 频率 | 作用 |
| :--- | :--- | :--- |
| `monitorDevice()` | 每 5 分钟（300000ms） | 对所有设备做 SNMP 状态采集并打印日志 |
| `collectTraffic()` | 每 10 秒（10000ms） | 采集所有设备 ifInOctets/ifOutOctets，计算 bps 速率，存入 MySQL，并广播 WebSocket |

**流量速率计算逻辑**：

1. SNMP GET 获取当前 `inOctets` / `outOctets`
2. 查询该设备上一条 `TrafficData` 记录
3. 计算 `seconds` = 两次采集时间差
4. 计算 `delta` = 当前 - 上次；若 `delta < 0`（Counter32 回绕），则 `delta += 0xFFFFFFFF + 1`
5. `inBps = inDelta * 8 / seconds`（字节转比特）
6. 存入 `traffic_data` 表，构建推送数据调用 `webSocketHandler.broadcast()`

**WebSocket 推送格式**：

```json
{
  "timestamp": 1692000001000,
  "devices": [
    {
      "deviceId": 1,
      "deviceName": "AR1-Huawei-Core",
      "online": true,
      "inBps": 1250000,
      "outBps": 830000,
      "isAnomaly": 0
    }
  ]
}
```

### 3.10 WebSocket 处理器 MetricsWebSocketHandler

- 继承 `TextWebSocketHandler`，使用 `CopyOnWriteArrayList` 维护所有在线会话
- `afterConnectionEstablished` / `afterConnectionClosed` 管理连接列表
- `broadcast(Object data)`：用 Jackson `ObjectMapper` 序列化为 JSON，向所有 open 会话发送 `TextMessage`

### 3.11 DeepSeek V4 大模型接入（DeepSeekService）

系统接入 DeepSeek 大模型（默认模型 `deepseek-flash`，即 V4.1-Flash，OpenAI 兼容协议），共两处使用，且均以「大模型优先、规则引擎兜底」的方式保证可用性：

| 文件 | 作用 |
| :--- | :--- |
| `service/DeepSeekService.java` | 基于 JDK11 内置 `java.net.http.HttpClient` 调用 `{base-url}/chat/completions`；Bearer 鉴权；请求体顶层 `"thinking":{"type":"disabled"}` 关闭思考模式（响应快、费用低）；`isEnabled()` 仅在配置了 Key 时为 true；网络/限流/业务错误统一抛内部类 `DeepSeekException` |
| `service/AgentChatService.java` | 小七问答：帮助/空提问直接走本地；DeepSeek 可用时用 `buildSystemPrompt()`（角色设定 + `buildBusinessSnapshot()` 拼装的设备/告警/决策/流量实时快照）调用大模型，响应新增 `engine=deepseek`、`model` 字段；异常或未配置 Key 时降级到原有关键词规则引擎 `ruleChat()` |
| `service/AlertService.java` | 告警逐条 AI 研判 `analyzeWithAi(id)`：已研判过直接返回库中缓存（避免重复调用计费）；首次调用把设备名/IP/攻击类型/等级/评分/置信度/流量等拼成 prompt，要求模型输出「威胁判断 / 可能成因 / 处置建议」三段短文；结果写入 `alert_info.ai_analysis`；未配置 Key 或调用异常时返回友好兜底文案且 HTTP 200，不抛 500 |
| `controller/AlertController.java` | 新增 `POST /api/alerts/{id}/analyze` |

配置项（`application-docker.yml`，均可被同名环境变量覆盖）：`deepseek.api-key`（默认空，**只从环境变量 `DEEPSEEK_API_KEY` 注入，禁止硬编码**）、`deepseek.base-url`、`deepseek.model`、`deepseek.timeout-seconds`（30s）。

### 3.12 接口鉴权（JWT + 拦截器，零第三方依赖）

系统对全部 `/api/**` 接口实施令牌认证与角色鉴权，不再允许匿名调用：

| 文件 | 作用 |
| :--- | :--- |
| `security/JwtUtil.java` | JWT 规范的 HS256 实现，仅用 JDK 原生 `javax.crypto.Mac`（HmacSHA256）+ Jackson，**未引入 jjwt 等第三方依赖**；`generate(uid,username,role)` 签发、`parse(token)` 验签（常量时间比较防时序攻击）并校验 `exp` 过期；声明含 `sub/uid/role/iat/exp` |
| `security/JwtInterceptor.java` | 从 `Authorization: Bearer <token>` 取令牌，解析失败返回 **401** JSON；`/api/user/**` 用户管理接口要求 `admin` 角色，否则 **403**；CORS 预检 OPTIONS 直接放行；解析出的用户信息放入 request attribute |
| `config/WebMvcConfig.java` | 注册拦截器：`addPathPatterns("/api/**")`，白名单仅 `excludePathPatterns("/api/user/login", "/error")`。`/ws/**` WebSocket 握手不在 `/api` 下，实时大盘可匿名订阅 |
| `controller/UserController.java` | 登录成功后在响应顶层返回 `token`（12 小时有效） |

认证数据流：`POST /api/user/login`（账号密码，库中 MD5 校验）→ 返回 JWT → 前端存 `localStorage` → axios 拦截器对每个请求自动附加 `Authorization: Bearer <token>` → 收到 401 自动清除登录态并跳转登录页。

前端实现：`utils/auth.js` 管理 `smart_monitor_token`；`api/request.js` 提供 `createRequest()` 统一实例工厂（6 个 `api/*.js` 与 Agent.vue 全部改用它）与 `attachAuthInterceptors()`（供 Alert.vue 等直接使用全局 axios 的页面，在 `main.js` 中安装）。

配置项：`app.jwt.secret`（环境变量 `JWT_SECRET`，生产环境必须注入随机密钥）、`app.jwt.expire-hours`（默认 12）。

**鉴权矩阵**：

| 场景 | 结果 |
| :--- | :--- |
| 无 token / 伪造 token / 过期 token 访问 `/api/**` | 401 + 中文 JSON 错误提示 |
| admin / operator / viewer 携带合法 token 访问业务接口 | 200 |
| 非 admin 角色访问 `/api/user/**`（用户增删改查、重置密码） | 403 |
| 访问 `/api/user/login`、`/ws/**` | 匿名放行 |

初始账号（`docker/init.sql`，密码均为 `123456`，MD5 存储）：`admin`（系统管理员）/ `operator`（运维操作员），另有 viewer 只读角色可由管理员创建。

### 3.13 数据模式：真实采集 vs 模拟演示（双模式，默认真实）

系统的数据**唯一采集入口是 SNMP4J**（项目名中的 Pcap4j 旁路抓包仍为开题规划，未实现）。为兼顾"真实数据实测"与"答辩无设备时演示"，通过 docker compose **profile** 提供两种互斥模式，模拟器文件保留但默认不启动：

| 模式 | 启动命令 | 容器 | 数据源 |
| :--- | :--- | :--- | :--- |
| **真实模式（默认）** | `docker compose up -d` | 4 个：mysql / ai-detector / backend / frontend | 真实设备或 eNSP 仿真设备的真实 SNMP 响应 |
| 模拟演示模式 | `docker compose --profile sim up -d` | 16 个（额外 12 台 SNMP 模拟器） | `docker/snmp-simulator` 容器伪造的 SNMP 数据 |

关键改动：

1. **12 个模拟器服务**（`sim-core-sw`、`sim-fw-gateway`、`sim-ar1~3`、`sim-sw1~3`、`sim-pc1`、`sim-server1`、`sim-ap1`、`snmp-simulator`）统一加 `profiles: ["sim"]`；`backend.depends_on` 移除对它们的依赖。不带 profile 启动时这些容器不会运行，后端也不再等待它们。
2. **网络发现网段可配置**（[DiscoveryService.java](file:///d:/毕设/Kuangjia/src/main/java/com/smartmonitor/service/DiscoveryService.java)）：后端容器内网卡只能看到 Docker 内网（172.21.0.x），真实模式若仍靠"自动探测网卡"只会扫到内网。新增 `snmp.discovery.segment`（环境变量 `SNMP_DISCOVERY_SEGMENT`）后，`detectNetworkSegment()` 优先返回配置网段；`snmp.community`（`SNMP_COMMUNITY`，默认 public）作为扫描/加入设备时的默认团体字。
3. **AI 训练禁用一切合成数据**（[main.py](file:///d:/毕设/Kuangjia/ai-detector/app/main.py)）：删除 `_generate_synthetic_data()` / `_generate_synthetic_sequences()`，`load_training_data()` 与 `load_training_sequences()` 在真实历史不足或读库异常时**直接抛出明确错误，不再用 `np.random` 造假数据训练**。`train_model()` 捕获后返回 False，模型保持未训练状态；此时 `detect_batch` 返回 503，`MonitorScheduler` 捕获后**仅推送真实采集到的基础流量、不产生告警**，直到真实数据积累足够并训练成功。手动训练接口 `/api/ai/train` 在数据不足时返回 `200 + {success:false, message:"真实训练数据不足：...仅 N 条..."}` 而非裸 500。
4. **历史模拟数据清理脚本** [reset-sim-data.sql](file:///d:/毕设/Kuangjia/docker/reset-sim-data.sql)：删除 IP 属于 Docker 网段 `172.21.0.%`（及历史容器名 `sim-*`）的设备及其 traffic_data / alert_info / agent_action 数据，保留 sys_user。真实/eNSP 设备 IP 在宿主机网段，不会被误删。

> 设计原则：真实数据不可用时，系统选择"空着/明确报错"，绝不生成或回退任何模拟数据。

---

## 四、数据库设计

`docker/init.sql` 定义两张表（JPA `ddl-auto: update` 也会自动建表）：

### 4.1 device 表

| 字段 | 类型 | 约束 |
| :--- | :--- | :--- |
| id | BIGINT | PK, AUTO_INCREMENT |
| ip | VARCHAR(255) | NOT NULL, UNIQUE |
| type | VARCHAR(255) | - |
| community | VARCHAR(255) | - |
| remark | VARCHAR(255) | - |

### 4.2 traffic_data 表

| 字段 | 类型 | 约束 |
| :--- | :--- | :--- |
| id | BIGINT | PK, AUTO_INCREMENT |
| device_id | BIGINT | INDEX (device_id, collect_time) |
| device_ip | VARCHAR(255) | - |
| device_name | VARCHAR(255) | - |
| in_octets | BIGINT | - |
| out_octets | BIGINT | - |
| in_bps | BIGINT | - |
| out_bps | BIGINT | - |
| is_anomaly | INT | DEFAULT 0 |
| collect_time | DATETIME | - |

### 4.3 agent_action 表（智能体小七决策记录，JPA 自动建表）

| 字段 | 类型 | 约束 |
| :--- | :--- | :--- |
| id | BIGINT | PK, AUTO_INCREMENT |
| device_id | BIGINT | 可空（全网级决策为 NULL） |
| device_name | VARCHAR(255) | - |
| action_time | DATETIME | 决策时间 |
| action_type | VARCHAR(255) | ISOLATE/DISCONNECT/ADJUST_THRESHOLD/ALERT_ESCALATE/RETRAIN_MODEL |
| reason | VARCHAR(1024) | 决策原因（【小七】署名） |
| detail | VARCHAR(2048) | JSON 研判依据 |
| severity | VARCHAR(32) | low/medium/high/critical |
| confidence | DOUBLE | 置信度 0~1（历史数据可能为 NULL，前端显示"—"） |
| status | INT | 0待处理/1已执行/2已驳回/3自动执行 |
| execute_time | DATETIME | 可空 |
| note | VARCHAR(512) | 管理员备注，可空 |

### 4.4 agent_policy 表（小七自动执行策略，JPA 自动建表）

| 字段 | 类型 | 约束 |
| :--- | :--- | :--- |
| id | BIGINT | PK, AUTO_INCREMENT |
| action_type | VARCHAR(32) | UNIQUE NOT NULL，5 类决策各一条 |
| auto_execute | BIT(1) | NOT NULL，是否免人工确认自动执行 |
| updated_time | DATETIME | 最后更新时间 |
| updated_by | VARCHAR(64) | system 初始化或操作账号 |

### 4.5 alert_info 表（入侵告警，JPA 自动建表/加列）

核心字段：id、device_id、device_name、device_ip、alert_time、attack_type、severity、anomaly_score、confidence、in_bps、out_bps、description、status(0未处理/1已确认/2误报/3已处理)、handle_note、handle_time。

DeepSeek 接入后新增两列（`ddl-auto: update` 启动时自动添加）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| ai_analysis | TEXT | DeepSeek 生成的 AI 研判正文，懒生成并缓存，不随兜底结果写入 |
| ai_analysis_time | DATETIME | AI 研判生成时间 |

---

## 五、前端结构（snmp-monitor-ui）

Vue 2.7 + Element UI + ECharts 单页应用，前端开发端口 `8082`，通过 axios 调用后端 `/api/**`，通过 WebSocket 连接 `/ws/metrics`。

```
snmp-monitor-ui/src/
├── api/
│   ├── device.js          # 设备接口
│   ├── metrics.js         # 流量指标接口
│   └── topology.js        # 拓扑接口
├── router/index.js        # 路由（Dashboard / Topology / DeviceManage）
├── utils/websocket.js     # WebSocket 客户端封装
├── views/
│   ├── Dashboard.vue      # 仪表盘（流量曲线 + 概览统计）
│   ├── Topology.vue        # 网络拓扑图（ECharts Graph）
│   ├── Agent.vue           # 智能小七页（身份横幅/置信度/研判依据展开/执行驳回备注）
│   └── DeviceManage.vue    # 设备管理（类型分类卡片/筛选/CRUD）
├── App.vue                # 主组件（含导航栏，侧边栏按"态势感知/安全响应/系统管理"三组分）
└── main.js                # 入口
```

**侧边栏分组**：8 个页面按功能域归入三个可折叠分组（默认全展开）——态势感知（实时监控大屏/网络拓扑/网络发现）、安全响应（入侵告警/智能小七/AI 模型监控）、系统管理（设备管理/用户管理），子菜单深色缩进样式适配。

**设备分类管理（DeviceManage.vue）**：页面顶部为设备类型分类卡片（全部 + 核心交换机/交换机/路由器/防火墙/服务器/无线AP/终端PC，数据中出现的未知类型自动追加），每张卡片展示该类型设备总数与在线数，点击卡片即按类型筛选（与查询区的"设备类型"下拉双向联动，再次点击取消）；分类常量 `DEVICE_TYPES` 同时收口统计卡片、筛选下拉与新增/编辑弹窗的选项，保证分类体系单一数据源。分类卡为扁平描边风格，选中态为蓝边 + `#f0f7ff` 浅蓝底（无渐变无浮起）。

**拓扑层级分类筛选（Topology.vue）**：左下角图例为可点击的分类开关——核心层 / 汇聚层 / 接入层 / 终端层（各带实时节点数）与离线设备开关；点击任意图例即过滤对应层级节点（连线自动仅保留两端均可见的边），对树形 / 力导向两种布局同时生效，再次点击恢复。层级色板统一为低饱和色（核心 `#3a7bd5`、汇聚 `#52a55c`、接入 `#e3a21f`、终端 `#94a0b4`，离线灰底红圈），已移除 ECharts 内置重复图例与节点发光阴影。

**全站简约视觉规范**（Dashboard 深色大屏、小七深色横幅除外）：页面底色统一 `#f7f8fa`、卡片白底 `#ebeef5` 细边框且默认无悬浮阴影、数值字重 600；去除所有彩色渐变（登录页改为主侧栏同色 `#304156` 纯色、AI 状态卡改为白底 + 语义色图标块、流程序号改为浅蓝底蓝字）；顶栏以底边线替代投影，路由进度条改为纯色 `#3a7bd5`。

**智能小七页（Agent.vue）**：顶部为小七身份横幅（动态头像、版本 v1.2、能力标签、建议采纳率 / 平均置信度 / 自动执行三项指标）与"小七最新研判"提示条；决策表格支持按状态筛选、置信度进度条（≥85% 绿 / ≥60% 蓝 / 其余橙，历史空值显示"—"）、行展开查看中文研判依据、执行/驳回时弹出备注框，10 秒轮询刷新。工具栏「自动执行策略」弹窗：每类决策一个开关，同步展示其生效门槛、去重冷却、采纳率与被驳回次数（驳回反哺效果可视化），保存时携带当前登录用户名。右下角为**小七悬浮问答窗**：脉冲"七"字按钮唤起，支持自然语言提问（设备状态、攻击告警、处理建议、全网流量、AI 原理、指定设备追问），含打字等待动画、气泡消息流与推荐问题快捷标签。

---

## 六、部署与使用

### 6.1 本地运行

1. 准备 MySQL 8.0，创建数据库 `smart_monitor`
2. 修改 `application.yml` 中的 `password`
3. 启动后端：`mvn spring-boot:run`（端口 8081，JPA 自动建表）
4. 启动前端：`cd snmp-monitor-ui && npm install && npm run serve`（端口 8082）
5. 通过 `/api/device` POST 添加设备，或直接插入 `device` 表
6. 浏览器访问 `http://localhost:8082`

### 6.2 Docker 部署（默认真实模式 / 可选模拟演示）

```bash
cd Kuangjia

# 真实模式（默认）：只启动 4 个核心容器，数据全部来自真实设备 / eNSP
docker compose up -d

# 模拟演示模式：额外启动 12 台 SNMP 模拟器，共 16 个容器（无真实硬件时答辩用）
docker compose --profile sim up -d
```

**真实模式**启动 4 个容器（MySQL + AI 检测 + Spring Boot 后端 + Vue 前端），此时 `device` 表为空、大盘显示 0 设备，需要先按 [6.3 节](#) 接入 eNSP/真实设备并做网络发现，系统**不会生成任何模拟数据**。

**模拟演示模式**（`--profile sim`）额外启动 12 台 SNMP 模拟器（11 台拓扑设备 + 1 台老版兼容容器），开箱即用，可演示 12 台区域网设备的实时流量监控与 AI 告警。两种模式随时互切；从模拟切到真实后，如需清掉旧的模拟历史，执行：

```bash
docker exec -i smart-monitor-mysql mysql -uroot -p123456 smart_monitor < docker/reset-sim-data.sql
```

> **启用 DeepSeek V4 大模型（可选）**：在项目根目录 `.env` 文件中填入 `DEEPSEEK_API_KEY=sk-...`（在 https://platform.deepseek.com 申请），docker compose 会自动读取并注入后端容器，随后 `docker compose up -d`（或重启 backend）即可。未填写时小七问答与告警 AI 研判自动降级为内置规则引擎，系统功能不受影响。Key 只保存在本地 `.env`，切勿提交到代码仓库。

> **登录账号**：系统已开启 JWT 接口鉴权，打开前端后需先登录。默认账号 `admin / 123456`（管理员，可管理用户）、`operator / 123456`（操作员，业务功能不受限、不可管理用户）。所有 `/api/**` 接口匿名访问均返回 401。

```
前端地址:
  http://127.0.0.1:80/       (默认 http 端口)
  http://127.0.0.1:8080/     (备选, 避免 80 端口冲突)

后端 API / WebSocket:
  http://127.0.0.1:8081/api/traffic/overview     # 总览
  http://127.0.0.1:8081/api/traffic/latest       # 每台设备最新一条
  http://127.0.0.1:8081/api/traffic/history/{id} # 设备历史曲线
  ws://127.0.0.1:8081/ws/metrics                 # WebSocket 实时推送
```

#### 6.2.1 容器清单

| 容器名                     | service 名        | 功能                                 | 典型入速率 | 说明             |
|:-------------------------- |:---------------- |:------------------------------------ |:---------- |:---------------- |
| smart-monitor-mysql        | mysql             | MySQL 8.0 持久化存储                  | —           | 3306, root/123456 |
| smart-monitor-backend      | backend           | Spring Boot 采集/存储/API/WS         | —           | 8081             |
| smart-monitor-frontend     | frontend          | Vue + nginx 可视化大屏                | —           | 80 / 8080        |
| smart-sim-core-sw          | sim-core-sw       | **核心层** S12700E 核心交换机         | 150 Mbps   | 4 接口, 大带宽    |
| smart-sim-fw               | sim-fw-gateway    | **核心层** USG6655E 出口防火墙       | 120 Mbps   | NAT + 突发       |
| smart-sim-ar1              | sim-ar1           | **汇聚层** AR6280-S 办公区汇聚       | 80 Mbps    |                  |
| smart-sim-ar2              | sim-ar2           | **汇聚层** AR6280-S 数据中心汇聚     | 70 Mbps    |                  |
| smart-sim-ar3              | sim-ar3           | **汇聚层** AR6280-S 无线区汇聚       | 50 Mbps    |                  |
| smart-sim-sw1              | sim-sw1           | **接入层** S5735-L24T4S-A 1号楼     | 18 Mbps    |                  |
| smart-sim-sw2              | sim-sw2           | **接入层** S5735-L24T4S-A 数据中心   | 15 Mbps    |                  |
| smart-sim-sw3              | sim-sw3           | **接入层** S5735-L24T4S-A 3号楼     | 8 Mbps     |                  |
| smart-sim-pc1              | sim-pc1           | **终端层** Dell Win11 工作站        | 1000 Mbps  | 峰值突发流量     |
| smart-sim-server1          | sim-server1       | **终端层** Dell R750 Web 服务器     | 30 Mbps    |                  |
| smart-sim-ap1              | sim-ap1           | **终端层** AirEngine 6760 Wi-Fi 6   | 45 Mbps    |                  |
| smart-monitor-snmp         | snmp-simulator    | 兼容旧版 AR2220 模拟器 (id=1)       | 10 Mbps    | 保留             |

> 每台模拟器通过环境变量差异化配置设备名称、sysDescr、基线带宽、抖动比、上行下行偏置、接口数量、异常突发概率，答辩演示时每台设备的 Dashboard 曲线都会呈现差异化趋势，而不是完全相同的曲线。

#### 6.2.2 数据库中 12 台设备的注册清单（device 表 id 对应关系）

| id   | ip              | type                                    | remark                                        | 拓扑层级 |
|:---- |:--------------- |:--------------------------------------- |:--------------------------------------------- |:-------- |
| 1    | snmp-simulator  | 华为 AR2220 路由器                      | AR1-Huawei-Core 演示设备（兼容旧模拟器）       | 核心层   |
| 2    | sim-core-sw     | 华为 S12700E 核心交换机                 | 核心交换-S5700-Core                            | 核心层   |
| 3    | sim-fw-gateway  | 华为 USG6655E 下一代防火墙             | Internet 出口防火墙-USG6000E-FW                | 核心层   |
| 4    | sim-ar1         | 华为 AR6280-S 汇聚路由器                | AR1-Huawei 办公区1汇聚                         | 汇聚层   |
| 5    | sim-ar2         | 华为 AR6280-S 汇聚路由器                | AR2-Huawei 数据中心汇聚                        | 汇聚层   |
| 6    | sim-ar3         | 华为 AR6280-S 汇聚路由器                | AR3-Huawei 无线区汇聚                          | 汇聚层   |
| 7    | sim-sw1         | 华为 S5735-L24T4S-A 接入交换机         | S3700-Access-1 号楼1F配线间                    | 接入层   |
| 8    | sim-sw2         | 华为 S5735-L24T4S-A 接入交换机         | S3700-Access-2 数据中心列头柜                   | 接入层   |
| 9    | sim-sw3         | 华为 S5735-L24T4S-A 接入交换机         | S3700-Access-3 号楼3F配线间                    | 接入层   |
| 10   | sim-pc1         | Dell Workstation Win11 Net-SNMP         | PC-Workstation-1 办公工位                      | 终端层   |
| 11   | sim-server1     | Dell R750 Ubuntu22.04 Net-SNMP         | Server-Web-01 业务服务器                       | 终端层   |
| 12   | sim-ap1         | 华为 AirEngine 6760-X1 Wi-Fi 6 AP      | AP-Wireless-01 天花板AP                        | 终端层   |

#### 6.2.3 答辩演示拓扑层级与链路对应图

```
                    ┌───────────────────────┐
                    │  sim-core-sw (id=2)   │── 核心交换机 (150 Mbps)
                    │  华为 S12700E         │
                    └────┬──────┬──────┬────┘
                         │      │      │
              ┌──────────┘      │      └──────────────────┐
              ▼                 ▼                          ▼
   sim-fw-gateway (id=3)   3x 汇聚层                     2x 兼容层
   USG6655E 出口防火墙     ┌──────────────┐              snmp-simulator(id=1)
                          │ sim-ar1(id=4) │               (兼容旧环境)
                          │ sim-ar2(id=5) │
                          │ sim-ar3(id=6) │
                          └──┬──┬──┬───┬───┘          ... (实际在前端拓扑页以
                             │  │  │   │                    ECharts graph 渲染,
                    ┌────────┘  │  │   └──────────┐       节点颜色区分在线/离线)
                    ▼           ▼  ▼               ▼
               sim-sw1(id=7) sim-sw2 sim-sw3    sim-ap1(id=12)
               接入层 S5735       (id=8,9)      Wi-Fi 6 无线
                    │
              ┌─────┴────────────┐
              ▼                  ▼
        sim-pc1(id=10)    sim-server1(id=11)
        PC 工作站           Web 服务器
```

> 前端"网络拓扑"页通过 `/api/topology/graph` 接口（[TopologyController.java](src/main/java/com/smartmonitor/controller/TopologyController.java)）生成节点和边的 JSON，再由 ECharts graph 渲染。节点颜色按所属层级着色（核心蓝 / 汇聚绿 / 接入橙 / 终端灰蓝，离线节点灰底红圈），支持力导向拖拽布局与树形布局切换，并可通过左下角图例按层级 / 离线状态筛选。

#### 6.2.4 SNMP 模拟器关键参数（agent.py 环境变量）

所有变量均在 [docker-compose.yml](docker-compose.yml) 中按设备差异化设置，模拟器代码位于 [docker/snmp-simulator/agent.py](docker/snmp-simulator/agent.py)。

| 变量名                     | 作用                                           | 示例值      |
|:-------------------------- |:---------------------------------------------- |:----------- |
| `SNMP_SYSNAME`             | sysName.0 (snmpget 返回的设备名)               | S5700-Core   |
| `SNMP_SYSDESCR`            | sysDescr.0 (设备描述/型号)                     | Huawei S12700E |
| `DEVICE_TYPE`              | 设备类型标签, 对应 device.type 字段            | 核心交换机    |
| `SNMP_LOCATION`            | sysLocation.0                                  | 核心机房      |
| `BASE_BPS`                 | 基线速率 (bps), 每个采样点在此基础抖动         | 150000000    |
| `JITTER_RATIO`             | 抖动比 (0~1)                                   | 0.25         |
| `IN_OUT_BIAS`              | 出/入 偏置 (0.5=对称, <0.5=入>出, >0.5=出>入) | 0.6          |
| `IF_COUNT`                 | 接口数量 (ifIndex 1..N, 每个接口独立计数器)   | 4            |
| `BURST_FACTOR`             | 正常突发倍数上限                               | 2.0          |
| `BURST_ANOMALY_PROB`       | 异常突发概率 (0~1) — 供异常检测算法演示        | 0.15         |
| `BURST_ANOMALY_MIN_BPS`    | 触发异常突发的最小速率                         | 80000000     |
| `COUNTER32_MAX`            | Counter32 最大值, 控制回绕频率 (测试专用)      | 4294967295   |

> **Counter32 回绕处理**：后端 [MonitorScheduler.java](src/main/java/com/smartmonitor/scheduler/MonitorScheduler.java) 的 `calcBps()` 已处理 `cur < last` 情况，按 `cur + COUNTER32_MAX - last` 计算差值。

#### 6.2.5 一键重置演示环境

```bash
# 1. 清空 traffic_data 历史并重置设备注册表
Get-Content docker\init.sql -Raw | docker exec -i smart-monitor-mysql mysql -uroot -p123456 smart_monitor

# 2. 重启后端让 MonitorScheduler 重新建立状态缓存
docker compose restart backend

# 3. 30 秒后验证 12/12 在线
Invoke-RestMethod http://127.0.0.1:8081/api/traffic/overview
# 期望: { totalDevices:12, onlineCount:12, anomalyCount:0, totalInBps/OutBps:非零 }
```

`init.sql` 内容见 [docker/init.sql](docker/init.sql)，每次执行会先 DROP 再重建 device / traffic_data 两张表，并批量 INSERT 12 台设备。

### 6.3 eNSP 真实设备接入（真实模式主路径）

真实模式下系统本身不含任何设备，需要在 eNSP（华为 VRP 仿真）中搭建拓扑并开启 SNMP，再由「网络发现」扫描入库。步骤如下：

**① eNSP 设备开启 SNMP（以 AR 路由器为例，交换机 S5700 命令相同）**

```text
system-view
interface GigabitEthernet 0/0/0
 ip address 192.168.56.10 255.255.255.0        # 与 Cloud 网卡同网段
 quit
snmp-agent
snmp-agent community read public              # 只读团体字，需与 .env 的 SNMP_COMMUNITY 一致
snmp-agent sys-info version v2c
```

**② 用 Cloud 把设备桥接到宿主机网卡**

- 拖入一台「Cloud」设备，双击打开配置；端口绑定选择宿主机网卡（初学者推荐 `VirtualBox Host-Only Network`，默认网段 `192.168.56.0/24`），新增一条 UDP 端口映射；
- 用网线把设备的 GE0/0/0 接到 Cloud 对应端口；
- 在宿主机上确认能 ping 通设备 IP（如 `ping 192.168.56.10`）。

**③ 告知后端扫描网段并重启**

编辑项目根目录 `.env`：

```text
SNMP_DISCOVERY_SEGMENT=192.168.56.0/24
SNMP_COMMUNITY=public
```

```bash
docker compose up -d        # 让新环境变量生效（真实模式，4 个核心容器）
```

后端容器经 Docker Desktop 的 NAT 访问宿主机网卡网段。若发现页扫描不到设备，按以下顺序排查：宿主机能否 ping 通设备 → 设备 `display snmp-agent community` 是否有 public → Cloud 是否绑定了正确网卡 → 尝试把 Cloud 改绑物理网卡（设备配成物理局域网 IP）。

**④ 网络发现并加入管理**

前端登录后进入「网络发现」页（已自动带出配置网段与团体字）→ 开始扫描 → 勾选发现的设备 → 加入管理。设备写入 `device` 表后，`MonitorScheduler` 每 10 秒真实采集一次 ifInOctets/ifOutOctets 并落库、推送 WebSocket。

**⑤ 积累真实流量后训练 AI 模型**

AI 检测（Transformer + 孤立森林）**只接受真实历史数据训练**：

- 快照特征（孤立森林）至少需要 **10 条**有效 `traffic_data`；
- 时序序列（Transformer）需要 12 台设备 × 30 个时间步（约 5 分钟）以上数据，设备/时长不足时训练会明确返回失败原因；
- 在「AI 模型」页手动点训练，或等真实数据积累足够后调用 `POST /api/ai/train`。模型未训练期间系统照常采集和展示真实流量，只是不产生 AI 告警。

> 多台真实设备同理：逐台配置同一网段 IP 与相同团体字，一次网络发现即可全部纳入。真实物理交换机/路由器（华为/思科/H3C）接入方式与上完全一致，只需保证后端容器到设备管理 IP 的三层可达。

---

## 七、SNMP OID 参考

| OID | 描述 | 用途 |
| :--- | :--- | :--- |
| `1.3.6.1.2.1.1.5.0` | sysName | 设备名称 |
| `1.3.6.1.2.1.1.3.0` | sysUpTime | 设备运行时间 |
| `1.3.6.1.2.1.2.2.1.10.1` | ifInOctets.1 | 接口 1 入字节累计 |
| `1.3.6.1.2.1.2.2.1.16.1` | ifOutOctets.1 | 接口 1 出字节累计 |
| `1.3.6.1.2.1.1.1.0` | sysDescr | 设备描述（备用） |
| `1.3.6.1.2.1.1.6.0` | sysLocation | 设备位置（备用） |

---

## 八、规划中的核心模块（开题报告）

当前代码已实现 SNMP 采集、MySQL 持久化、WebSocket 推送、拓扑可视化等基础设施。以下为开题报告规划但尚未实现的核心模块，是后续开发重点。

### 8.1 混合采集架构

构建基于 SNMP4J 的状态采集器（已实现）与基于 Pcap4j 的旁路抓包引擎（待实现），实现全链路数据获取，确定流量数据采集方案。

```
┌─────────────────────────────────────────────────────┐
│                  数据采集层                          │
│                                                     │
│  ┌─────────────────┐      ┌──────────────────────┐ │
│  │ SNMP4J 采集器    │      │ Pcap4j 旁路抓包引擎  │ │
│  │ (宏观统计)       │      │ (微观抓包)           │ │
│  │                 │      │                      │ │
│  │ • sysName       │      │ • TCP/IP 协议解析    │ │
│  │ • sysUpTime     │      │ • 包特征提取         │ │
│  │ • ifInOctets    │      │ • 攻击模式匹配       │ │
│  │ • ifOutOctets   │      │                      │ │
│  │                 │      │                      │ │
│  │ 周期: 10s/60s   │      │ 实时(毫秒级)         │ │
│  └────────┬────────┘      └──────────┬───────────┘ │
│           │                          │             │
│           ▼                          ▼             │
│      ┌──────────────────────────────────────┐     │
│      │     数据时空对齐模块（待实现）         │     │
│      │  对齐 SNMP 分钟级与 Pcap 毫秒级数据    │     │
│      └──────────────────────────────────────┘     │
└─────────────────────────────────────────────────────┘
```

### 8.2 动态基线异常检测

采集接口流量数据，研发基于时间序列的滑动窗口算法，根据历史流量特征动态生成安全基线，自动发现流量突发异常，并将流量速率数据存入 MySQL 数据库。

**算法思路（μ±2σ 滑动窗口）**：

1. 维护最近 N 次（如 30 次）的流量速率样本
2. 计算窗口内样本均值 μ 与标准差 σ
3. 若当前流量超出 `[μ - 2σ, μ + 2σ]` 区间，标记 `is_anomaly = 1`
4. 窗口随新数据滚动，基线动态更新
5. 引入统计学偏差分析，降低因正常业务突发导致的误报

> 数据库 `traffic_data.is_anomaly` 字段已预留，待算法实现后由 `MonitorScheduler.collectTraffic()` 自动标记。

### 8.3 攻击特征识别引擎

利用 Pcap4j 库进行旁路抓包，解析 TCP/IP 协议栈，设计多模式匹配规则库，精准识别以下攻击行为：

| 攻击类型 | 检测特征 | 判定规则 |
| :--- | :--- | :--- |
| **SYN Flood** | 大量 SYN 标志的 TCP 包，源 IP 伪造 | 单位时间内 SYN 包数 > 阈值且无对应 ACK |
| **UDP Flood** | 大量 UDP 包涌入目标端口 | 单位时间内 UDP 包数 > 阈值 |
| **端口扫描** | 连续探测多个端口 | 同一源 IP 在短时间内连接多个不同端口 |

**规则引擎设计**：

- 基于正则匹配的规则配置文件
- 支持自定义匹配模式与阈值
- 抓包缓冲区管理，防止高吞吐下丢包
- 检测延迟目标 < 500ms

### 8.4 数据时空对齐（拟解决的关键问题）

解决 SNMP 轮询数据（分钟级）与 Pcap 抓包数据（毫秒级）在异常关联分析时的时间同步难题：

- 统一时间基准（`LocalDateTime.now()`）
- SNMP 采集结果按采集时间存储
- Pcap 抓包事件按包时间戳存储
- 异常关联时采用时间窗口匹配（如 ±30 秒内的 SNMP 与 Pcap 数据关联）

### 8.5 海量数据高效存储

实现历史流量数据的高效存储与快速查询，支持至少 30 天历史数据查询：

- `traffic_data` 表已建立 `(device_id, collect_time)` 复合索引
- 当前查询最近 30 条（`findTop30ByDeviceIdOrderByCollectTimeAsc`）
- 规划：增加按时间范围查询接口、定期归档冷数据、分区表优化

### 8.6 误报率控制

通过引入统计学偏差分析，解决因正常业务突发导致的异常检测误报问题：

- 结合 μ±2σ 与业务时段特征（如工作高峰期）
- 多次确认机制（连续 N 次超出基线才告警）
- 目标误报率 < 10%

---

## 九、性能指标（任务书要求）

| 指标 | 要求 | 当前状态 |
| :--- | :--- | :--- |
| 系统架构 | B/S 架构，浏览器访问 | ✅ 已满足（前端 80/8080，后端 8081） |
| 监控设备数 | ≥ 10 台 | ✅ **已实现 12 台**（2 核心 + 3 汇聚 + 3 接入 + 3 终端 + 1 兼容） |
| 数据采集间隔 | 可调（默认 30s~60s） | ⚠️ 当前流量采集 10s，状态采集 5min，可通过 `@Scheduled(fixedRate)` 调整 |
| 前端刷新及时性 | 实时刷新 | ✅ WebSocket `/ws/metrics` 每轮推送 + HTTP 轮询兜底 |
| 拓扑图状态区分 | 节点拖拽 + 颜色区分（正常/告警/离线） | ✅ 已实现：按层级着色 + 离线灰底红圈，支持层级/离线图例筛选与树形/力导向双布局 |
| 攻击识别类型 | ≥ 3 种（SYN Flood/端口扫描等） | ❌ 待实现 Pcap4j 模块 |
| 误报率 | < 10% | ❌ 待实现 μ±2σ 滑动窗口异常检测算法 |
| SNMP 采集延迟 | < 3 秒 | ✅ 超时配置 2000ms，12 台设备一轮采集 < 400ms |
| Pcap4j 抓包延迟 | < 500ms | ❌ 待实现 |
| 论文字数 | ≥ 1.2 万字 | ⏳ 待撰写 |
| 测试环境 | eNSP 模拟环境 | ✅ **Docker 答辩演示版就绪**（12 台 SNMP 模拟器，文档 6.2 节）；eNSP 作为备选兼容 |

---

## 十、测试方案

### 10.1 研究方法

| 方法 | 说明 |
| :--- | :--- |
| **对比分析法** | 对比不同抓包库的性能差异，查阅 SNMP 协议 RFC 文档及相关学术论文，选定最优技术栈 |
| **仿真实验法** | 利用 eNSP 搭建仿真拓扑，使用 Kali Linux 模拟攻击流量，验证算法有效性 |
| **原型开发法** | 采用 Spring Boot 架构进行模块化开发，先实现单设备数据采集原型，再扩展到多设备统一管理平台 |

### 10.2 eNSP 仿真环境

> **状态**：eNSP 环境完全就绪（VBox 5.2.44 + Npcap 1.88 + 5 个设备镜像已注册），可立即联调 AR1。

利用 eNSP 搭建模拟网络环境，验证系统功能正确性和稳定性。本机已具备以下资源：

| 资源 | 路径 | 说明 |
| :--- | :--- | :--- |
| eNSP 主程序 | `d:\毕设\eNSP\eNSP_Client.exe` | V100R003，2019 版 |
| VirtualBox | `C:\Program Files\Oracle\VirtualBox\VirtualBox.exe` | ✅ 5.2.44r139111（2020-07-09），eNSP 必需依赖 |
| Npcap | `C:\Program Files\Npcap` | ✅ 1.88（Nmap Project），WinPcap API 兼容模式，wpcap.dll 1.10.6 |
| **SNMP-Monitor 拓扑** | `d:\毕设\eNSP\examples\SNMP-Monitor\snmp-monitor.topo` | 为本项目准备的测试拓扑 |
| Campus_Network 拓扑 | `d:\毕设\eNSP\examples\Campus_Network\Campus_Network.topo` | 完整企业网（可选参考） |
| eNSP 设备镜像 | `d:\毕设\eNSP\vboxserver\*.vdi` | ✅ 5 个 VM 已注册（AR_Base / WLAN_AC/AD/AP/SAP_Base） |

#### 10.2.1 SNMP-Monitor 拓扑结构（本项目主测试环境）

```
   ┌─────────────────┐         ┌─────────────────┐
   │   AR1 (AR1220)  │ Copper  │   CLOUD1        │
   │   被监控路由器   │◄──────►│   网络桥接器     │
   │                 │         │ (UDP端口绑定到   │
   │ GE0/0/0:        │         │  主机网卡)       │
   │ 192.168.56.100  │         │                 │
   └─────────────────┘         └────────┬────────┘
                                        │
                                        ▼
                          ┌─────────────────────────┐
                          │  主机 (Spring Boot 8081) │
                          │  通过 SNMP v2c 采集 AR1   │
                          └─────────────────────────┘
```

- **AR1**：华为 AR1220 路由器，2 个 GE + 8 个 Ethernet + 2 个 Serial 接口，作为被监控设备
- **CLOUD1**：eNSP Cloud，通过 UDP 端口绑定将虚拟网络桥接到主机物理网络，使后端可直接通过 IP 访问 AR1
- 设计意图：无需真实硬件即可完成 SNMP 联调验证

#### 10.2.2 eNSP 联调步骤

1. ~~安装 VirtualBox 5.2.44~~ ✅ 已完成（5.2.44r139111）
2. ~~安装 Npcap 1.88~~ ✅ 已完成（1.88，WinPcap 兼容模式）
3. 启动 eNSP，加载 `examples/SNMP-Monitor/snmp-monitor.topo`
4. 启动 AR1，进入命令行配置 SNMP 服务：

```text
system-view
sysname AR1
interface GigabitEthernet 0/0/0
ip address 192.168.56.100 255.255.255.0
quit
snmp-agent
snmp-agent community read public
snmp-agent sys-info version v2c
snmp-agent target-host trap address udp-domain 192.168.56.1 params securityname public
```

5. 在 CLOUD1 上绑定主机网卡（UDP 端口绑定），让 AR1 与主机网络互通
6. 在 MySQL `device` 表插入 AR1 记录（或通过 `/api/device` POST 接口添加）
7. 启动 Spring Boot 后端，访问 `http://localhost:8081/api/device` 验证 SNMP 采集

#### 10.2.3 完整企业网拓扑（可选参考）

`Campus_Network.topo` 提供更完整的中小型企业网结构（4 台 AR1220 路由器 + 8+ 台 S5700 交换机），可用于后续多设备监控扩展测试。本机原始模拟拓扑对应文档第一节"项目概述"中描述的层级：

- **核心层**：S5700 三层交换机
- **汇聚层**：3 台 AR 系列路由器
- **接入层**：3 台 S3700 二层交换机
- **终端层**：PC、服务器、无线 AP、网络打印机

### 10.3 Kali Linux 攻击模拟

使用 Kali Linux 模拟攻击流量，验证攻击识别算法有效性：

| 攻击类型 | 模拟工具 | 验证目标 |
| :--- | :--- | :--- |
| SYN Flood | `hping3 -S` | SYN Flood 检测规则 |
| UDP Flood | `hping3 --udp` | UDP Flood 检测规则 |
| 端口扫描 | `nmap` | 端口扫描检测规则 |

### 10.4 进度计划（16 周）

| 阶段 | 周次 | 任务 |
| :--- | :--- | :--- |
| 一 | 第 1-2 周 | 查阅 SNMP 协议文献及 RFC 文档，学习 Java Web 开发技术，完成开题报告与外文翻译 |
| 二 | 第 3-5 周 | 搭建 eNSP 模拟网络环境，配置设备 SNMP 服务；完成数据库表结构设计；编写 SNMP 数据采集代码 |
| 三 | 第 6-9 周 | 实现多设备定时轮询调度与数据入库；开发 Pcap4j 抓包服务与滑动窗口异常检测算法；开发 Spring Boot 后端接口 |
| 四 | 第 10-12 周 | 开发 Web 前端页面，集成 ECharts 图表；实现攻击规则引擎；进行系统联调与功能测试 |
| 五 | 第 13-16 周 | 整理系统测试数据，撰写毕业论文，定稿打印装订，制作答辩 PPT 并准备答辩 |

---

## 十一、参考文献

[1] 谢希仁. 计算机网络（第 8 版）[M]. 北京: 电子工业出版社, 2021.

[2] 张伟, 李强. 基于 SNMP 的网络流量监控系统设计与实现[J]. 计算机工程与应用, 2020, 56(10): 112-117.

[3] Case J, Fedor M, Schoffstall M, et al. RFC 1157: Simple Network Management Protocol (SNMP)[S]. IETF, 1990.

[4] 王海涛. 基于 SNMP 的园区网络性能监测系统研究[D]. 西安电子科技大学, 2019.

[5] Harrington D, et al. RFC 3411: An Architecture for SNMP Management Frameworks[S]. IETF, 2002.

[6] 华为技术有限公司. eNSP 用户手册[Z]. 2020.

[7] 刘洋. 基于 Java 的网络设备监控系统设计与实现[J]. 软件导刊, 2021, 20(3): 45-49.

[8] 李伟. 基于 SNMP 的网络流量监测系统的研究与实现[J]. 计算机技术与发展, 2019, 29(2): 188-192.

[9] Stallings W. SNMP, SNMPv2, SNMPv3, and RMON 1 and 2[M]. Addison-Wesley, 1999.

[10] 陈鸣. 网络工程设计教程[M]. 北京: 机械工业出版社, 2018.

---

**文档版本**：3.0  
**项目名称**：基于 SNMP 与 Pcap4j 的区域网络流量监控与恶意流量检测系统  
**更新说明**：v3.0 根据《卢梁敏开题报告》与《卢梁敏任务书》补充项目基本信息、研究背景与意义、规划中的核心模块（混合采集架构、动态基线异常检测、攻击特征识别引擎、数据时空对齐、海量数据存储、误报率控制）、性能指标、测试方案（eNSP + Kali Linux）、进度计划、参考文献等内容，使文档与开题报告、任务书要求对齐。
