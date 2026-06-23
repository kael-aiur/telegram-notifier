package site.kael.telegram.notifier;

import org.junit.jupiter.api.Test;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramMessage;
import site.kael.telegram.notifier.core.dao.AccountMonitoringLogDao;
import site.kael.telegram.notifier.core.model.TelegramAccount;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
        var monitoringLogDao = mock(AccountMonitoringLogDao.class);
        var monitoringLogProperties = new MonitoringLogProperties();
        when(accounts.list()).thenReturn(List.of(account(1L, AuthorizationState.READY, true, 30)));
        var message = new TelegramMessage(1L, 42L, 100L, "Ops", "group", 9L, "Ada", "ada", LocalDateTime.now(), "hello");
        when(sessions.peekUnreadMessages(eq(1L), eq(42L))).thenReturn(List.of(message));
        when(sessions.peekUnreadMessages(eq(1L), eq(43L))).thenReturn(List.of());
        when(notifications.handleBatch(any())).thenReturn(1);
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, notifications, monitoringLogDao, monitoringLogProperties);

        scheduler.scanDueAccounts();

        verify(sessions).peekUnreadMessages(1L, 42L);
        verify(sessions).peekUnreadMessages(1L, 43L);
        verify(notifications).handleBatch(List.of(message));
        verify(monitoringLogDao, times(2)).insert(anyLong(), anyLong(), anyString(), anyInt(), anyInt(), anyString());
        verify(monitoringLogDao, times(2)).deleteOldestBeyond(eq(1L), eq(1000));
    }

    @Test
    void skipsAccountsWithoutConfiguredChatIdsOrReadyState() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var notifications = mock(NotificationRuleService.class);
        var monitoringLogDao = mock(AccountMonitoringLogDao.class);
        var monitoringLogProperties = new MonitoringLogProperties();
        when(accounts.list()).thenReturn(List.of(
                account(1L, AuthorizationState.READY, true, 30, List.of()),
                account(2L, AuthorizationState.WAIT_CODE, true, 30)
        ));
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, notifications, monitoringLogDao, monitoringLogProperties);

        scheduler.scanDueAccounts();

        verify(sessions, never()).peekUnreadMessages(anyLong(), anyLong());
        verify(notifications, never()).handleBatch(any());
    }

    @Test
    void respectsScanFrequency() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var notifications = mock(NotificationRuleService.class);
        var monitoringLogDao = mock(AccountMonitoringLogDao.class);
        var monitoringLogProperties = new MonitoringLogProperties();
        when(accounts.list()).thenReturn(List.of(account(1L, AuthorizationState.READY, true, 60, List.of(42L))));
        when(notifications.handleBatch(any())).thenReturn(1);
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, notifications, monitoringLogDao, monitoringLogProperties);

        scheduler.scanDueAccounts();
        scheduler.scanDueAccounts();

        verify(sessions, times(1)).peekUnreadMessages(anyLong(), anyLong());
    }

    private TelegramAccount account(long id, AuthorizationState state, boolean enabled, long scanFrequencySeconds) {
        return account(id, state, enabled, scanFrequencySeconds, List.of(42L, 43L));
    }

    private TelegramAccount account(long id, AuthorizationState state, boolean enabled, long scanFrequencySeconds, List<Long> chatIds) {
        var now = java.time.Instant.now();
        return new TelegramAccount(id, "main", "+100000", enabled, state.name(), null, null,
                scanFrequencySeconds, 60, false, chatIds, now, now);
    }
}
