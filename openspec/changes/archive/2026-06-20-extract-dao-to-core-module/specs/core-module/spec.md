## ADDED Requirements

### Requirement: Core 模块提供 DAO 层数据访问

系统 SHALL 提供 `telegram-notifier-core` 模块，封装所有数据库访问操作到 DAO 层。Service 层 SHALL 通过 DAO 对象访问数据库，而非直接使用 JdbcTemplate。

#### Scenario: DAO 层封装 administrators 表操作

- **WHEN** Service 需要查询管理员数量
- **THEN** 调用 `AdminDao.count()` 方法

#### Scenario: DAO 层封装 telegram_accounts 表操作

- **WHEN** Service 需要查询账户列表
- **THEN** 调用 `TelegramAccountDao.selectAll()` 方法

#### Scenario: Service 层不直接使用 JdbcTemplate

- **WHEN** 审查 Service 类代码
- **THEN** Service 类 SHALL NOT 包含 JdbcTemplate 字段或直接 SQL 语句

### Requirement: 每个数据库表有对应的 DAO 类

系统 SHALL 为每个数据库表提供对应的 DAO 类，封装该表的所有 CRUD 操作。

#### Scenario: administrators 表对应 AdminDao

- **WHEN** 需要操作 administrators 表
- **THEN** 使用 `AdminDao` 类

#### Scenario: telegram_accounts 表对应 TelegramAccountDao

- **WHEN** 需要操作 telegram_accounts 表
- **THEN** 使用 `TelegramAccountDao` 类

#### Scenario: proxy_servers 和 account_proxies 表对应 ProxyDao

- **WHEN** 需要操作代理相关表
- **THEN** 使用 `ProxyDao` 类

#### Scenario: push_channels 表对应 PushChannelDao

- **WHEN** 需要操作 push_channels 表
- **THEN** 使用 `PushChannelDao` 类

#### Scenario: notification_rules 表对应 NotificationRuleDao

- **WHEN** 需要操作 notification_rules 表
- **THEN** 使用 `NotificationRuleDao` 类

#### Scenario: notified_telegram_messages 表对应 NotifiedMessageDao

- **WHEN** 需要操作 notified_telegram_messages 表
- **THEN** 使用 `NotifiedMessageDao` 类

#### Scenario: 统计表对应 StatisticsDao

- **WHEN** 需要操作 message_stats、rule_stats 或 delivery_stats 表
- **THEN** 使用 `StatisticsDao` 类

### Requirement: 跨表操作在主表 DAO 中实现

对于连表查询操作，系统 SHALL 在主表的 DAO 类中实现该方法。

#### Scenario: 查询账户的代理配置列表

- **WHEN** 需要获取某个账户绑定的代理列表
- **THEN** 调用 `ProxyDao.selectProxiesByAccountId()` 方法（连表查询 proxy_servers 和 account_proxies）

### Requirement: 独立跨表操作由 Service 层协调

对于涉及多个表但非连表查询的操作，系统 SHALL 在各 DAO 中分别提供方法，由 Service 层协调调用。

#### Scenario: 绑定账户代理

- **WHEN** 需要更新账户的代理绑定
- **THEN** Service 调用 `ProxyDao.deleteBindingsByAccountId()` 删除旧绑定
- **AND** Service 循环调用 `ProxyDao.insertBinding()` 插入新绑定

### Requirement: Model 类使用 String 存储枚举值

Core 模块的 Model 类 SHALL 使用 String 类型存储枚举值，不依赖 Telegram SDK 类型。SDK 类型转换 SHALL 在 Service 层完成。

#### Scenario: TelegramAccount 使用 String 存储 authorizationState

- **WHEN** 从数据库读取账户数据
- **THEN** `TelegramAccount.authorizationState` 为 String 类型

#### Scenario: Service 层进行 SDK 类型转换

- **WHEN** Service 需要使用 SDK 的 AuthorizationState 枚举
- **THEN** Service 将 String 转换为 AuthorizationState.valueOf()

### Requirement: Core 模块不依赖 Telegram SDK

`telegram-notifier-core` 模块 SHALL NOT 依赖 `telegram-spring-boot-starter` 或任何 Telegram SDK。

#### Scenario: Core 模块依赖关系

- **WHEN** 检查 core 模块的 pom.xml
- **THEN** 不存在对 telegram-spring-boot-starter 的依赖

### Requirement: Support 类提供通用功能

Core 模块 SHALL 提供 Support 类封装通用功能，包括 JSON 处理和输入验证。

#### Scenario: JsonSupport 提供 JSON 序列化

- **WHEN** DAO 需要将 Map 或 List 序列化为 JSON 字符串
- **THEN** 使用 `JsonSupport.write()` 方法

#### Scenario: JsonSupport 提供 JSON 反序列化

- **WHEN** DAO 需要将 JSON 字符串反序列化为 Map 或 List
- **THEN** 使用 `JsonSupport.readMap()` 或 `JsonSupport.readLongList()` 方法

#### Scenario: ValidationSupport 提供输入验证

- **WHEN** 需要验证文本字段不为空
- **THEN** 使用 `ValidationSupport.requireText()` 方法

#### Scenario: ValidationSupport 提供默认值处理

- **WHEN** 需要处理布尔值的默认值
- **THEN** 使用 `ValidationSupport.bool()` 方法
