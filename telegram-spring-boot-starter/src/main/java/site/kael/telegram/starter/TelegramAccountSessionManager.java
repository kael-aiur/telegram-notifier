package site.kael.telegram.starter;

import java.util.List;

public interface TelegramAccountSessionManager {
    TelegramConnectionStatus start(TelegramAccountConfig config);

    TelegramConnectionStatus stop(long accountId);

    TelegramConnectionStatus submitPhone(long accountId, String phoneNumber);

    TelegramConnectionStatus submitCode(long accountId, String code);

    TelegramConnectionStatus submitPassword(long accountId, String password);

    TelegramConnectionStatus status(long accountId);

    void updateProxies(long accountId, List<ProxyConfig> proxies);

    void scan(long accountId);

    void subscribe(TelegramMessageListener listener);

    default void subscribeStatus(TelegramConnectionStatusListener listener) {
    }

    void publishTestMessage(TelegramMessageEvent event);
}
