# lucky 项目长期记忆

## 项目概况
- `C:\Users\28182\Desktop\java\lucky` = 毕业典礼实时互动抽奖系统（2000 人规模）。
- 后端 `lucky-server`（Spring Boot 3.2.5 / Java 17 / MyBatis-Plus / Security+JWT / Redis / RabbitMQ / Redisson / Sentinel / WebSocket / Swagger 2.5.0）。
- 前端三端：`lucky-screen`（大屏，:5173）、`lucky-mobile`（手机端，:5174）、`lucky-admin`（管理后台，:5175）。
- 核心链路（v2，2026-11-04 起）：扫码登录（Redis 临时令牌）→ 进直播间（WebSocket 场次分组）→ 发弹幕（MQ 削峰 + Lua 限流 + 敏感词）→ **管理员开轮（预热库存）→ 用户点抢奖（Lua 原子扣减 + MQ 异步落库）** → WebSocket 实时广播。

## v2 业务模型（2026-11-04 重构）
- 抽奖从「管理员随机抽人」改为「用户限时抢奖」，即秒杀模型。
- `lottery_round` 必须绑 `prize_id`（`/api/lottery/round` 传 prizeId），状态 0待开始/1抢奖中/2已结束。
- 接口：`POST /round/{id}/start`、`POST /round/{id}/end`、`POST /join/{roundId}`（需扫码令牌）、`GET /result`、`GET /remaining`。旧的 `/draw/{roundId}` 已删除。
- `participant.status` 已改名 `lottery_status`（只管资格 1/3），中奖看 `winner` 表。
- 秒杀核心在 `SeckillServiceImpl` + `lua/seckill.lua`；MQ 队列 `lucky.lottery.join.queue`。
- 一人一活动只能中一次：Redis Set 去重 + DB 唯一索引 `uk_activity_participant` 双保险。

## 运行约定（2026-10-09 确立）
- 统一用 `docker compose up -d --build` 全量编排启动；后端 `profile=prod`。
- **宿主机端口映射不是默认端口**（本机 3306/5672/15672 被 Windows 服务占用且无管理员权限停止）：
  - mysql `3307:3306`、rabbitmq `5773:5672` + `15673:15672`、redis `6379:6379`、backend `8080`、frontend `5173-5175`。
  - 容器内端口不变，服务间用 compose 网络名（`mysql` / `redis` / `rabbitmq` / `backend`）互访，后端配置无需改。
- 默认管理员：`admin` / `admin123`（首个 `AdminUserInitializer` 自动创建）。
- MySQL 数据在 `mysql-data` 卷；重置数据用 `docker compose down -v`。
- `docker-compose.prod.yml` 与 `docker-compose.yml` 端口/卷保持一致。

## 代码约定 / 已知坑
- **异步落库**：弹幕（`DanmakuBatchPersister` 每 100ms 刷）与中奖记录都是异步写入，接口断言前需 sleep 2~3 秒。
- **`@RateLimit` 弹幕 1 条/秒/participantId**：测试连续请求同一参与者会被 429 打断。
- **扫码登录限流**：单 IP 200 次/60 秒（校园网 NAT 场景），单学号 5 次/60 秒。测试批量造用户时学号要唯一。
- **Redis 有持久卷**：跨轮次测试会读到旧缓存（活动缓存、中奖集合、敏感词集合），需 `FLUSHALL` 或换新 activityId。
- **敏感词初始化**：MySQL 容器初始化时客户端字符集必须是 utf8mb4（`schema.sql` 首行 `SET NAMES utf8mb4;` + `Dockerfile.mysql` 的 `ENV LANG=C.UTF-8`），否则中文敏感词入库乱码、过滤失效。
- **OSS 背景上传**：`.env` 里的 `bucket-name: ttbyx` 不存在，上传报 `NoSuchBucket`，功能不可用。
- **Maven 3.9 本地仓库坑**：`~/.m2/settings.xml` 的 mirror id 必须与本地仓库 `_remote.repositories` 里的 id 一致（本项目已改成 `central`，备份在 `settings.xml.bak`），否则报 "present, but unavailable" 并因 TLS 失败编译不了。
- **容器内 Maven**：`docker/maven-settings.xml` 走阿里云，`Dockerfile.backend` 已引用。

## 测试资产
- `bench/smoke_test.py`：端到端冒烟 18 项（抢奖链路 + WS 鉴权 + JWT 黑名单 + Prometheus 指标）。
- `bench/bench_join.py`：抢奖压测（连接复用、分批发请求），用法 `-n 用户数 -q 名额 -c 线程数`。
- `bench/压测报告.md`：压测数据、调优动作、局限说明。
- `缺陷.md`：已修复清单 F-01~F-12 + 待修复 D-01~D-12。

## 架构要点（v2）
- **秒杀链路**：Redis 元信息 → 资格校验 → `lua/seckill.lua` 原子扣减 → MQ → 异步落库。
  三道防线：Redis 预扣 / DB `WHERE stock>0` / `winner` 唯一索引。
- **WS 集群广播**：`WsClusterPublisher` publish 到 `lucky:ws:broadcast`，`WsClusterSubscriber` 收到后只推本地连接。
  Handler 的公开方法是「集群范围」，`localXxx` 才是本机推送。
- **WS 鉴权**：`WsAuthInterceptor` 要求带 participantId 时必须有有效扫码令牌；大屏匿名可连。
- **JWT 黑名单**：登出后 token 立即失效，key 是 token 的 MD5，TTL 跟随剩余有效期。
- **监控**：`/actuator/prometheus`，自定义 `lucky_seckill_join_total`、`lucky_ws_local_connections`。

## 常用命令
```bash
# 单测（17 个）
mvn -f lucky-server/pom.xml test
# 冒烟
python bench/smoke_test.py
# 压测
python bench/bench_join.py -n 500 -q 500 -c 50
```

## 用户偏好（项目相关）
- 改完文件不要替他执行 git 提交，**告诉他命令让他自己练手**。
- 回答要详细：给结论也要给依据、对比、被排除项与成立前提。
