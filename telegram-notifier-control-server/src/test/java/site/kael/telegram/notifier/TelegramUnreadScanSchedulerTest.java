package site.kael.telegram.notifier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramScanRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramUnreadScanSchedulerTest {
    @Test
    void scansReadyAccountWithConfiguredChatIdsWhenDue() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var properties = new UnreadScanProperties();
        properties.setAccounts(new LinkedHashMap<>(java.util.Map.of(1L, List.of(42L, 43L))));
        properties.setMaxMessagesPerChat(5);
        when(accounts.list()).thenReturn(List.of(account(1L, AuthorizationState.READY, true, 30)));
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, properties);

        scheduler.scanDueAccounts();

        var captor = ArgumentCaptor.forClass(TelegramScanRequest.class);
        verify(sessions).scan(captor.capture());
        assertThat(captor.getValue().accountId()).isEqualTo(1L);
        assertThat(captor.getValue().chatIds()).containsExactly(42L, 43L);
        assertThat(captor.getValue().maxMessagesPerChat()).isEqualTo(5);
    }

    @Test
    void skipsAccountsWithoutConfiguredChatIdsOrReadyState() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var properties = new UnreadScanProperties();
        when(accounts.list()).thenReturn(List.of(
                account(1L, AuthorizationState.READY, true, 30),
                account(2L, AuthorizationState.WAIT_CODE, true, 30)
        ));
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, properties);

        scheduler.scanDueAccounts();

        verify(sessions, never()).scan(org.mockito.ArgumentMatchers.any(TelegramScanRequest.class));
    }

    @Test
    void respectsScanFrequency() {
        var accounts = mock(TelegramAccountService.class);
        var sessions = mock(TelegramAccountSessionManager.class);
        var properties = new UnreadScanProperties();
        properties.setAccounts(new LinkedHashMap<>(java.util.Map.of(1L, List.of(42L))));
        when(accounts.list()).thenReturn(List.of(account(1L, AuthorizationState.READY, true, 60)));
        var scheduler = new TelegramUnreadScanScheduler(accounts, sessions, properties);

        scheduler.scanDueAccounts();
        scheduler.scanDueAccounts();

        verify(sessions).scan(org.mockito.ArgumentMatchers.any(TelegramScanRequest.class));
    }

    private TelegramAccount account(long id, AuthorizationState state, boolean enabled, long scanFrequencySeconds) {
        var now = Instant.now();
        return new TelegramAccount(id, "main", "+100000", enabled, state, null, null,
                scanFrequencySeconds, 60, now, now);
    }
}
