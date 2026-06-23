package site.kael.telegram.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import site.kael.telegram.notifier.core.dao.NotificationRuleDao;
import site.kael.telegram.notifier.core.dao.TelegramAccountDao;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Flyway 之后执行的启动数据迁移：
 * 1. 将 UnreadScanProperties 中的 chatIds 写入账号的 monitored_chat_ids_json（仅当为空）
 * 2. 将 account_id IS NULL 的历史规则关联到最小 id 的账号（无账号则删除）
 */
@Component
@Order(1) // runs after Flyway (order 0)
class DataMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final UnreadScanProperties scanProperties;
    private final TelegramAccountDao accountDao;
    private final NotificationRuleDao ruleDao;
    private final JdbcTemplate jdbc;

    DataMigrationRunner(UnreadScanProperties scanProperties, TelegramAccountDao accountDao,
                        NotificationRuleDao ruleDao, JdbcTemplate jdbc) {
        this.scanProperties = scanProperties;
        this.accountDao = accountDao;
        this.ruleDao = ruleDao;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateMonitoredChatIds();
        migrateRuleAccountIds();
    }

    private void migrateMonitoredChatIds() {
        Map<Long, List<Long>> configAccounts = scanProperties.getAccounts();
        if (configAccounts == null || configAccounts.isEmpty()) {
            return;
        }
        var allAccounts = accountDao.selectAll();
        var now = Instant.now().toString();

        for (var entry : configAccounts.entrySet()) {
            long accountId = entry.getKey();
            List<Long> chatIds = entry.getValue() == null ? List.of() :
                    entry.getValue().stream().filter(id -> id != null && id != 0L).distinct().toList();
            if (chatIds.isEmpty()) {
                continue;
            }

            var account = allAccounts.stream().filter(a -> a.id() == accountId).findFirst();
            if (account.isEmpty()) {
                log.warn("配置中的账号 {} 已不存在，跳过 chatIds 迁移", accountId);
                continue;
            }
            if (!account.get().monitoredChatIds().isEmpty()) {
                log.debug("账号 {} 已有 monitored chatIds，跳过配置迁移", accountId);
                continue;
            }

            accountDao.updateMonitoredChatIds(accountId, chatIds, now);
            log.info("已将配置中的 chatIds {} 迁移到账号 {} ({})", chatIds, accountId, account.get().displayName());
        }
    }

    private void migrateRuleAccountIds() {
        var nullRules = jdbc.queryForList(
                "SELECT id, name FROM notification_rules WHERE account_id IS NULL");
        if (nullRules.isEmpty()) {
            return;
        }

        var allAccounts = accountDao.selectAll();
        var now = Instant.now().toString();

        if (allAccounts.isEmpty()) {
            for (var rule : nullRules) {
                long ruleId = ((Number) rule.get("id")).longValue();
                String ruleName = (String) rule.get("name");
                ruleDao.deleteById(ruleId);
                log.warn("无可用账号，删除历史规则 {} (id: {})", ruleName, ruleId);
            }
            return;
        }

        long targetAccountId = allAccounts.stream().mapToLong(a -> a.id()).min().orElse(0L);
        String targetAccountName = allAccounts.stream().filter(a -> a.id() == targetAccountId)
                .findFirst().map(a -> a.displayName()).orElse("unknown");

        for (var rule : nullRules) {
            long ruleId = ((Number) rule.get("id")).longValue();
            String ruleName = (String) rule.get("name");
            jdbc.update("UPDATE notification_rules SET account_id = ? WHERE id = ?",
                    targetAccountId, ruleId);
            log.info("已将历史规则 '{}' (id: {}) 归属到账号 '{}' (id: {})",
                    ruleName, ruleId, targetAccountName, targetAccountId);
        }
    }
}
