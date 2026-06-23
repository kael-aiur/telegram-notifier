package site.kael.telegram.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramMessage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * {@link TelegramClient} 的默认实现:包装单个 {@link TelegramSession},在构造时订阅其 publisher,
 * 内部完成懒启动、envelope 路由、命令响应等待与 peek 收集。
 *
 * <p>路由策略:
 * <ul>
 *   <li>{@code reply}:解析 status/error 更新共享业务状态,并完成对应 input 的等待。</li>
 *   <li>{@code message} 带 {@code replyInputId} 且属进行中的 peek(收集 disposition):收入返回列表。</li>
 *   <li>实时 {@code message}(无 {@code replyInputId} 或非收集)与 {@code log}:仅记元数据日志,不外送。</li>
 * </ul>
 *
 * <p>所有变更方法与命令发送均 synchronized:对单账号而言命令频率低,串行化可保证同一时刻只有一个
 * 在途命令,且 {@code CountDownLatch.await} 不释放 monitor,而 publisher 线程的 onEnvelope 路径
 * 不获取本锁,因此不会死锁。
 */
public class DefaultTelegramClient implements TelegramClient {
    private static final Logger LOGGER = Logger.getLogger(DefaultTelegramClient.class.getName());
    private static final long COMMAND_TIMEOUT_SECONDS = 120;

    private final TelegramSession session;
    private final ObjectMapper objectMapper;
    private final Consumer<TelegramConnectionStatus> statusListener;
    private final AtomicLong inputIds = new AtomicLong();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private volatile TelegramClientConfig config;
    private volatile TelegramSessionConfig sessionConfig;
    private volatile AuthorizationState authorizationState = AuthorizationState.LOGGED_OUT;
    private volatile Long activeProxyId;
    private volatile String errorMessage;
    private volatile boolean startSent;
    private volatile boolean closed;
    private volatile Consumer<String> logListener;

    public DefaultTelegramClient(TelegramSession session, TelegramClientConfig config,
                                 TelegramSessionConfig sessionConfig, ObjectMapper objectMapper) {
        this(session, config, sessionConfig, objectMapper, null);
    }

    /**
     * @param sessionConfig  进程级运行配置(executable/workerScript/proxies 等),由工厂注入,仅在实现内部使用。
     * @param statusListener 授权状态变更回调(供注册表 manager 扇出给 subscribeStatus 监听器),可为 null。
     */
    public DefaultTelegramClient(TelegramSession session, TelegramClientConfig config,
                                 TelegramSessionConfig sessionConfig, ObjectMapper objectMapper,
                                 Consumer<TelegramConnectionStatus> statusListener) {
        this.session = Objects.requireNonNull(session, "session");
        this.config = Objects.requireNonNull(config, "config");
        this.sessionConfig = Objects.requireNonNull(sessionConfig, "sessionConfig");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.statusListener = statusListener;
        session.getPublisher().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                onEnvelope(item);
            }

            @Override
            public void onError(Throwable throwable) {
                LOGGER.warning(() -> "TelegramSession publisher error: " + safe(throwable));
            }

            @Override
            public void onComplete() {
                // no-op
            }
        });
    }

    @Override
    public AuthorizationState getState() {
        return authorizationState;
    }

    /** 当前活动代理 id(来自 status),诊断用。 */
    public Long activeProxyId() {
        return activeProxyId;
    }

    /** 当前错误信息(已脱敏),诊断用。 */
    public String errorMessage() {
        return errorMessage;
    }

    /** 设置日志监听器,接收 Python worker 的日志消息(已脱敏)。 */
    public void setLogListener(Consumer<String> logListener) {
        this.logListener = logListener;
    }

    @Override
    public synchronized LoginFlow login() {
        ensureConnected();
        return new DefaultLoginFlow();
    }

    @Override
    public synchronized void start() {
        ensureConnected();
    }

    @Override
    public synchronized List<TelegramMessage> peekUnreadMessage(long chatId) {
        ensureConnected();
        return sendFetchCommand("fetch_unread", Map.of(
                "accountId", config.accountId(),
                "chatId", chatId
        ));
    }

    /**
     * 显式提交某个授权命令(供注册表 manager 按 starter 接口的 submitPhone/Code/Password 语义精确调用)。
     * 与 {@link LoginFlow#submit(String)} 的多态分发不同,本方法直接发送指定命令,值由调用方提供。
     */
    synchronized AuthorizationState submitAuthorization(String command, String value) {
        var fields = switch (command) {
            case "submit_phone" -> Map.of("accountId", config.accountId(), "phoneNumber", nullToEmpty(value));
            case "submit_code" -> Map.of("accountId", config.accountId(), "code", nullToEmpty(value));
            case "submit_password" -> Map.of("accountId", config.accountId(), "password", nullToEmpty(value));
            default -> throw new IllegalArgumentException("Unsupported authorization command: " + command);
        };
        ensureConnected();
        return sendStatusCommand(command, fields);
    }

    @Override
    public synchronized void updateProxies(List<ProxyConfig> proxies) {
        if (closed) {
            throw new IllegalStateException("TelegramClient is closed");
        }
        var newProxies = proxies == null ? List.<ProxyConfig>of() : List.copyOf(proxies);
        var sessionProxies = toSessionProxies(newProxies);
        var sc = sessionConfig;
        sessionConfig = new TelegramSessionConfig(
                sc.executable(), sc.workerScript(), sc.workingDirectory(), sc.runtimeDataDirectory(),
                sc.extraArgs(), sc.environment(), sessionProxies,
                sc.startupTimeout(), sc.shutdownTimeout());
        config = new TelegramClientConfig(config.accountId(), config.displayName(), config.phoneNumber(),
                config.apiId(), config.apiHash(), config.dataDir(), newProxies);
        stopSession();
        startSent = false;
        authorizationState = AuthorizationState.LOGGED_OUT;
        ensureConnected();
    }

    /** 业务形态 {@link ProxyConfig} → 底层 session 代理形态,仅在本实现内部使用。 */
    private List<TelegramSessionProxyConfig> toSessionProxies(List<ProxyConfig> proxies) {
        return proxies.stream()
                .filter(ProxyConfig::enabled)
                .map(proxy -> new TelegramSessionProxyConfig(
                        proxy.id(), proxy.protocol().name(), proxy.host(), proxy.port(),
                        proxy.username(), proxy.password()))
                .toList();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending.values().forEach(Pending::complete);
        pending.clear();
        stopSession();
    }

    private synchronized void ensureConnected() {
        if (closed) {
            throw new IllegalStateException("TelegramClient is closed");
        }
        ensureRunning();
        if (!startSent) {
            startSent = true;
            sendStartCommand();
        }
    }

    private void ensureRunning() {
        var status = session.getStatus();
        if (status == SessionStatus.RUNNING) {
            return;
        }
        if (status == SessionStatus.STARTING) {
            throw new IllegalStateException("TelegramSession is starting");
        }
        // NEW / STOPPED / FAILED:按当前 config(重新)启动子进程。新进程是全新的,
        // 必须重发 start 命令才能重连,因此重置 startSent(首次调用时本就是 false,赋值无害)。
        session.start(sessionConfig);
        startSent = false;
    }

    private void sendStartCommand() {
        var inputId = nextInputId();
        var entry = new Pending(false);
        pending.put(inputId, entry);
        try {
            session.send(inputEnvelope(inputId, "start", Map.of(
                    "accountId", config.accountId(),
                    "displayName", nullToEmpty(config.displayName()),
                    "phoneNumber", nullToEmpty(config.phoneNumber()),
                    "apiId", config.apiId(),
                    "apiHash", nullToEmpty(config.apiHash()),
                    "dataDir", config.dataDir() == null ? "" : config.dataDir().toString()
            )));
            await(entry, "start");
        } catch (RuntimeException e) {
            // worker 未运行(如已崩溃):session.send 抛异常,转 ERROR 而非上抛,避免拖垮调用方。
            applyError(safe(e));
        } finally {
            pending.remove(inputId);
        }
    }

    private AuthorizationState sendStatusCommand(String command, Map<String, ?> fields) {
        var inputId = nextInputId();
        var entry = new Pending(false);
        pending.put(inputId, entry);
        try {
            session.send(inputEnvelope(inputId, command, fields));
            await(entry, command);
        } catch (RuntimeException e) {
            applyError(safe(e));
        } finally {
            pending.remove(inputId);
        }
        return authorizationState;
    }

    private List<TelegramMessage> sendFetchCommand(String command, Map<String, ?> fields) {
        var inputId = nextInputId();
        var entry = new Pending(true);
        pending.put(inputId, entry);
        try {
            session.send(inputEnvelope(inputId, command, fields));
            await(entry, command);
        } catch (RuntimeException e) {
            applyError(safe(e));
        } finally {
            pending.remove(inputId);
        }
        return List.copyOf(entry.messages);
    }

    private void await(Pending entry, String command) {
        try {
            if (!entry.await(COMMAND_TIMEOUT_SECONDS)) {
                throw new IllegalStateException(
                        "No reply for '" + command + "' within " + COMMAND_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for reply to '" + command + "'");
        }
    }

    // ---- publisher 路由(运行在 publisher 线程,不获取本锁) ----

    private void onEnvelope(String line) {
        JsonNode envelope;
        try {
            envelope = TelegramProtocolEnvelope.validate(objectMapper, line);
        } catch (Exception e) {
            LOGGER.warning(() -> "Invalid protocol output ignored: " + safe(e));
            return;
        }
        var type = TelegramProtocolEnvelope.text(envelope, "type");
        switch (type) {
            case TelegramProtocolEnvelope.TYPE_REPLY -> handleReply(envelope);
            case TelegramProtocolEnvelope.TYPE_MESSAGE -> handleMessage(envelope);
            case TelegramProtocolEnvelope.TYPE_LOG -> handleLog(envelope);
            default -> LOGGER.fine(() -> "Ignored protocol type: " + type);
        }
    }

    private void handleReply(JsonNode envelope) {
        var content = envelope.get("content");
        var statusNode = statusNode(content);
        if (statusNode != null) {
            applyStatus(statusNode);
        } else if (content != null && content.has("error")) {
            applyError(errorMessage(content.get("error")));
        }
        complete(envelope);
    }

    private void handleMessage(JsonNode envelope) {
        var content = envelope.has("content") ? envelope.get("content") : envelope;
        var replyInputId = TelegramProtocolEnvelope.text(envelope, "replyInputId");
        var entry = replyInputId.isBlank() ? null : pending.get(replyInputId);
        if (entry != null && entry.collect) {
            entry.messages.add(toTelegramMessage(content));
        } else {
            logMessageMetadata(content);
        }
    }

    private void handleLog(JsonNode envelope) {
        var content = envelope.get("content");
        var message = (content != null && content.isObject())
                ? TelegramProtocolEnvelope.text(content, "message")
                : (content == null ? "" : content.asText());
        var sanitized = sanitize(message);
        LOGGER.info(() -> "telegram python worker: " + sanitized);
        var listener = logListener;
        if (listener != null) {
            try {
                listener.accept(sanitized);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        // SubprocessTelegramSession 在子进程异常退出时会发布该日志;据此置 ERROR 并解锁所有等待,
        // 使进行中的命令(如 start)不会一直阻塞到超时。
        if (message != null && message.startsWith("Python worker exited unexpectedly")) {
            applyError(message);
            pending.values().forEach(Pending::complete);
        }
    }

    private void complete(JsonNode envelope) {
        var replyInputId = TelegramProtocolEnvelope.text(envelope, "replyInputId");
        if (replyInputId.isBlank()) {
            return;
        }
        var entry = pending.get(replyInputId);
        if (entry != null) {
            entry.complete();
        }
    }

    private void applyStatus(JsonNode status) {
        authorizationState = AuthorizationState.valueOf(TelegramProtocolEnvelope.text(status, "state"));
        if (status.hasNonNull("activeProxyId")) {
            activeProxyId = status.get("activeProxyId").asLong();
        }
        errorMessage = status.hasNonNull("errorMessage") ? sanitize(status.get("errorMessage").asText()) : null;
        notifyStatus();
    }

    private void applyError(String message) {
        authorizationState = AuthorizationState.ERROR;
        errorMessage = sanitize(message);
        notifyStatus();
    }

    private void notifyStatus() {
        var listener = statusListener;
        if (listener == null) {
            return;
        }
        listener.accept(new TelegramConnectionStatus(
                config.accountId(), authorizationState, activeProxyId, errorMessage));
    }

    private void logMessageMetadata(JsonNode content) {
        LOGGER.info(() -> "telegram message observed: accountId=" + longValue(content, "accountId")
                + " chatId=" + longValue(content, "chatId")
                + " chatType=" + TelegramProtocolEnvelope.text(content, "chatType")
                + " senderId=" + longValue(content, "senderId")
                + " senderUsername=" + TelegramProtocolEnvelope.text(content, "senderUsername")
                + " receivedAt=" + TelegramProtocolEnvelope.text(content, "receivedAt"));
    }

    private JsonNode statusNode(JsonNode content) {
        if (content == null || !content.isObject()) {
            return null;
        }
        if (content.has("status")) {
            return content.get("status");
        }
        if (content.has("result") && content.get("result").has("status")) {
            return content.get("result").get("status");
        }
        return null;
    }

    private TelegramMessage toTelegramMessage(JsonNode content) {
        return new TelegramMessage(
                longValue(content, "accountId"),
                longValue(content, "chatId"),
                longValue(content, "messageId"),
                TelegramProtocolEnvelope.text(content, "chatTitle"),
                TelegramProtocolEnvelope.text(content, "chatType"),
                longValue(content, "senderId"),
                TelegramProtocolEnvelope.text(content, "senderName"),
                TelegramProtocolEnvelope.text(content, "senderUsername"),
                toLocalDateTime(TelegramProtocolEnvelope.text(content, "receivedAt")),
                TelegramProtocolEnvelope.text(content, "text")
        );
    }

    private LocalDateTime toLocalDateTime(String instantText) {
        if (instantText == null || instantText.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return Instant.parse(instantText).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private void stopSession() {
        try {
            var status = session.getStatus();
            if (status == SessionStatus.RUNNING || status == SessionStatus.STARTING) {
                session.stop();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private String inputEnvelope(String inputId, String command, Map<String, ?> fields) {
        var content = new LinkedHashMap<String, Object>();
        content.put("command", command);
        content.putAll(fields);
        return TelegramProtocolEnvelope.input(objectMapper, inputId, content);
    }

    private String nextInputId() {
        return "java-" + config.accountId() + "-" + inputIds.incrementAndGet();
    }

    private String errorMessage(JsonNode error) {
        if (error == null) {
            return "Python worker returned an error";
        }
        if (error.isObject() && error.hasNonNull("message")) {
            return error.get("message").asText();
        }
        return error.asText("Python worker returned an error");
    }

    private long longValue(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asLong() : 0L;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        var output = value;
        var apiHash = config.apiHash();
        if (apiHash != null && !apiHash.isBlank()) {
            output = output.replace(apiHash, "******");
        }
        for (ProxyConfig proxy : config.proxies()) {
            if (proxy.password() != null && !proxy.password().isBlank()) {
                output = output.replace(proxy.password(), "******");
            }
        }
        return output;
    }

    private String safe(Throwable error) {
        var message = error.getMessage();
        return sanitize(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }

    /** 进行中的命令等待项。collect=true 时收集 message envelope;否则只等终态 reply。 */
    private static final class Pending {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final boolean collect;
        private final List<TelegramMessage> messages = new CopyOnWriteArrayList<>();

        private Pending(boolean collect) {
            this.collect = collect;
        }

        private boolean await(long timeoutSeconds) throws InterruptedException {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        private void complete() {
            latch.countDown();
        }
    }

    /** 与 client 共享状态的登录流程句柄。submit 按当前环节多态分发。 */
    private final class DefaultLoginFlow implements LoginFlow {
        @Override
        public AuthorizationState getState() {
            return authorizationState;
        }

        @Override
        public AuthorizationState submit(String value) {
            synchronized (DefaultTelegramClient.this) {
                var state = authorizationState;
                var safeValue = nullToEmpty(value);
                return switch (state) {
                    case WAIT_PHONE -> sendStatusCommand("submit_phone",
                            Map.of("accountId", config.accountId(), "phoneNumber", safeValue));
                    case WAIT_CODE -> sendStatusCommand("submit_code",
                            Map.of("accountId", config.accountId(), "code", safeValue));
                    case WAIT_PASSWORD -> sendStatusCommand("submit_password",
                            Map.of("accountId", config.accountId(), "password", safeValue));
                    case READY -> throw new IllegalStateException("TelegramClient is already logged in");
                    default -> throw new IllegalStateException(
                            "Cannot submit in authorization state " + state);
                };
            }
        }
    }
}
