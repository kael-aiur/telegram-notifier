## Why

当前 Python 子进程运行时已经具备 Java 侧进程管理、依赖检查、worker 资源内置和 JSON Lines 协议骨架，但 bundled worker 仍是状态模拟，无法真正登录 Telegram、应用代理或接收消息。为了让服务进入真实 Telegram 功能测试阶段，需要将占位 worker 替换为可连接 Telegram 的 Python 实现，并保持上层 Java 接口不变。

## What Changes

- 将 `telegram-python-subprocess-runtime` 内置的 Python worker 从占位状态机升级为真实 Telegram client worker。
- 使用 Python Telegram 客户端库完成个人账号登录、session 持久化、手机号、验证码和二步验证密码流程。
- 在账号启动时读取 Java 下发的 `apiId`、`apiHash`、账号数据目录和代理链，并将可用代理应用到 Telegram 连接。
- 将 Telegram 授权状态、连接错误和活动代理通过 JSON Lines 状态事件回传给 Java。
- 监听真实 Telegram 新消息，将文本和来源元数据转换为 JSON Lines message 事件，交给现有规则、统计和推送流程处理。
- 保持一个 jar 部署模式：Python worker 源码继续随 jar 内置并在运行时抽取到 data dir；系统只需要预装 Python 和声明的 Python 依赖。
- 补充 worker 协议、依赖诊断、授权状态、代理映射和消息桥接测试，支撑真实 Telegram 功能测试。

## Capabilities

### New Capabilities

- `python-telegram-worker-runtime`: 定义 Python subprocess worker 真实连接 Telegram 的配置、授权、session、代理、消息监听、错误诊断和 Java 协议行为。

### Modified Capabilities

- 无。当前主规格目录中没有已归档的对应能力；本变更在现有运行时骨架之上新增 Python worker 的真实 Telegram 行为规格。

## Impact

- 影响 `telegram-python-subprocess-runtime`：Python worker 实现、worker 资源、依赖检查、协议事件处理和模块文档。
- 影响 `telegram-spring-boot-starter`：通常不改变公共接口；如发现状态或错误表达不足，应以兼容方式扩展。
- 影响 `telegram-notifier-control-server`：复用现有账号、代理、规则和状态同步逻辑，必要时补充真实运行时配置说明和测试配置。
- 影响部署环境：运行真实模式时需要可用的 `python3`、Telegram Python 客户端依赖、`api_id`、`api_hash`、可写 data dir，以及可选代理服务。
- 风险边界：不得把 Telegram 消息正文写入数据库、统计记录、持久日志或诊断错误；代理密码也不得出现在日志和错误消息中。
