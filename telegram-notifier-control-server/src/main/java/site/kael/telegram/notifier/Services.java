package site.kael.telegram.notifier;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.ProxyProtocol;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramMessageEvent;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
class AdminService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean hasAdmin() {
        Integer count = jdbc.queryForObject("select count(*) from administrators", Integer.class);
        return count != null && count > 0;
    }

    void initialize(String username, String password) {
        if (hasAdmin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "administrator already exists");
        }
        jdbc.update("insert into administrators(username, password_hash, created_at) values(?,?,?)",
                requireText(username, "username"), passwordEncoder.encode(requireText(password, "password")), Instant.now().toString());
    }

    boolean authenticate(String username, String password) {
        var rows = jdbc.query("select password_hash from administrators where username = ?",
                (rs, rowNum) -> rs.getString("password_hash"), username);
        return !rows.isEmpty() && passwordEncoder.matches(password == null ? "" : password, rows.getFirst());
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }
}

@Service
class TelegramAccountService {
    private final JdbcTemplate jdbc;
    private final ProxyService proxyService;
    private final TelegramAccountSessionManager sessions;

    TelegramAccountService(JdbcTemplate jdbc, ProxyService proxyService, TelegramAccountSessionManager sessions) {
        this.jdbc = jdbc;
        this.proxyService = proxyService;
        this.sessions = sessions;
        this.sessions.subscribeStatus(this::saveStatus);
    }

    List<TelegramAccount> list() {
        return jdbc.query("select * from telegram_accounts order by id", mapper());
    }

    TelegramAccount get(long id) {
        return jdbc.query("select * from telegram_accounts where id = ?", mapper(), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }

    TelegramAccount create(TelegramAccountRequest request) {
        var now = Instant.now().toString();
        jdbc.update("""
                insert into telegram_accounts(display_name, phone_number, enabled, scan_frequency_seconds, unread_age_threshold_seconds, created_at, updated_at)
                values(?,?,?,?,?,?,?)
                """, requireText(request.displayName(), "displayName"), request.phoneNumber(),
                bool(request.enabled(), true) ? 1 : 0,
                positive(request.scanFrequencySeconds(), 60),
                positive(request.unreadAgeThresholdSeconds(), 3600),
                now, now);
        Long id = jdbc.queryForObject("select last_insert_rowid()", Long.class);
        return get(id);
    }

    TelegramAccount update(long id, TelegramAccountRequest request) {
        get(id);
        jdbc.update("""
                update telegram_accounts
                set display_name = ?, phone_number = ?, enabled = ?, scan_frequency_seconds = ?, unread_age_threshold_seconds = ?, updated_at = ?
                where id = ?
                """, requireText(request.displayName(), "displayName"), request.phoneNumber(),
                bool(request.enabled(), true) ? 1 : 0,
                positive(request.scanFrequencySeconds(), 60),
                positive(request.unreadAgeThresholdSeconds(), 3600),
                Instant.now().toString(), id);
        return get(id);
    }

    void delete(long id) {
        jdbc.update("delete from telegram_accounts where id = ?", id);
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
        jdbc.update("update telegram_accounts set phone_number = ?, updated_at = ? where id = ?", phone, Instant.now().toString(), id);
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
        jdbc.update("update telegram_accounts set scan_frequency_seconds = ?, unread_age_threshold_seconds = ?, updated_at = ? where id = ?",
                positive(frequencySeconds, 60), positive(unreadAgeSeconds, 3600), Instant.now().toString(), id);
        return get(id);
    }

    void refreshProxies(long accountId) {
        sessions.updateProxies(accountId, proxyService.configsForAccount(accountId));
    }

    private void saveStatus(TelegramConnectionStatus status) {
        jdbc.update("""
                update telegram_accounts set authorization_state = ?, active_proxy_id = ?, connection_error = ?, updated_at = ? where id = ?
                """, status.authorizationState().name(), status.activeProxyId(), status.errorMessage(), Instant.now().toString(), status.accountId());
    }

    private RowMapper<TelegramAccount> mapper() {
        return (rs, rowNum) -> new TelegramAccount(
                rs.getLong("id"),
                rs.getString("display_name"),
                rs.getString("phone_number"),
                rs.getInt("enabled") == 1,
                site.kael.telegram.starter.AuthorizationState.valueOf(rs.getString("authorization_state")),
                nullableLong(rs.getObject("active_proxy_id")),
                rs.getString("connection_error"),
                rs.getLong("scan_frequency_seconds"),
                rs.getLong("unread_age_threshold_seconds"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        return value.trim();
    }

    private boolean bool(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long positive(Long value, long defaultValue) {
        var v = value == null ? defaultValue : value;
        if (v <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must be positive");
        return v;
    }

    private long positive(long value, long defaultValue) {
        return positive(Long.valueOf(value), defaultValue);
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}

@Service
class ProxyService {
    private final JdbcTemplate jdbc;

    ProxyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ProxyServer> list() {
        return jdbc.query("select * from proxy_servers order by id", mapper()).stream().map(ProxyServer::masked).toList();
    }

    ProxyServer get(long id) {
        return jdbc.query("select * from proxy_servers where id = ?", mapper(), id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "proxy not found"));
    }

    ProxyServer create(ProxyServerRequest request) {
        var now = Instant.now().toString();
        jdbc.update("""
                insert into proxy_servers(name, protocol, host, port, username, password, enabled, created_at, updated_at)
                values(?,?,?,?,?,?,?,?,?)
                """, requireText(request.name(), "name"), Objects.requireNonNull(request.protocol(), "protocol").name(),
                requireText(request.host(), "host"), validPort(request.port()), request.username(), request.password(),
                bool(request.enabled(), true) ? 1 : 0, now, now);
        Long id = jdbc.queryForObject("select last_insert_rowid()", Long.class);
        return get(id).masked();
    }

    ProxyServer update(long id, ProxyServerRequest request) {
        get(id);
        jdbc.update("""
                update proxy_servers set name = ?, protocol = ?, host = ?, port = ?, username = ?, password = ?, enabled = ?, updated_at = ? where id = ?
                """, requireText(request.name(), "name"), Objects.requireNonNull(request.protocol(), "protocol").name(),
                requireText(request.host(), "host"), validPort(request.port()), request.username(), request.password(),
                bool(request.enabled(), true) ? 1 : 0, Instant.now().toString(), id);
        return get(id).masked();
    }

    void delete(long id) {
        jdbc.update("delete from proxy_servers where id = ?", id);
    }

    List<Long> bindings(long accountId) {
        return jdbc.query("select proxy_id from account_proxies where account_id = ? order by priority",
                (rs, rowNum) -> rs.getLong("proxy_id"), accountId);
    }

    void bind(long accountId, List<Long> proxyIds) {
        jdbc.update("delete from account_proxies where account_id = ?", accountId);
        int priority = 0;
        for (Long proxyId : proxyIds == null ? List.<Long>of() : proxyIds) {
            get(proxyId);
            jdbc.update("insert into account_proxies(account_id, proxy_id, priority) values(?,?,?)", accountId, proxyId, priority++);
        }
    }

    List<ProxyConfig> configsForAccount(long accountId) {
        return jdbc.query("""
                select p.* from proxy_servers p
                join account_proxies ap on ap.proxy_id = p.id
                where ap.account_id = ?
                order by ap.priority
                """, mapper(), accountId).stream()
                .map(p -> new ProxyConfig(p.id(), p.protocol(), p.host(), p.port(), p.username(), p.password(), p.enabled()))
                .toList();
    }

    private RowMapper<ProxyServer> mapper() {
        return (rs, rowNum) -> new ProxyServer(rs.getLong("id"), rs.getString("name"),
                ProxyProtocol.valueOf(rs.getString("protocol")), rs.getString("host"), rs.getInt("port"),
                rs.getString("username"), rs.getString("password"), rs.getInt("enabled") == 1,
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        return value.trim();
    }

    private boolean bool(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private int validPort(Integer port) {
        if (port == null || port <= 0 || port > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid port is required");
        }
        return port;
    }
}

@Service
class PushChannelService {
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final RestClient restClient = RestClient.create();

    PushChannelService(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<PushChannel> list() {
        return jdbc.query("select * from push_channels order by id", mapper()).stream().map(PushChannel::masked).toList();
    }

    PushChannel get(long id) {
        return jdbc.query("select * from push_channels where id = ?", mapper(), id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found"));
    }

    PushChannel create(PushChannelRequest request) {
        var now = Instant.now().toString();
        var config = request.config() == null ? Map.<String, Object>of() : request.config();
        jdbc.update("""
                insert into push_channels(name, type, enabled, config_json, created_at, updated_at) values(?,?,?,?,?,?)
                """, requireText(request.name(), "name"), Objects.requireNonNull(request.type(), "type").name(),
                bool(request.enabled(), true) ? 1 : 0, json.write(config), now, now);
        Long id = jdbc.queryForObject("select last_insert_rowid()", Long.class);
        return get(id).masked();
    }

    PushChannel update(long id, PushChannelRequest request) {
        get(id);
        var config = request.config() == null ? Map.<String, Object>of() : request.config();
        jdbc.update("update push_channels set name = ?, type = ?, enabled = ?, config_json = ?, updated_at = ? where id = ?",
                requireText(request.name(), "name"), Objects.requireNonNull(request.type(), "type").name(),
                bool(request.enabled(), true) ? 1 : 0, json.write(config), Instant.now().toString(), id);
        return get(id).masked();
    }

    void delete(long id) {
        jdbc.update("delete from push_channels where id = ?", id);
    }

    DeliveryResult test(long id) {
        return send(get(id), "Telegram Notifier test");
    }

    DeliveryResult send(PushChannel channel, String content) {
        if (!channel.enabled()) {
            return new DeliveryResult(false, "channel disabled");
        }
        if (channel.type() != PushChannelType.BARK) {
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

    private RowMapper<PushChannel> mapper() {
        return (rs, rowNum) -> new PushChannel(rs.getLong("id"), rs.getString("name"),
                PushChannelType.valueOf(rs.getString("type")), rs.getInt("enabled") == 1,
                json.readMap(rs.getString("config_json")), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        return value.trim();
    }

    private boolean bool(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}

@Service
class NotificationRuleService {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final PushChannelService channels;
    private final StatisticsService statistics;

    NotificationRuleService(JdbcTemplate jdbc, JsonSupport json, PushChannelService channels, StatisticsService statistics,
                            TelegramAccountSessionManager sessions) {
        this.jdbc = jdbc;
        this.json = json;
        this.channels = channels;
        this.statistics = statistics;
        sessions.subscribe(this::handle);
    }

    List<NotificationRule> list() {
        return jdbc.query("select * from notification_rules order by id", mapper());
    }

    NotificationRule get(long id) {
        return jdbc.query("select * from notification_rules where id = ?", mapper(), id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found"));
    }

    NotificationRule create(NotificationRuleRequest request) {
        var now = Instant.now().toString();
        jdbc.update("""
                insert into notification_rules(name, enabled, source_label, condition_json, template, channel_ids_json, created_at, updated_at)
                values(?,?,?,?,?,?,?,?)
                """, requireText(request.name(), "name"), bool(request.enabled(), true) ? 1 : 0,
                defaultText(request.sourceLabel(), "服务器"), json.write(defaultCondition(request.condition())),
                defaultText(request.template(), "{{receivedAt}} 收到来自{{sourceLabel}}的通知消息"),
                json.write(request.channelIds() == null ? List.of() : request.channelIds()), now, now);
        Long id = jdbc.queryForObject("select last_insert_rowid()", Long.class);
        return get(id);
    }

    NotificationRule update(long id, NotificationRuleRequest request) {
        get(id);
        jdbc.update("""
                update notification_rules set name = ?, enabled = ?, source_label = ?, condition_json = ?, template = ?, channel_ids_json = ?, updated_at = ?
                where id = ?
                """, requireText(request.name(), "name"), bool(request.enabled(), true) ? 1 : 0,
                defaultText(request.sourceLabel(), "服务器"), json.write(defaultCondition(request.condition())),
                defaultText(request.template(), "{{receivedAt}} 收到来自{{sourceLabel}}的通知消息"),
                json.write(request.channelIds() == null ? List.of() : request.channelIds()), Instant.now().toString(), id);
        return get(id);
    }

    void delete(long id) {
        jdbc.update("delete from notification_rules where id = ?", id);
    }

    void handle(TelegramMessageEvent event) {
        statistics.incrementMessages(event.accountId());
        list().stream().filter(NotificationRule::enabled).filter(rule -> matches(rule.condition(), event)).forEach(rule -> {
            statistics.incrementRuleHit(rule.id());
            var content = render(rule, event);
            for (Long channelId : rule.channelIds()) {
                var result = channels.send(channels.get(channelId), content);
                statistics.recordDelivery(rule.id(), channelId, result.success(), result.message());
            }
        });
    }

    boolean matches(Map<String, Object> condition, TelegramMessageEvent event) {
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

    String render(NotificationRule rule, TelegramMessageEvent event) {
        var values = new LinkedHashMap<String, String>();
        values.put("receivedAt", FORMATTER.format(event.receivedAt()));
        values.put("accountId", String.valueOf(event.accountId()));
        values.put("chatId", String.valueOf(event.chatId()));
        values.put("chatTitle", nullToEmpty(event.chatTitle()));
        values.put("chatType", nullToEmpty(event.chatType()));
        values.put("senderId", String.valueOf(event.senderId()));
        values.put("senderName", nullToEmpty(event.senderName()));
        values.put("senderUsername", nullToEmpty(event.senderUsername()));
        values.put("sourceLabel", nullToEmpty(rule.sourceLabel()));
        values.put("text", nullToEmpty(event.text()));
        var output = rule.template();
        for (var entry : values.entrySet()) {
            output = output.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return output;
    }

    private String fieldValue(String field, TelegramMessageEvent event) {
        return switch (field) {
            case "accountId" -> String.valueOf(event.accountId());
            case "chatId" -> String.valueOf(event.chatId());
            case "chatTitle" -> nullToEmpty(event.chatTitle());
            case "chatType" -> nullToEmpty(event.chatType());
            case "senderId" -> String.valueOf(event.senderId());
            case "senderName" -> nullToEmpty(event.senderName());
            case "senderUsername" -> nullToEmpty(event.senderUsername());
            case "text" -> nullToEmpty(event.text());
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

    private RowMapper<NotificationRule> mapper() {
        return (rs, rowNum) -> new NotificationRule(rs.getLong("id"), rs.getString("name"),
                rs.getInt("enabled") == 1, rs.getString("source_label"), json.readMap(rs.getString("condition_json")),
                rs.getString("template"), json.readLongList(rs.getString("channel_ids_json")),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));
    }

    private Map<String, Object> defaultCondition(Map<String, Object> condition) {
        return condition == null ? Map.of() : condition;
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        return value.trim();
    }

    private boolean bool(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

@Service
class StatisticsService {
    private final JdbcTemplate jdbc;

    StatisticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void incrementMessages(long accountId) {
        jdbc.update("""
                insert into message_stats(bucket, account_id, message_count) values(?,?,1)
                on conflict(bucket, account_id) do update set message_count = message_count + 1
                """, bucket(), accountId);
    }

    void incrementRuleHit(long ruleId) {
        jdbc.update("""
                insert into rule_stats(bucket, rule_id, hit_count) values(?,?,1)
                on conflict(bucket, rule_id) do update set hit_count = hit_count + 1
                """, bucket(), ruleId);
    }

    void recordDelivery(long ruleId, long channelId, boolean success, String message) {
        jdbc.update("""
                insert into delivery_stats(bucket, rule_id, channel_id, success_count, failure_count, last_error)
                values(?,?,?,?,?,?)
                on conflict(bucket, rule_id, channel_id) do update set
                    success_count = success_count + excluded.success_count,
                    failure_count = failure_count + excluded.failure_count,
                    last_error = excluded.last_error
                """, bucket(), ruleId, channelId, success ? 1 : 0, success ? 0 : 1, success ? null : truncate(message));
    }

    StatisticsResponse query() {
        return new StatisticsResponse(
                jdbc.queryForList("select * from message_stats order by bucket desc, account_id"),
                jdbc.queryForList("select * from rule_stats order by bucket desc, rule_id"),
                jdbc.queryForList("select * from delivery_stats order by bucket desc, rule_id, channel_id")
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
