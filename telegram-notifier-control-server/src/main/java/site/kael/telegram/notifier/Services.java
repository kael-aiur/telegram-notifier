package site.kael.telegram.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.ProxyProtocol;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramMessage;
import site.kael.telegram.notifier.core.dao.*;
import site.kael.telegram.notifier.core.model.*;
import site.kael.telegram.notifier.core.support.ValidationSupport;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private final AdminDao adminDao;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    AdminService(AdminDao adminDao) {
        this.adminDao = adminDao;
    }

    boolean hasAdmin() {
        return adminDao.count() > 0;
    }

    void initialize(String username, String password) {
        if (hasAdmin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "administrator already exists");
        }
        adminDao.insert(
                ValidationSupport.requireText(username, "username"),
                passwordEncoder.encode(ValidationSupport.requireText(password, "password")),
                Instant.now().toString());
    }

    boolean authenticate(String username, String password) {
        return adminDao.selectPasswordHashByUsername(username)
                .map(hash -> passwordEncoder.matches(password == null ? "" : password, hash))
                .orElse(false);
    }
}

@Service
class TelegramAccountService {
    private static final Logger log = LoggerFactory.getLogger(TelegramAccountService.class);
    private final TelegramAccountDao accountDao;
    private final ProxyService proxyService;
    private final TelegramAccountSessionManager sessions;

    TelegramAccountService(TelegramAccountDao accountDao, ProxyService proxyService, TelegramAccountSessionManager sessions) {
        this.accountDao = accountDao;
        this.proxyService = proxyService;
        this.sessions = sessions;
        this.sessions.subscribeStatus(this::saveStatus);
    }

    @EventListener(ApplicationReadyEvent.class)
    void autoStartReadyAccounts() {
        var readyAccounts = accountDao.selectByAuthorizationStateAndEnabled(
                AuthorizationState.READY.name(), true);
        if (readyAccounts.isEmpty()) {
            return;
        }
        log.info("auto-starting {} READY account(s)", readyAccounts.size());
        for (var account : readyAccounts) {
            try {
                start(account.id());
                log.info("auto-started account {} ({})", account.id(), account.displayName());
            } catch (Exception e) {
                log.warn("auto-start failed for account {} ({}): {}", account.id(), account.displayName(), e.getMessage());
            }
        }
    }

    List<TelegramAccount> list() {
        return accountDao.selectAll();
    }

    TelegramAccount get(long id) {
        return accountDao.selectById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }

    java.util.Optional<TelegramAccount> find(long id) {
        return accountDao.selectById(id);
    }

    TelegramAccount create(TelegramAccountRequest request) {
        var now = Instant.now().toString();
        long id = accountDao.insert(
                ValidationSupport.requireText(request.displayName(), "displayName"),
                request.phoneNumber(),
                ValidationSupport.bool(request.enabled(), true),
                ValidationSupport.positive(request.scanFrequencySeconds(), 60L),
                ValidationSupport.positive(request.unreadAgeThresholdSeconds(), 3600L),
                now, now);
        return get(id);
    }

    TelegramAccount update(long id, TelegramAccountRequest request) {
        get(id);
        accountDao.update(id,
                ValidationSupport.requireText(request.displayName(), "displayName"),
                request.phoneNumber(),
                ValidationSupport.bool(request.enabled(), true),
                ValidationSupport.positive(request.scanFrequencySeconds(), 60L),
                ValidationSupport.positive(request.unreadAgeThresholdSeconds(), 3600L),
                Instant.now().toString());
        return get(id);
    }

    void delete(long id) {
        accountDao.deleteById(id);
        sessions.stop(id);
    }

    TelegramConnectionStatus start(long id) {
        var account = get(id);
        var status = sessions.start(new TelegramAccountConfig(account.id(), account.displayName(),
                account.phoneNumber(),
                Duration.ofSeconds(account.scanFrequencySeconds()),
                Duration.ofSeconds(account.unreadAgeThresholdSeconds()),
                proxyService.configsForAccount(id)));
        saveStatus(status);
        return status;
    }

    TelegramConnectionStatus stop(long id) {
        var status = sessions.stop(id);
        saveStatus(status);
        return status;
    }

    TelegramConnectionStatus submitPhone(long id, String phone) {
        var status = sessions.submitPhone(id, phone);
        saveStatus(status);
        accountDao.updatePhoneNumber(id, phone, Instant.now().toString());
        return status;
    }

    TelegramConnectionStatus submitCode(long id, String code) {
        var status = sessions.submitCode(id, code);
        saveStatus(status);
        return status;
    }

    TelegramConnectionStatus submitPassword(long id, String password) {
        var status = sessions.submitPassword(id, password);
        saveStatus(status);
        return status;
    }

    TelegramConnectionStatus status(long id) {
        get(id);
        var status = sessions.status(id);
        saveStatus(status);
        return status;
    }

    TelegramAccount updateScan(long id, long frequencySeconds, long unreadAgeSeconds) {
        get(id);
        accountDao.updateScanSettings(id,
                ValidationSupport.positive(frequencySeconds, 60),
                ValidationSupport.positive(unreadAgeSeconds, 3600),
                Instant.now().toString());
        return get(id);
    }

    void refreshProxies(long accountId) {
        sessions.updateProxies(accountId, proxyService.configsForAccount(accountId));
    }

    private void saveStatus(TelegramConnectionStatus status) {
        accountDao.updateAuthorizationState(
                status.accountId(),
                status.authorizationState().name(),
                status.activeProxyId(),
                status.errorMessage(),
                Instant.now().toString());
    }
}

@Service
class ProxyService {
    private final ProxyDao proxyDao;

    ProxyService(ProxyDao proxyDao) {
        this.proxyDao = proxyDao;
    }

    List<ProxyServer> list() {
        return proxyDao.selectAllServers().stream().map(ProxyServer::masked).toList();
    }

    ProxyServer get(long id) {
        return proxyDao.selectServerById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "proxy not found"));
    }

    ProxyServer create(ProxyServerRequest request) {
        var now = Instant.now().toString();
        long id = proxyDao.insertServer(
                ValidationSupport.requireText(request.name(), "name"),
                java.util.Objects.requireNonNull(request.protocol(), "protocol").name(),
                ValidationSupport.requireText(request.host(), "host"),
                ValidationSupport.validPort(request.port()),
                request.username(),
                request.password(),
                ValidationSupport.bool(request.enabled(), true),
                now, now);
        return get(id).masked();
    }

    ProxyServer update(long id, ProxyServerRequest request) {
        get(id);
        proxyDao.updateServer(id,
                ValidationSupport.requireText(request.name(), "name"),
                java.util.Objects.requireNonNull(request.protocol(), "protocol").name(),
                ValidationSupport.requireText(request.host(), "host"),
                ValidationSupport.validPort(request.port()),
                request.username(),
                request.password(),
                ValidationSupport.bool(request.enabled(), true),
                Instant.now().toString());
        return get(id).masked();
    }

    void delete(long id) {
        proxyDao.deleteServerById(id);
    }

    List<Long> bindings(long accountId) {
        return proxyDao.selectProxyIdsByAccountId(accountId);
    }

    void bind(long accountId, List<Long> proxyIds) {
        proxyDao.deleteBindingsByAccountId(accountId);
        int priority = 0;
        for (Long proxyId : proxyIds == null ? List.<Long>of() : proxyIds) {
            get(proxyId);
            proxyDao.insertBinding(accountId, proxyId, priority++);
        }
    }

    List<ProxyConfig> configsForAccount(long accountId) {
        return proxyDao.selectProxiesByAccountId(accountId).stream()
                .map(p -> new ProxyConfig(p.id(), ProxyProtocol.valueOf(p.protocol()),
                        p.host(), p.port(), p.username(), p.password(), p.enabled()))
                .toList();
    }
}

@Service
class PushChannelService {
    private final PushChannelDao channelDao;
    private final RestClient restClient = RestClient.create();

    PushChannelService(PushChannelDao channelDao) {
        this.channelDao = channelDao;
    }

    List<PushChannel> list() {
        return channelDao.selectAll().stream().map(PushChannel::masked).toList();
    }

    PushChannel get(long id) {
        return channelDao.selectById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found"));
    }

    PushChannel create(PushChannelRequest request) {
        var now = Instant.now().toString();
        var config = request.config() == null ? Map.<String, Object>of() : request.config();
        long id = channelDao.insert(
                ValidationSupport.requireText(request.name(), "name"),
                java.util.Objects.requireNonNull(request.type(), "type").name(),
                ValidationSupport.bool(request.enabled(), true),
                config, now, now);
        return get(id).masked();
    }

    PushChannel update(long id, PushChannelRequest request) {
        get(id);
        var config = request.config() == null ? Map.<String, Object>of() : request.config();
        channelDao.update(id,
                ValidationSupport.requireText(request.name(), "name"),
                java.util.Objects.requireNonNull(request.type(), "type").name(),
                ValidationSupport.bool(request.enabled(), true),
                config, Instant.now().toString());
        return get(id).masked();
    }

    void delete(long id) {
        channelDao.deleteById(id);
    }

    DeliveryResult test(long id) {
        return send(get(id), "Telegram Notifier test");
    }

    DeliveryResult send(PushChannel channel, String content) {
        if (!channel.enabled()) {
            return new DeliveryResult(false, "channel disabled");
        }
        if (!"BARK".equals(channel.type())) {
            return new DeliveryResult(false, "unsupported channel type");
        }
        var serverUrl = String.valueOf(channel.config().getOrDefault("serverUrl", "https://api.day.app"));
        var deviceKey = String.valueOf(channel.config().getOrDefault("deviceKey", ""));
        if (deviceKey.isBlank()) {
            return new DeliveryResult(false, "Bark deviceKey is required");
        }
        try {
            URI uri = UriComponentsBuilder.fromUriString(serverUrl)
                    .pathSegment(deviceKey, content)
                    .build()
                    .encode()
                    .toUri();
            restClient.get().uri(uri).retrieve().toBodilessEntity();
            return new DeliveryResult(true, "sent");
        } catch (Exception e) {
            return new DeliveryResult(false, e.getMessage());
        }
    }
}

@Service
class NotifiedTelegramMessageService {
    private final NotifiedMessageDao notifiedMessageDao;

    NotifiedTelegramMessageService(NotifiedMessageDao notifiedMessageDao) {
        this.notifiedMessageDao = notifiedMessageDao;
    }

    boolean isNotified(TelegramMessage event) {
        if (!hasMessageIdentity(event)) {
            return false;
        }
        return notifiedMessageDao.exists(event.accountId(), event.chatId(), event.messageId());
    }

    void remember(TelegramMessage event) {
        if (!hasMessageIdentity(event)) {
            return;
        }
        notifiedMessageDao.insert(event.accountId(), event.chatId(), event.messageId(), Instant.now().toString());
    }

    private boolean hasMessageIdentity(TelegramMessage event) {
        return event.messageId() > 0;
    }
}

@Service
class NotificationRuleService {
    private static final Logger log = LoggerFactory.getLogger(NotificationRuleService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final NotificationRuleDao ruleDao;
    private final PushChannelService channels;
    private final StatisticsService statistics;
    private final TelegramAccountService accounts;
    private final NotifiedTelegramMessageService notifiedMessages;

    NotificationRuleService(NotificationRuleDao ruleDao, PushChannelService channels, StatisticsService statistics,
                            TelegramAccountService accounts, NotifiedTelegramMessageService notifiedMessages) {
        this.ruleDao = ruleDao;
        this.channels = channels;
        this.statistics = statistics;
        this.accounts = accounts;
        this.notifiedMessages = notifiedMessages;
    }

    List<NotificationRule> list() {
        return ruleDao.selectAll();
    }

    NotificationRule get(long id) {
        return ruleDao.selectById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found"));
    }

    NotificationRule create(NotificationRuleRequest request) {
        var now = Instant.now().toString();
        long id = ruleDao.insert(
                ValidationSupport.requireText(request.name(), "name"),
                ValidationSupport.bool(request.enabled(), true),
                ValidationSupport.defaultText(request.sourceLabel(), "服务器"),
                defaultCondition(request.condition()),
                ValidationSupport.defaultText(request.template(), "{{receivedAt}} 收到来自{{sourceLabel}}的通知消息"),
                request.channelIds() == null ? List.of() : request.channelIds(),
                now, now);
        return get(id);
    }

    NotificationRule update(long id, NotificationRuleRequest request) {
        get(id);
        ruleDao.update(id,
                ValidationSupport.requireText(request.name(), "name"),
                ValidationSupport.bool(request.enabled(), true),
                ValidationSupport.defaultText(request.sourceLabel(), "服务器"),
                defaultCondition(request.condition()),
                ValidationSupport.defaultText(request.template(), "{{receivedAt}} 收到来自{{sourceLabel}}的通知消息"),
                request.channelIds() == null ? List.of() : request.channelIds(),
                Instant.now().toString());
        return get(id);
    }

    void delete(long id) {
        ruleDao.deleteById(id);
    }

    void handle(TelegramMessage event) {
        if (event.messageId() > 0) {
            if (notifiedMessages.isNotified(event) || !isOldEnough(event)) {
                return;
            }
        }
        var matched = false;
        statistics.incrementMessages(event.accountId());
        for (NotificationRule rule : list().stream().filter(NotificationRule::enabled).filter(rule -> matches(rule.condition(), event)).toList()) {
            matched = true;
            statistics.incrementRuleHit(rule.id());
            var content = render(rule, event);
            for (Long channelId : rule.channelIds()) {
                PushChannel channel;
                try {
                    channel = channels.get(channelId);
                } catch (Exception e) {
                    log.warn("channel not found by id: {} for rule {}(id: {})", channelId, rule.name(), rule.id());
                    continue;
                }
                var result = channels.send(channel, content);
                statistics.recordDelivery(rule.id(), channelId, result.success(), result.message());
            }
        }
        if (matched) {
            notifiedMessages.remember(event);
        }
    }

    private boolean isOldEnough(TelegramMessage event) {
        return accounts.find(event.accountId())
                .map(account -> !event.receivedAt().isAfter(LocalDateTime.now().minusSeconds(account.unreadAgeThresholdSeconds())))
                .orElse(false);
    }

    boolean matches(Map<String, Object> condition, TelegramMessage event) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        if (condition.containsKey("all")) {
            return listConditions(condition.get("all")).stream().allMatch(c -> matches(c, event));
        }
        if (condition.containsKey("any")) {
            return listConditions(condition.get("any")).stream().anyMatch(c -> matches(c, event));
        }
        if (condition.containsKey("not")) {
            return !matches(asMap(condition.get("not")), event);
        }
        var field = String.valueOf(condition.getOrDefault("field", ""));
        var op = String.valueOf(condition.getOrDefault("op", "contains")).toLowerCase(Locale.ROOT);
        var expected = String.valueOf(condition.getOrDefault("value", ""));
        var actual = fieldValue(field, event);
        return switch (op) {
            case "equals" -> actual.equals(expected);
            case "startsWith", "startswith" -> actual.startsWith(expected);
            case "regex", "matches" -> actual.matches(expected);
            case "exists" -> !actual.isBlank();
            case "in" -> List.of(expected.split(",")).stream().map(String::trim).anyMatch(actual::equals);
            default -> actual.contains(expected);
        };
    }

    String render(NotificationRule rule, TelegramMessage event) {
        var values = new LinkedHashMap<String, String>();
        values.put("receivedAt", FORMATTER.format(event.receivedAt()));
        values.put("accountId", String.valueOf(event.accountId()));
        values.put("chatId", String.valueOf(event.chatId()));
        values.put("messageId", String.valueOf(event.messageId()));
        values.put("chatTitle", ValidationSupport.nullToEmpty(event.chatTitle()));
        values.put("chatType", ValidationSupport.nullToEmpty(event.chatType()));
        values.put("senderId", String.valueOf(event.senderId()));
        values.put("senderName", ValidationSupport.nullToEmpty(event.senderName()));
        values.put("senderUsername", ValidationSupport.nullToEmpty(event.senderUsername()));
        values.put("sourceLabel", ValidationSupport.nullToEmpty(rule.sourceLabel()));
        values.put("text", ValidationSupport.nullToEmpty(event.text()));
        var output = rule.template();
        for (var entry : values.entrySet()) {
            output = output.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return output;
    }

    private String fieldValue(String field, TelegramMessage event) {
        return switch (field) {
            case "accountId" -> String.valueOf(event.accountId());
            case "chatId" -> String.valueOf(event.chatId());
            case "messageId" -> String.valueOf(event.messageId());
            case "chatTitle" -> ValidationSupport.nullToEmpty(event.chatTitle());
            case "chatType" -> ValidationSupport.nullToEmpty(event.chatType());
            case "senderId" -> String.valueOf(event.senderId());
            case "senderName" -> ValidationSupport.nullToEmpty(event.senderName());
            case "senderUsername" -> ValidationSupport.nullToEmpty(event.senderUsername());
            case "text" -> ValidationSupport.nullToEmpty(event.text());
            default -> "";
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listConditions(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Map<String, Object> defaultCondition(Map<String, Object> condition) {
        return condition == null ? Map.of() : condition;
    }
}

@Service
class StatisticsService {
    private final StatisticsDao statisticsDao;

    StatisticsService(StatisticsDao statisticsDao) {
        this.statisticsDao = statisticsDao;
    }

    void incrementMessages(long accountId) {
        statisticsDao.incrementMessageCount(bucket(), accountId);
    }

    void incrementRuleHit(long ruleId) {
        statisticsDao.incrementRuleHitCount(bucket(), ruleId);
    }

    void recordDelivery(long ruleId, long channelId, boolean success, String message) {
        statisticsDao.upsertDeliveryStats(bucket(), ruleId, channelId,
                success ? 1 : 0, success ? 0 : 1,
                success ? null : truncate(message));
    }

    StatisticsResponse query() {
        return new StatisticsResponse(
                statisticsDao.selectAllMessageStats(),
                statisticsDao.selectAllRuleStats(),
                statisticsDao.selectAllDeliveryStats()
        );
    }

    private String bucket() {
        return Instant.now().truncatedTo(ChronoUnit.HOURS).toString();
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
