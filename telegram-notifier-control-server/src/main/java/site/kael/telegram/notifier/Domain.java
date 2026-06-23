package site.kael.telegram.notifier;

import site.kael.telegram.notifier.core.model.DeliveryResultEntry;

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
        Long unreadAgeThresholdSeconds,
        List<Long> monitoredChatIds
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
        Long accountId,
        String name,
        Boolean enabled,
        String sourceLabel,
        Map<String, Object> condition,
        String template,
        List<Long> channelIds
) {
}

record NotifiedMessageResponse(
        long accountId,
        long chatId,
        long messageId,
        String notifiedAt,
        List<Long> matchedRuleIds,
        List<DeliveryResultEntry> deliveryResults
) {
}

record NotifiedMessageWithRulesResponse(
        long accountId,
        long chatId,
        long messageId,
        String notifiedAt,
        List<RuleNameEntry> matchedRules,
        List<DeliveryResultEntry> deliveryResults
) {
}

record RuleNameEntry(long id, String name) {
}

record MonitoringLogResponse(
        long id,
        long accountId,
        long chatId,
        String scannedAt,
        int unreadCount,
        int notifiedCount,
        String createdAt
) {
}

record WorkerLogResponse(
        long id,
        long accountId,
        String level,
        String message,
        String createdAt
) {
}
