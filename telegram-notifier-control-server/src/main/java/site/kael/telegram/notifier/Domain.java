package site.kael.telegram.notifier;

import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyProtocol;

import java.time.Instant;
import java.util.List;
import java.util.Map;

record BootstrapStatus(boolean needsAdminInit) {
}

record InitAdminRequest(String username, String password) {
}

record LoginRequest(String username, String password) {
}

record LoginResponse(String token) {
}

record TelegramAccount(
        long id,
        String displayName,
        String phoneNumber,
        boolean enabled,
        AuthorizationState authorizationState,
        Long activeProxyId,
        String connectionError,
        long scanFrequencySeconds,
        long unreadAgeThresholdSeconds,
        Instant createdAt,
        Instant updatedAt
) {
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

record ProxyServer(
        long id,
        String name,
        ProxyProtocol protocol,
        String host,
        int port,
        String username,
        String password,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    ProxyServer masked() {
        return new ProxyServer(id, name, protocol, host, port, username, password == null ? null : "******",
                enabled, createdAt, updatedAt);
    }
}

record ProxyServerRequest(
        String name,
        ProxyProtocol protocol,
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

record PushChannel(
        long id,
        String name,
        PushChannelType type,
        boolean enabled,
        Map<String, Object> config,
        Instant createdAt,
        Instant updatedAt
) {
    PushChannel masked() {
        if (config == null || !config.containsKey("deviceKey")) {
            return this;
        }
        var copy = new java.util.LinkedHashMap<>(config);
        copy.put("deviceKey", "******");
        return new PushChannel(id, name, type, enabled, copy, createdAt, updatedAt);
    }
}

record PushChannelRequest(
        String name,
        PushChannelType type,
        Boolean enabled,
        Map<String, Object> config
) {
}

record DeliveryResult(boolean success, String message) {
}

record NotificationRule(
        long id,
        String name,
        boolean enabled,
        String sourceLabel,
        Map<String, Object> condition,
        String template,
        List<Long> channelIds,
        Instant createdAt,
        Instant updatedAt
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

record StatisticsResponse(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> rules,
        List<Map<String, Object>> deliveries
) {
}
