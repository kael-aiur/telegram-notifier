## Why

当前系统的 Telegram starter 只有内存模拟实现，`Start`、验证码提交和消息扫描不会真正连接 Telegram，也无法监听真实账号消息。要让通知规则、代理链和推送渠道进入可用状态，需要实现真实 Telegram 客户端对接并保留现有控制台 API 边界。

## What Changes

- 新增真实 Telegram 客户端运行时，用于连接个人 Telegram 账号、完成登录授权、维护会话并接收消息事件。
- 支持配置 Telegram `api_id`、`api_hash` 和客户端会话数据目录。
- 将现有账号启动、停止、手机号、验证码、二步验证密码提交接口接入真实授权状态机。
- 将账号绑定的代理链传递给 Telegram 客户端，并在启动和代理更新时应用连接配置。
- 在账号达到可监听状态后发布真实 `TelegramMessageEvent`，供现有规则评估、统计和推送流程消费。
- 保留内存实现作为测试或无真实 Telegram 配置时的可替代实现。
- 不持久化 Telegram 消息正文，继续只允许消息正文在内存事件、规则匹配和显式模板渲染中短暂存在。

## Capabilities

### New Capabilities

- `telegram-client-runtime`: 定义真实 Telegram 客户端运行时的配置、启动停止、授权流程、会话持久化、代理应用和消息事件发布行为。

### Modified Capabilities

- 无。当前 `openspec/specs/` 中没有已存在的主规格，本变更先引入新的能力规格。

## Impact

- 影响 `telegram-spring-boot-starter`：需要新增真实客户端实现、配置属性、授权状态映射、会话存储和消息订阅桥接。
- 影响 `telegram-notifier-control-server`：需要暴露或消费 Telegram client 配置，确保账号状态、错误信息和代理更新与真实运行时同步。
- 影响 `telegram-notifier-control-web`：可能需要补充真实登录状态提示和 Telegram API 配置入口。
- 新增外部依赖或运行时组件：需要选择并集成 Telegram client 库，例如 TDLib Java 绑定或等价实现。
- 运行部署需要管理员提供 Telegram `api_id` / `api_hash`，并为会话文件和 TDLib 数据准备可写目录。
