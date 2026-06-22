package site.kael.telegram.notifier;

import org.junit.jupiter.api.Test;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramMessage;
import site.kael.telegram.notifier.core.model.TelegramAccount;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramUnreadScanSchedulerTest {
    @Test
    void peeksReadyAccountAndFeedsNotificationsWhenDue() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var notifications = mock(NotificationRuleService.class);
        var properties = new UnreadScanProperties();
        properties.setAccounts(new LinkedHashMap<>(java.util.Map.of(1L, List.of(42L, 43L))));
        when(accounts.list()).thenReturn(List.of(account(1L, AuthorizationState.READY, true, 30)));
        var message = new TelegramMessage(1L, 42L, 100L, "Ops", "group", 9L, "Ada", "ada", LocalDateTime.now(), "hello");
        when(sessions.peekUnreadMessages(eq(1L), eq(42L))).thenReturn(List.of(message));
        when(sessions.peekUnreadMessages(eq(1L), eq(43L))).thenReturn(List.of());
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, notifications, properties);

        scheduler.scanDueAccounts();

        verify(sessions).peekUnreadMessages(1L, 42L);
        verify(sessions).peekUnreadMessages(1L, 43L);
        verify(notifications).handleBatch(List.of(message));
    }

    @Test
    void skipsAccountsWithoutConfiguredChatIdsOrReadyState() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var notifications = mock(NotificationRuleService.class);
        var properties = new UnreadScanProperties();
        when(accounts.list()).thenReturn(List.of(
                account(1L, AuthorizationState.READY, true, 30),
                account(2L, AuthorizationState.WAIT_CODE, true, 30)
        ));
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, notifications, properties);

        scheduler.scanDueAccounts();

        verify(sessions, never()).peekUnreadMessages(anyLong(), anyLong());
        verify(notifications, never()).handleBatch(anyList());
    }

    @Test
    void respectsScanFrequency() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var notifications = mock(NotificationRuleService.class);
        var properties = new UnreadScanProperties();
        properties.setAccounts(new LinkedHashMap<>(java.util.Map.of(1L, List.of(42L))));
        when(accounts.list()).thenReturn(List.of(account(1L, AuthorizationState.READY, true, 60)));
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, notifications, properties);

        scheduler.scanDueAccounts();
        scheduler.scanDueAccounts();

        verify(sessions, times(1)).peekUnreadMessages(anyLong(), anyLong());
    }

    private TelegramAccount account(long id, AuthorizationState state, boolean enabled, long scanFrequencySeconds) {
        var now = java.time.Instant.now();
        return new TelegramAccount(id, "main", "+100000", enabled, state.name(), null, null,
                scanFrequencySeconds, 60, false, now, now);
    }
}
