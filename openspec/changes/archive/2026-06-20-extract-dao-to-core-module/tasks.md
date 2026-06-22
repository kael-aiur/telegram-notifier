# Tasks: Extract DAO to core module

## Phase 1: 模块创建

- [x] 1.1 创建 `telegram-notifier-core` 目录结构
- [x] 1.2 创建 `telegram-notifier-core/pom.xml`
- [x] 1.3 更新根目录 `pom.xml` 添加 core 模块
- [x] 1.4 更新 `telegram-notifier-control-server/pom.xml` 添加 core 依赖

## Phase 2: 基础层迁移

- [x] 2.1 创建 `core/model/` 包
- [x] 2.2 迁移 `TelegramAccount` record 到 core
- [x] 2.3 迁移 `ProxyServer` record 到 core
- [x] 2.4 迁移 `PushChannel` record 到 core
- [x] 2.5 迁移 `NotificationRule` record 到 core
- [x] 2.6 迁移 `DeliveryResult` record 到 core
- [x] 2.7 迁移 `StatisticsResponse` record 到 core
- [x] 2.8 迁移其他相关 record 类
- [x] 2.9 迁移 `JsonSupport` 到 `core/support/`
- [x] 2.10 创建 `ValidationSupport` 提取通用验证方法

## Phase 3: DAO 实现

### AdminDao

- [x] 3.1 创建 `AdminDao.java`
- [x] 3.2 实现 `count()` 方法
- [x] 3.3 实现 `insert()` 方法
- [x] 3.4 实现 `selectPasswordHashByUsername()` 方法

### TelegramAccountDao

- [x] 3.5 创建 `TelegramAccountDao.java`
- [x] 3.6 实现 `selectAll()` 方法
- [x] 3.7 实现 `selectById()` 方法
- [x] 3.8 实现 `selectByAuthorizationStateAndEnabled()` 方法
- [x] 3.9 实现 `insert()` 方法
- [x] 3.10 实现 `update()` 方法
- [x] 3.11 实现 `deleteById()` 方法
- [x] 3.12 实现 `updateAuthorizationState()` 方法
- [x] 3.13 实现 `updateScanSettings()` 方法
- [x] 3.14 实现 `updatePhoneNumber()` 方法

### ProxyDao

- [x] 3.15 创建 `ProxyDao.java`
- [x] 3.16 实现代理服务器 CRUD 方法
- [x] 3.17 实现账户-代理绑定方法
- [x] 3.18 实现 `selectProxiesByAccountId()` 连表查询

### PushChannelDao

- [x] 3.19 创建 `PushChannelDao.java`
- [x] 3.20 实现 CRUD 方法

### NotificationRuleDao

- [x] 3.21 创建 `NotificationRuleDao.java`
- [x] 3.22 实现 CRUD 方法

### NotifiedMessageDao

- [x] 3.23 创建 `NotifiedMessageDao.java`
- [x] 3.24 实现 `exists()` 方法
- [x] 3.25 实现 `insert()` 方法

### StatisticsDao

- [x] 3.26 创建 `StatisticsDao.java`
- [x] 3.27 实现统计更新方法
- [x] 3.28 实现统计查询方法

## Phase 4: Service 重构

- [x] 4.1 重构 `AdminService` 使用 `AdminDao`
- [x] 4.2 重构 `TelegramAccountService` 使用 `TelegramAccountDao`
- [x] 4.3 重构 `ProxyService` 使用 `ProxyDao`
- [x] 4.4 重构 `PushChannelService` 使用 `PushChannelDao`
- [x] 4.5 重构 `NotificationRuleService` 使用多个 Dao
- [x] 4.6 重构 `NotifiedTelegramMessageService` 使用 `NotifiedMessageDao`
- [x] 4.7 重构 `StatisticsService` 使用 `StatisticsDao`
- [x] 4.8 更新 `Domain.java` 保留 API 层类型
- [x] 4.9 清理 `Services.java` 中的重复代码

## Phase 5: 验证

- [x] 5.1 编译整个项目
- [x] 5.2 运行现有单元测试
- [x] 5.3 运行集成测试
- [ ] 5.4 手动测试 API 接口
- [ ] 5.5 检查日志输出

## 完成标准

- [x] 所有数据库操作通过 DAO 层访问
- [x] Service 层不直接使用 JdbcTemplate
- [x] 所有测试通过
- [ ] 功能与重构前一致
