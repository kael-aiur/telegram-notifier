package site.kael.telegram.notifier;

import org.junit.jupiter.api.Test;
import site.kael.telegram.notifier.core.dao.NotificationRuleDao;
import site.kael.telegram.notifier.core.model.DeliveryResult;
import site.kael.telegram.notifier.core.model.NotificationRule;
import site.kael.telegram.notifier.core.model.PushChannel;
import site.kael.telegram.notifier.core.model.TelegramAccount;
import site.kael.telegram.starter.TelegramMessage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRuleServiceTest {

    @Test
    void handleBatchPushesAtMostOnceEvenWhenMultipleMessagesMatchTheSameRule() {
        var ruleDao = mock(NotificationRuleDao.class);
        var channels = mock(PushChannelService.class);
        var statistics = mock(StatisticsService.class);
        var accounts = mock(TelegramAccountService.class);
        var notifiedMessages = mock(NotifiedTelegramMessageService.class);

        // 单条规则:text contains "alarm"
        var rule = new NotificationRule(1L, "alarm rule", true, "Telegram",
                Map.of("field", "text", "op", "contains", "value", "alarm"),
                "{{messageId}}", List.of(7L), Instant.now(), Instant.now());
        when(ruleDao.selectAll()).thenReturn(List.of(rule));

        // 阈值 60s,消息都足够老(120s 前),避免年龄阈值成为过滤因素
        var account = new TelegramAccount(1L, "main", "+1", true, "READY",
                null, null, 60L, 60L, true, Instant.now(), Instant.now());
        when(accounts.find(1L)).thenReturn(Optional.of(account));

        when(notifiedMessages.isNotified(any())).thenReturn(false);

        var channel = new PushChannel(7L, "bark", "BARK", true,
                Map.of("deviceKey", "k"), Instant.now(), Instant.now());
        when(channels.get(7L)).thenReturn(channel);
        when(channels.send(any(), anyString())).thenReturn(new DeliveryResult(true, "sent"));

        var service = new NotificationRuleService(ruleDao, channels, statistics, accounts, notifiedMessages);

        // 4 条未读,其中 2 条命中规则(alarm),2 条不命中 —— 复现用户真实场景
        var old = LocalDateTime.now().minusSeconds(120);
        var batch = List.of(
                msg(101L, "alarm one", old),
                msg(102L, "no keyword", old),
                msg(103L, "alarm two", old),
                msg(104L, "no keyword", old)
        );

        service.handleBatch(batch);

        // 核心不变量:一批至多一次推送
        verify(channels, times(1)).send(any(), anyString());
        // 整批全部 remember(含不命中的)
        verify(notifiedMessages, times(4)).remember(any());
    }

    private TelegramMessage msg(long messageId, String text, LocalDateTime receivedAt) {
        return new TelegramMessage(1L, 42L, messageId, "Ops", "group",
                9L, "Ada", "ada", receivedAt, text);
    }
}
