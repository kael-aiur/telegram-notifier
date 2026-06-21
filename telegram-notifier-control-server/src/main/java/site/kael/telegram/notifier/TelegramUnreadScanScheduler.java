package site.kael.telegram.notifier;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramMessage;
import site.kael.telegram.notifier.core.model.TelegramAccount;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 未读消息轮询调度器(pull 模型):周期性对每个 READY 账号的每个监控 chatId 调
 * {@link TelegramAccountSessionManager#peekUnreadMessages},把拉到的消息逐条喂给
 * {@link NotificationRuleService#handle}。轮询策略在本层,Telegram 通信能力在 client 层。
 */
@Service
class TelegramUnreadScanScheduler {
    private static final Logger LOGGER = Logger.getLogger(TelegramUnreadScanScheduler.class.getName());

    private final TelegramAccountService accounts;
    private final TelegramAccountSessionManager sessions;
    private final NotificationRuleService notifications;
    private final UnreadScanProperties properties;
    private final Map<Long, Instant> lastScans = new ConcurrentHashMap<>();

    TelegramUnreadScanScheduler(TelegramAccountService accounts,
                                TelegramAccountSessionManager sessions,
                                NotificationRuleService notifications,
                                UnreadScanProperties properties) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.notifications = notifications;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${telegram-notifier.unread-scan.scheduler-delay-ms:10000}")
    void scanDueAccounts() {
        var now = Instant.now();
        for (TelegramAccount account : accounts.list()) {
            if (!account.enabled() || !AuthorizationState.READY.name().equals(account.authorizationState())) {
                continue;
            }
            var chatIds = properties.chatIds(account.id());
            if (chatIds.isEmpty()) {
                LOGGER.fine(() -> "telegram account " + account.id() + " has no monitored chatIds; skip unread scan");
                continue;
            }
            var lastScan = lastScans.get(account.id());
            if (lastScan != null && Duration.between(lastScan, now).getSeconds() < account.scanFrequencySeconds()) {
                continue;
            }
            lastScans.put(account.id(), now);
            for (Long chatId : chatIds) {
                for (TelegramMessage message : sessions.peekUnreadMessages(account.id(), chatId)) {
                    notifications.handle(message);
                }
            }
        }
    }
}
