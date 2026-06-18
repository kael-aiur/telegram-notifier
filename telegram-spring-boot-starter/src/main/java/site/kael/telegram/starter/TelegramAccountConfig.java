package site.kael.telegram.starter;

import java.time.Duration;
import java.util.List;

public record TelegramAccountConfig(
        long accountId,
        String displayName,
        String phoneNumber,
        Duration scanFrequency,
        Duration unreadAgeThreshold,
        List<ProxyConfig> proxies
) {
}
