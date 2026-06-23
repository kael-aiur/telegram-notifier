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
            if (!account.enabled() || !AuthorizationState.READY.name().equals(account.authorizationState())) {
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
}
