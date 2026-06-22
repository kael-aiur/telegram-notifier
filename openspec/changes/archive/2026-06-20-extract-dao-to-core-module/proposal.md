# Extract DAO to core module

## Problem

当前项目的所有数据库操作直接散布在 `Services.java` 的各个 Service 类中，存在以下问题：

1. **职责混杂**：每个 Service 同时承担业务逻辑和数据库访问，违反单一职责原则
2. **代码重复**：多个 Service 中存在相似的验证方法（`requireText`、`bool`、`positive` 等）
3. **可测试性差**：难以对业务逻辑进行单元测试，必须依赖真实的数据库
4. **扩展困难**：添加新的数据源或修改持久化策略需要改动所有 Service

## Solution

创建 `telegram-notifier-core` 核心模块，将所有数据库操作提取到 DAO（Data Access Object）层，实现关注点分离。

### 架构变化

```
重构前：
┌─────────────────┐
│  Controllers    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    Services     │  ← 业务逻辑 + 数据库操作混合
│  (JdbcTemplate) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    SQLite       │
└─────────────────┘

重构后：
┌─────────────────┐
│  Controllers    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    Services     │  ← 只包含业务逻辑
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│      telegram-notifier-core     │
│  ┌───────────────────────────┐  │
│  │       DAO Layer           │  │  ← 数据访问层
│  │  AdminDao, AccountDao...  │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │       Model Layer         │  │  ← 领域模型
│  │  TelegramAccount, Proxy...│  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │       Support Layer       │  │  ← 公共支持
│  │  JsonSupport, Validation  │  │
│  └───────────────────────────┘  │
└────────────────┬────────────────┘
                 │
                 ▼
        ┌─────────────────┐
        │    SQLite       │
        └─────────────────┘
```

### DAO 类设计

| DAO 类 | 负责表 | 主要职责 |
|--------|--------|----------|
| `AdminDao` | administrators | 管理员 CRUD |
| `TelegramAccountDao` | telegram_accounts | 账户 CRUD + 状态管理 |
| `ProxyDao` | proxy_servers, account_proxies | 代理 CRUD + 绑定管理 |
| `PushChannelDao` | push_channels | 推送渠道 CRUD |
| `NotificationRuleDao` | notification_rules | 规则 CRUD |
| `NotifiedMessageDao` | notified_telegram_messages | 已通知消息记录 |
| `StatisticsDao` | message_stats, rule_stats, delivery_stats | 统计数据读写 |

### 跨表操作策略

- **连表查询**：在主表 DAO 中增加方法（如 `ProxyDao.selectProxiesByAccountId()`）
- **独立操作**：各 DAO 各自提供方法，由 Service 层协调调用

### 模型类处理

- 所有 model 类使用 String 类型存储枚举值（如 `authorizationState`）
- 涉及 Telegram SDK 类型（`AuthorizationState`、`ProxyProtocol`）的转换在 Service 层完成
- core 模块不依赖 `telegram-spring-boot-starter`

## Why

1. **关注点分离**：DAO 负责数据访问，Service 负责业务逻辑，职责清晰
2. **可测试性**：可以 Mock DAO 层对 Service 进行单元测试
3. **可维护性**：数据库操作集中管理，便于优化和维护
4. **可扩展性**：未来可轻松替换数据源（如从 SQLite 迁移到 PostgreSQL）

## Scope

### In scope

- 创建 `telegram-notifier-core` 模块
- 实现 7 个 DAO 类
- 迁移 Model 类和 Support 类
- 重构 Service 层使用 DAO
- 更新模块依赖关系

### Out of scope

- 数据库表结构变更
- 前端变更
- API 接口变更
- 新增功能

## Implementation sketch

```
Phase 1: 模块创建
├── 创建 telegram-notifier-core 模块
├── 配置 pom.xml 依赖
└── 创建包结构 (dao, model, support)

Phase 2: 基础层迁移
├── 迁移 Domain.java 中的 record 类到 core/model/
├── 迁移 JsonSupport.java 到 core/support/
└── 创建 ValidationSupport.java 提取通用验证方法

Phase 3: DAO 实现
├── 实现 AdminDao (最简单，验证流程)
├── 实现 TelegramAccountDao
├── 实现 ProxyDao (包含跨表操作)
├── 实现 PushChannelDao
├── 实现 NotificationRuleDao
├── 实现 NotifiedMessageDao
└── 实现 StatisticsDao

Phase 4: Service 重构
├── 重构 AdminService 使用 AdminDao
├── 重构 TelegramAccountService 使用 TelegramAccountDao
├── 重构 ProxyService 使用 ProxyDao
├── 重构 PushChannelService 使用 PushChannelDao
├── 重构 NotificationRuleService 使用多个 Dao
├── 重构 NotifiedTelegramMessageService 使用 NotifiedMessageDao
└── 重构 StatisticsService 使用 StatisticsDao

Phase 5: 验证
├── 更新 telegram-notifier-control-server pom.xml
├── 运行现有测试
└── 确保功能正常
```

## Key files

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `pom.xml` | 修改 | 添加 core 模块到 modules |
| `telegram-notifier-core/pom.xml` | 新增 | 核心模块配置 |
| `telegram-notifier-core/src/.../dao/*.java` | 新增 | 7 个 DAO 类 |
| `telegram-notifier-core/src/.../model/*.java` | 迁移 | 领域模型类 |
| `telegram-notifier-core/src/.../support/*.java` | 迁移+新增 | 支持工具类 |
| `telegram-notifier-control-server/pom.xml` | 修改 | 添加 core 依赖 |
| `telegram-notifier-control-server/.../Services.java` | 重构 | 移除 JdbcTemplate，注入 Dao |
| `telegram-notifier-control-server/.../Domain.java` | 删除 | 内容迁移到 core/model/ |

## Risks

| 风险 | 缓解措施 |
|------|----------|
| 循环依赖 | core 模块不依赖 starter，SDK 类型转换在 Service 层完成 |
| 测试失败 | 分阶段实施，每完成一个 DAO 立即运行测试验证 |
| 性能影响 | DAO 层只是封装，不增加额外开销 |
| 合并冲突 | 一次性完成重构，避免长期分支 |

## Dependencies

- 无外部依赖
- 仅涉及项目内部模块重组

## Estimated effort

- **Phase 1-2**: 1-2 小时（模块创建 + 基础迁移）
- **Phase 3**: 2-3 小时（DAO 实现）
- **Phase 4**: 2-3 小时（Service 重构）
- **Phase 5**: 1 小时（测试验证）

**总计**: 6-9 小时
