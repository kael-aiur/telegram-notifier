package site.kael.telegram.notifier;

import java.util.List;
import java.util.Map;

// API Request/Response types

record BootstrapStatus(boolean needsAdminInit) {
}

record InitAdminRequest(String username, String password) {
}

record LoginRequest(String username, String password) {
}

record LoginResponse(String token) {
}

record TelegramAccountRequest(
        String displayName,
        String phoneNumber,
        Boolean enabled,
        Long scanFrequencySeconds,
        Long unreadAgeThresholdSeconds
) {
}

record LoginInput(String value) {
}

record ProxyServerRequest(
        String name,
        site.kael.telegram.starter.ProxyProtocol protocol,
        String host,
        Integer port,
        String username,
        String password,
        Boolean enabled
) {
}

record AccountProxyBindingRequest(List<Long> proxyIds) {
}

enum PushChannelType {
    BARK
}

record PushChannelRequest(
        String name,
        PushChannelType type,
        Boolean enabled,
        Map<String, Object> config
) {
}

record NotificationRuleRequest(
        String name,
        Boolean enabled,
        String sourceLabel,
        Map<String, Object> condition,
        String template,
        List<Long> channelIds
) {
}
