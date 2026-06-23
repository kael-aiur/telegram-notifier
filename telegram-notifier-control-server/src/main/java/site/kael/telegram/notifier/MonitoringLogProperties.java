package site.kael.telegram.notifier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram-notifier.monitoring-log")
class MonitoringLogProperties {
    private int maxPerAccount = 1000;

    public int getMaxPerAccount() {
        return maxPerAccount;
    }

    public void setMaxPerAccount(int maxPerAccount) {
        this.maxPerAccount = maxPerAccount <= 0 ? 1000 : maxPerAccount;
    }
}
