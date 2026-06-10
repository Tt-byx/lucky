# 🎓 毕业典礼抽奖程序

> 支持 2000 人规模的在线实时互动抽奖系统

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green)](https://spring.io/)
[![Vue 3](https://img.shields.io/badge/Vue-3.4-brightgreen)](https://vuejs.org/)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://www.java.com/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7.0-red)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Ready-blue)](https://www.docker.com/)

---

## 📖 项目简介

这是一个为大学毕业典礼设计的实时互动抽奖系统，支持：

- 🎯 **实时抽奖**：大屏幕滚动动画，现场氛围感拉满
- 💬 **弹幕互动**：2000人同时发送弹幕，实时显示
- 📱 **扫码参与**：手机扫码即可注册参与
- 🔐 **安全认证**：管理后台 JWT 认证，权限控制
- 🚀 **高性能**：Redis 缓存 + WebSocket 实时通信

---

## ✨ 功能特性

### 🎪 大屏展示端 (lucky-screen)
- 二维码展示，扫码参与
- 实时弹幕动画（支持自定义区域、速度、字号）
- 抽奖滚动动画 + 中奖结果展示
- 在线人数统计
- 自定义背景（图片/视频）

### 📱 手机端 (lucky-mobile)
- 学号快速登录
- 实时弹幕发送
- 快捷消息（666、恭喜恭喜等）
- 响应式设计，适配各种手机

### 💼 管理后台 (lucky-admin)
- 活动创建与管理
- 抽奖轮次管理（一等奖、二等奖...）
- 参与者管理（禁言、移出直播间、移除抽奖资格）
- 敏感词过滤
- 屏幕配置（背景、弹幕设置）
- 历史活动查看

---

## 🛠️ 技术栈

### 后端
| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.2.5 | 核心框架 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| Spring Security | - | 安全框架 |
| JWT | 0.12.5 | 认证方案 |
| WebSocket | - | 实时通信 |
| Redis | 7.0 | 缓存 & 分布式 |
| MySQL | 8.0 | 数据库 |
| Swagger | 2.5.0 | API 文档 |

### 前端
| 技术 | 版本 | 说明 |
|------|------|------|
| Vue 3 | 3.4 | 前端框架 |
| Vite | 5.0 | 构建工具 |
| Composition API | - | 组合式 API |

### 部署
| 技术 | 说明 |
|------|------|
| Docker | 容器化部署 |
| Docker Compose | 多容器编排 |
| Nginx | 反向代理 & 静态资源 |

---

## 📁 项目结构

```
lucky/
├── lucky-server/                 # 后端服务
│   ├── src/main/java/com/lucky/
│   │   ├── config/              # 配置类
│   │   ├── controller/          # 控制器
│   │   ├── dto/                 # 数据传输对象
│   │   ├── entity/              # 实体类
│   │   ├── exception/           # 异常处理
│   │   ├── mapper/              # MyBatis Mapper
│   │   ├── security/            # 安全认证
│   │   ├── service/             # 业务逻辑
│   │   ├── util/                # 工具类
│   │   └── websocket/           # WebSocket
│   └── src/main/resources/
│       ├── application.yml      # 开发环境配置
│       ├── application-prod.yml # 生产环境配置
│       └── schema.sql           # 数据库脚本
│
├── lucky-screen/                 # 大屏展示端
│   └── src/App.vue
│
├── lucky-mobile/                 # 手机端
│   └── src/App.vue
│
├── lucky-admin/                  # 管理后台
│   └── src/App.vue
│
├── docker/                       # Docker 配置
│   ├── Dockerfile.backend
│   ├── Dockerfile.frontend
│   ├── Dockerfile.mysql
│   └── nginx/nginx.conf
│
├── docker-compose.yml            # 本地开发
├── docker-compose.prod.yml       # 生产部署
│
└── doc/                          # 项目文档
```

---

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0+
- Redis 7.0+

### 1. 克隆项目

```bash
git clone https://github.com/Tt-byx/lukcy.git
cd lukcy
```

### 2. 初始化数据库

```bash
# 登录 MySQL
mysql -u root -p

# 创建数据库
CREATE DATABASE lucky DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

# 导入脚本（可选，应用启动时会自动初始化）
USE lucky;
SOURCE lucky-server/src/main/resources/schema.sql;
```

### 3. 启动后端

```bash
cd lucky-server

# 修改配置（可选）
# vim src/main/resources/application.yml

# 启动
mvn spring-boot:run
```

后端将在 http://localhost:8080 启动

### 4. 启动前端

```bash
# 终端1：大屏展示端
cd lucky-screen
npm install
npm run dev
# 访问 http://localhost:5173

# 终端2：手机端
cd lucky-mobile
npm install
npm run dev
# 访问 http://localhost:5174

# 终端3：管理后台
cd lucky-admin
npm install
npm run dev
# 访问 http://localhost:5175
```

### 5. 访问系统

| 服务 | 地址 | 说明 |
|------|------|------|
| 大屏展示 | http://localhost:5173 | 投影仪展示 |
| 手机端 | http://localhost:5174 | 扫码参与 |
| 管理后台 | http://localhost:5175 | 管理活动 |
| API 文档 | http://localhost:8080/swagger-ui.html | 接口文档 |

---

## 🐳 Docker 部署

### 本地开发

```bash
# 构建并启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

### 生产部署

```bash
# 1. 构建镜像
docker-compose build

# 2. 保存镜像
docker save -o lucky-images.tar lucky-mysql lucky-backend lucky-frontend

# 3. 上传到服务器
scp lucky-images.tar root@your-server:/root/

# 4. 在服务器上加载镜像
docker load -i lucky-images.tar

# 5. 启动服务
mv docker-compose.prod.yml docker-compose.yml
docker-compose up -d
```

### 端口说明

| 服务 | 端口 | 说明 |
|------|------|------|
| 后端 API | 8080 | Spring Boot |
| 大屏展示 | 5173 | Nginx |
| 手机端 | 5174 | Nginx |
| 管理后台 | 5175 | Nginx |
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存 |

---

## 🔐 默认账号

### 管理后台登录

- **用户名**：admin
- **密码**：admin123

> ⚠️ 生产环境请务必修改默认密码！

---

## 📡 API 接口

### 公开接口（无需认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/activity/current | 获取当前活动 |
| POST | /api/participant/register | 参与者注册 |
| GET | /api/participant/check | 检查是否已注册 |
| POST | /api/danmaku/send | 发送弹幕 |
| GET | /api/screen/config | 获取屏幕配置 |

### 管理接口（需要认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/login | 管理员登录 |
| POST | /api/activity | 创建活动 |
| PUT | /api/activity/{id}/status | 更新活动状态 |
| POST | /api/lottery/round | 创建抽奖轮次 |
| POST | /api/lottery/draw/{roundId} | 开始抽奖 |
| GET | /api/admin/user/list | 获取用户列表 |
| PUT | /api/admin/user/{id}/mute | 禁言用户 |
| PUT | /api/admin/user/{id}/ban | 移出直播间 |

完整 API 文档：http://localhost:8080/swagger-ui.html

---

## 🔧 配置说明

### 环境变量

```bash
# 数据库
DB_HOST=localhost
DB_PORT=3306
DB_NAME=lucky
DB_USERNAME=root
DB_PASSWORD=123456

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# JWT
JWT_SECRET=your-secret-key-here
JWT_EXPIRATION=86400000  # 24小时

# 前端访问地址
BASE_URL=http://your-server-ip
```

---

## 🎨 界面预览

### 大屏展示端
- 深色主题，沉浸式体验
- 粒子背景效果
- 玻璃拟态卡片
- 丰富的抽奖动画

### 手机端
- 简洁友好的界面
- 流畅的交互体验
- 响应式设计

### 管理后台
- 专业的仪表盘风格
- 清晰的信息层次
- 高效的操作流程

---

## 📝 开发文档

项目文档位于 `doc/` 目录：

- 项目搭建说明
- 功能开发记录
- Bug 修复记录
- 部署指南
- 优化记录

---

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## 👨‍💻 作者

- GitHub: [@Tt-byx](https://github.com/Tt-byx)

---

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Vue.js](https://vuejs.org/)
- [MyBatis-Plus](https://baomidou.com/)
- [Element Plus](https://element-plus.org/)

---

⭐ 如果这个项目对你有帮助，请给个 Star 支持一下！
