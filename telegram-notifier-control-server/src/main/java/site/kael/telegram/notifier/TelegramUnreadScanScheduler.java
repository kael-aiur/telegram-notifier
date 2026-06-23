package site.kael.telegram.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.notifier.core.dao.AccountMonitoringLogDao;
import site.kael.telegram.notifier.core.model.TelegramAccount;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未读消息轮询调度器(pull 模型):周期性对每个 READY 账号的每个监控 chatId 调
 * {@link TelegramAccountSessionManager#peekUnreadMessages},把拉到的消息逐条喂给
 * {@link NotificationRuleService#handleBatch}。轮询策略在本层,Telegram 通信能力在 client 层。
 */
@Service
class TelegramUnreadScanScheduler {
    private static final Logger log = LoggerFactory.getLogger(TelegramUnreadScanScheduler.class);

    private final TelegramAccountService accounts;
    private final TelegramAccountSessionManager sessions;
    private final NotificationRuleService notifications;
    private final AccountMonitoringLogDao monitoringLogDao;
    private final MonitoringLogProperties monitoringLogProperties;
    private final Map<Long, Instant> lastScans = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastRecoveryAttempts = new ConcurrentHashMap<>();

    TelegramUnreadScanScheduler(TelegramAccountService accounts,
                                TelegramAccountSessionManager sessions,
                                NotificationRuleService notifications,
                                AccountMonitoringLogDao monitoringLogDao,
                                MonitoringLogProperties monitoringLogProperties) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.notifications = notifications;
        this.monitoringLogDao = monitoringLogDao;
        this.monitoringLogProperties = monitoringLogProperties;
    }

    @Scheduled(fixedDelayString = "${telegram-notifier.unread-scan.scheduler-delay-ms:10000}")
    void scanDueAccounts() {
        var now = Instant.now();
        for (TelegramAccount account : accounts.list()) {
            if (!account.enabled()) {
                continue;
            }

            // 自动恢复 ERROR 状态的账号
            if (AuthorizationState.ERROR.name().equals(account.authorizationState())) {
                tryRecover(account, now);
                continue;
            }

            if (!AuthorizationState.READY.name().equals(account.authorizationState())) {
                continue;
            }
            var chatIds = account.monitoredChatIds();
            if (chatIds.isEmpty()) {
                log.debug("telegram account {} has no monitored chatIds; skip unread scan", account.id());
                continue;
            }
            var lastScan = lastScans.get(account.id());
            if (lastScan != null && Duration.between(lastScan, now).getSeconds() < account.scanFrequencySeconds()) {
                continue;
            }
            lastScans.put(account.id(), now);
            for (Long chatId : chatIds) {
                var messages = sessions.peekUnreadMessages(account.id(), chatId);
                int unreadCount = messages.size();
                int notifiedCount = 0;
                if (!messages.isEmpty()) {
                    notifiedCount = notifications.handleBatch(messages);
                }
                var scannedAt = Instant.now().toString();
                monitoringLogDao.insert(account.id(), chatId, scannedAt,
                        unreadCount, notifiedCount, scannedAt);
                monitoringLogDao.deleteOldestBeyond(account.id(), monitoringLogProperties.getMaxPerAccount());
            }
        }
    }

    private void tryRecover(TelegramAccount account, Instant now) {
        // 限制恢复频率：每 5 分钟最多尝试一次
        var lastAttempt = lastRecoveryAttempts.get(account.id());
        if (lastAttempt != null && Duration.between(lastAttempt, now).getSeconds() < 300) {
            return;
        }
        lastRecoveryAttempts.put(account.id(), now);
        log.info("尝试恢复 ERROR 状态的账号 {} ({})", account.id(), account.displayName());
        try {
            accounts.stop(account.id());
            accounts.start(account.id());
            log.info("账号 {} ({}) 恢复成功", account.id(), account.displayName());
            lastRecoveryAttempts.remove(account.id());
        } catch (Exception e) {
            log.warn("账号 {} ({}) 恢复失败: {}", account.id(), account.displayName(), e.getMessage());
        }
    }
}
