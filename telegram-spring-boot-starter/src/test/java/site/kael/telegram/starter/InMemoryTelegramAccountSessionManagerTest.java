package site.kael.telegram.starter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTelegramAccountSessionManagerTest {
    @Test
    void mapsLoginStatesAndNormalizesEvents() {
        var manager = new InMemoryTelegramAccountSessionManager();
        var proxy = new ProxyConfig(7L, ProxyProtocol.SOCKS5, "localhost", 1080, null, null, true);

        var started = manager.start(new TelegramAccountConfig(1L, "main", "+100000000", Duration.ofMinutes(1), Duration.ofHours(1), List.of(proxy)));
        assertThat(started.authorizationState()).isEqualTo(AuthorizationState.WAIT_PHONE);
        assertThat(started.activeProxyId()).isEqualTo(7L);

        assertThat(manager.submitPhone(1L, "+100000000").authorizationState()).isEqualTo(AuthorizationState.WAIT_CODE);
        assertThat(manager.submitCode(1L, "12345").authorizationState()).isEqualTo(AuthorizationState.READY);

        var received = new AtomicReference<TelegramMessageEvent>();
        manager.subscribe(received::set);
        var event = new TelegramMessageEvent(1L, 2L, 4L, "server", "channel", 3L, "sender", "sender",
                java.time.Instant.now(), "secret");
        manager.publishTestMessage(event);
        assertThat(received.get()).isEqualTo(event);
    }
}
