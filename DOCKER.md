# Docker 部署指南

## 快速开始

### 1. 使用 Docker Compose（推荐）

```bash
# 创建 .env 文件配置 Telegram API 凭证
cat > .env << 'EOF'
TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash
EOF

# 启动服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

### 2. 使用 Docker 命令

```bash
# 拉取镜像
docker pull kael2018/telegram-notifier:latest

# 运行容器
docker run -d \
  --name telegram-notifier \
  -p 21192:21192 \
  -v telegram-notifier-data:/telegram-notifier/data \
  -e TELEGRAM_API_ID=your_api_id \
  -e TELEGRAM_API_HASH=your_api_hash \
  kael2018/telegram-notifier:latest
```

### 3. 本地构建

```bash
# 构建镜像
docker build -t telegram-notifier .

# 运行
docker run -d \
  --name telegram-notifier \
  -p 21192:21192 \
  -v telegram-notifier-data:/telegram-notifier/data \
  -e TELEGRAM_API_ID=your_api_id \
  -e TELEGRAM_API_HASH=your_api_hash \
  telegram-notifier
```

## 环境变量

| 变量 | 必需 | 默认值 | 说明 |
|------|------|--------|------|
| `TELEGRAM_API_ID` | ✅ | - | Telegram API ID |
| `TELEGRAM_API_HASH` | ✅ | - | Telegram API Hash |
| `TELEGRAM_NOTIFIER_DATA_DIR` | ❌ | `/telegram-notifier/data` | 数据存储目录 |
| `TELEGRAM_PYTHON_EXECUTABLE` | ❌ | `python3` | Python 可执行文件路径 |
| `JAVA_OPTS` | ❌ | - | JVM 启动参数 |

## 数据持久化

容器内的 `/telegram-notifier/data` 目录包含：
- SQLite 数据库文件
- Telegram 会话数据
- 应用配置

**务必**挂载此目录以持久化数据：

```bash
# 使用命名卷（推荐）
-v telegram-notifier-data:/telegram-notifier/data

# 使用宿主机目录
-v /path/on/host:/telegram-notifier/data
```

## 端口

默认暴露 `21192` 端口。启动后访问：

```
http://localhost:21192
```

## 健康检查

容器内置健康检查，每 30 秒检测一次：

```bash
# 手动检查
curl http://localhost:21192/api/system/bootstrap-status

# 查看容器健康状态
docker inspect --format='{{.State.Health.Status}}' telegram-notifier
```

## 日志

```bash
# 查看实时日志
docker logs -f telegram-notifier

# 最近 100 行
docker logs --tail 100 telegram-notifier
```

## 升级

```bash
# 拉取最新镜像
docker pull kael2018/telegram-notifier:latest

# 重启容器（保留数据）
docker-compose down
docker-compose up -d
```

## 故障排除

### 容器无法启动

```bash
# 检查日志
docker logs telegram-notifier

# 常见原因：
# 1. TELEGRAM_API_ID 或 TELEGRAM_API_HASH 未设置
# 2. 端口 21192 被占用
# 3. 数据目录权限问题
```

### Python 子进程错误

```bash
# 进入容器调试
docker exec -it telegram-notifier bash

# 检查 Python 环境
python3 --version
pip3 list | grep pyrogram
```
