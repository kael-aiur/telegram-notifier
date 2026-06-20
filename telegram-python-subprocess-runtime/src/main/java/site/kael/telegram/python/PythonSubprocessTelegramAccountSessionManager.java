package site.kael.telegram.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramClientProperties;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramConnectionStatusListener;
import site.kael.telegram.starter.TelegramMessageEvent;
import site.kael.telegram.starter.TelegramMessageListener;
import site.kael.telegram.starter.TelegramScanRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class PythonSubprocessTelegramAccountSessionManager implements TelegramAccountSessionManager, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PythonSubprocessTelegramAccountSessionManager.class.getName());
    private static final long COMMAND_RESPONSE_TIMEOUT_SECONDS = 120;
    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TelegramClientProperties clientProperties;
    private final PythonTelegramRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;
    private final Map<Long, WorkerState> workers = new ConcurrentHashMap<>();
    private final List<TelegramMessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<TelegramConnectionStatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private volatile Path resolvedWorkerScript;
    private volatile String dependencyValidationError;
    private static final List<String> WORKER_RESOURCES = List.of(
            "telegram-python-worker/main.py",
            "telegram-python-worker/telegram_worker/__init__.py",
            "telegram-python-worker/telegram_worker/protocol.py",
            "telegram-python-worker/telegram_worker/security.py",
            "telegram-python-worker/telegram_worker/proxy.py",
            "telegram-python-worker/telegram_worker/messages.py",
            "telegram-python-worker/telegram_worker/worker.py"
    );

    public PythonSubprocessTelegramAccountSessionManager(TelegramClientProperties clientProperties,
                                                         PythonTelegramRuntimeProperties runtimeProperties,
                                                         ObjectMapper objectMapper) {
        this.clientProperties = Objects.requireNonNull(clientProperties, "clientProperties");
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public TelegramConnectionStatus start(TelegramAccountConfig config) {
        var state = workers.computeIfAbsent(config.accountId(), WorkerState::new);
        state.config = config;
        state.activeProxyId = activeProxyId(config.proxies());
        var validationError = validateConfiguration();
        if (validationError != null) {
            return fail(state, validationError);
        }
        if (!restartWorker(state)) {
            return state.status();
        }
        return sendCommandAndWaitStatus(state, "start", Map.of(
                "accountId", config.accountId(),
                "displayName", nullToEmpty(config.displayName()),
                "phoneNumber", nullToEmpty(config.phoneNumber()),
                "apiId", clientProperties.getApiId(),
                "apiHash", nullToEmpty(clientProperties.getApiHash()),
                "dataDir", accountDirectory(config.accountId()).toString()
        ));
    }

    @Override
    public TelegramConnectionStatus stop(long accountId) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        if (state.session != null && state.session.getStatus() == SessionStatus.RUNNING) {
            sendCommandAndWaitStatus(state, "stop", Map.of("accountId", accountId), 5);
        }
        stopWorker(state);
        state.authorizationState = AuthorizationState.LOGGED_OUT;
        state.activeProxyId = null;
        state.errorMessage = null;
        return publishStatus(state);
    }

    @Override
    public TelegramConnectionStatus submitPhone(long accountId, String phoneNumber) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        return sendCommandAndWaitStatus(state, "submit_phone", Map.of("accountId", accountId, "phoneNumber", requireText(phoneNumber, "phoneNumber")));
    }

    @Override
    public TelegramConnectionStatus submitCode(long accountId, String code) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        return sendCommandAndWaitStatus(state, "submit_code", Map.of("accountId", accountId, "code", requireText(code, "code")));
    }

    @Override
    public TelegramConnectionStatus submitPassword(long accountId, String password) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        return sendCommandAndWaitStatus(state, "submit_password", Map.of("accountId", accountId, "password", requireText(password, "password")));
    }

    @Override
    public TelegramConnectionStatus status(long accountId) {
        var state = workers.get(accountId);
        if (state == null) {
            return new TelegramConnectionStatus(accountId, AuthorizationState.LOGGED_OUT, null, null);
        }
        return state.status();
    }

    @Override
    public void updateProxies(long accountId, List<ProxyConfig> proxies) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        state.activeProxyId = activeProxyId(proxies);
        if (state.config != null) {
            state.config = new TelegramAccountConfig(
                    state.config.accountId(),
                    state.config.displayName(),
                    state.config.phoneNumber(),
                    state.config.scanFrequency(),
                    state.config.unreadAgeThreshold(),
                    proxies
            );
            start(state.config);
        } else {
            publishStatus(state);
        }
    }

    @Override
    public void scan(TelegramScanRequest request) {
        var state = workers.computeIfAbsent(request.accountId(), WorkerState::new);
        sendCommand(state, "scan", Map.of(
                "accountId", request.accountId(),
                "chatIds", request.chatIds(),
                "maxMessagesPerChat", request.maxMessagesPerChat()
        ));
    }

    @Override
    public void subscribe(TelegramMessageListener listener) {
        messageListeners.add(listener);
    }

    @Override
    public void subscribeStatus(TelegramConnectionStatusListener listener) {
        statusListeners.add(listener);
    }

    @Override
    public void publishTestMessage(TelegramMessageEvent event) {
        messageListeners.forEach(listener -> listener.onMessage(event));
    }

    @Override
    public void close() {
        workers.values().forEach(this::stopWorker);
    }

    private String validateConfiguration() {
        if (clientProperties.getApiId() == null || clientProperties.getApiId() <= 0) {
            return "Telegram api-id is required for python subprocess mode";
        }
        if (clientProperties.getApiHash() == null || clientProperties.getApiHash().isBlank()) {
            return "Telegram api-hash is required for python subprocess mode";
        }
        try {
            Files.createDirectories(dataDirectory());
            if (!Files.isDirectory(dataDirectory()) || !Files.isWritable(dataDirectory())) {
                return "Telegram data-dir is not writable: " + dataDirectory();
            }
            var workerScriptError = resolveWorkerScript();
            if (workerScriptError != null) {
                return workerScriptError;
            }
            var dependencyError = validatePythonDependencies();
            if (dependencyError != null) {
                return dependencyError;
            }
            return null;
        } catch (IOException e) {
            return "Telegram data-dir is not writable: " + dataDirectory();
        }
    }

    private boolean restartWorker(WorkerState state) {
        stopWorker(state);
        try {
            Files.createDirectories(accountDirectory(state.accountId));
            var session = new SubprocessTelegramSession(objectMapper, this::sanitize);
            subscribeSession(state, session);
            session.start(sessionConfig(state.config));
            state.session = session;
            return true;
        } catch (RuntimeException | IOException e) {
            fail(state, "Failed to start Python worker: " + safeError(e));
            return false;
        }
    }

    private TelegramSessionConfig sessionConfig(TelegramAccountConfig config) {
        return new TelegramSessionConfig(
                runtimeProperties.getExecutable(),
                resolvedWorkerScript,
                workerDirectory(),
                dataDirectory(),
                runtimeProperties.getExtraArgs(),
                Map.of(),
                sessionProxyPayload(config == null ? List.of() : config.proxies()),
                Duration.ofSeconds(10),
                Duration.ofSeconds(4)
        );
    }

    private void subscribeSession(WorkerState state, TelegramSession session) {
        session.getPublisher().subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                handleWorkerLine(state, item);
            }

            @Override
            public void onError(Throwable throwable) {
                fail(state, "Python session publisher failed: " + safeError(throwable));
            }

            @Override
            public void onComplete() {
            }
        });
    }

    private String resolveWorkerScript() throws IOException {
        if (runtimeProperties.getWorkerScript() != null) {
            var workerScript = runtimeProperties.getWorkerScript().toAbsolutePath().normalize();
            if (!Files.isRegularFile(workerScript)) {
                return "Python worker script does not exist: " + workerScript;
            }
            resolvedWorkerScript = workerScript;
            return null;
        }
        var workerDir = workerDirectory();
        Files.createDirectories(workerDir.resolve("telegram_worker"));
        for (String resource : WORKER_RESOURCES) {
            copyWorkerResource(resource, workerDir.resolve(resource.substring("telegram-python-worker/".length())));
        }
        resolvedWorkerScript = workerDir.resolve("main.py").toAbsolutePath().normalize();
        return null;
    }

    private void copyWorkerResource(String resourceName, Path target) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing bundled Python worker resource: " + resourceName);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String validatePythonDependencies() {
        if (dependencyValidationError != null) {
            return dependencyValidationError.isBlank() ? null : dependencyValidationError;
        }
        if (runtimeProperties.getRequiredModules().isEmpty()) {
            dependencyValidationError = "";
            return null;
        }
        var script = """
                import importlib.util, sys
                missing = [name for name in sys.argv[1:] if importlib.util.find_spec(name) is None]
                if missing:
                    print(",".join(missing))
                    sys.exit(42)
                """;
        var command = new ArrayList<String>();
        command.add(runtimeProperties.getExecutable());
        command.add("-c");
        command.add(script);
        command.addAll(runtimeProperties.getRequiredModules());
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            var exitCode = process.waitFor();
            if (exitCode == 0) {
                dependencyValidationError = "";
                return null;
            }
            var missing = output.isBlank() ? String.join(",", runtimeProperties.getRequiredModules()) : output;
            dependencyValidationError = "Missing Python modules: " + missing
                    + ". Install dependencies before starting real Telegram mode, for example: "
                    + runtimeProperties.getExecutable() + " -m pip install pyrogram tgcrypto";
            return dependencyValidationError;
        } catch (IOException e) {
            dependencyValidationError = "Failed to run Python executable '" + runtimeProperties.getExecutable()
                    + "': " + safeError(e)
                    + ". Install Python 3 or set telegram.client.python.executable.";
            return dependencyValidationError;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dependencyValidationError = "Interrupted while checking Python dependencies";
            return dependencyValidationError;
        }
    }

    private void stopWorker(WorkerState state) {
        state.stopping = true;
        if (state.session != null) {
            state.session.stop();
            state.session = null;
        }
        state.pendingReplies.values().forEach(PendingReply::complete);
        state.pendingReplies.clear();
    }

    private void sendCommand(WorkerState state, String command, Map<String, ?> fields) {
        if (state.session == null || state.session.getStatus() != SessionStatus.RUNNING) {
            LOGGER.fine("telegram python worker " + state.accountId + " is not running; skip command: " + command);
            return;
        }
        try {
            state.session.send(inputEnvelope(state, command, fields));
        } catch (RuntimeException e) {
            fail(state, "Failed to send command to Python worker: " + safeError(e));
        }
    }

    private TelegramConnectionStatus sendCommandAndWaitStatus(WorkerState state, String command, Map<String, ?> fields) {
        return sendCommandAndWaitStatus(state, command, fields, COMMAND_RESPONSE_TIMEOUT_SECONDS);
    }

    private TelegramConnectionStatus sendCommandAndWaitStatus(WorkerState state, String command, Map<String, ?> fields, long timeoutSeconds) {
        if (state.session == null || state.session.getStatus() != SessionStatus.RUNNING) {
            return fail(state, "Python worker is not running");
        }
        var inputId = nextInputId(state);
        var pending = new PendingReply();
        state.pendingReplies.put(inputId, pending);
        try {
            state.session.send(inputEnvelope(inputId, command, fields));
            if (!pending.await(timeoutSeconds, TimeUnit.SECONDS)) {
                return fail(state, "Python worker did not return a reply for command '" + command
                        + "' within " + timeoutSeconds + " seconds");
            }
            return pending.status == null ? state.status() : pending.status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(state, "Interrupted while waiting for Python worker command response");
        } catch (RuntimeException e) {
            return fail(state, "Failed to send command to Python worker: " + safeError(e));
        } finally {
            state.pendingReplies.remove(inputId);
        }
    }

    private String inputEnvelope(WorkerState state, String command, Map<String, ?> fields) {
        return inputEnvelope(nextInputId(state), command, fields);
    }

    private String inputEnvelope(String inputId, String command, Map<String, ?> fields) {
        var content = new LinkedHashMap<String, Object>();
        content.put("command", command);
        content.putAll(fields);
        return TelegramProtocolEnvelope.input(objectMapper, inputId, content);
    }

    private String nextInputId(WorkerState state) {
        return "java-" + state.accountId + "-" + state.inputIds.incrementAndGet();
    }

    private void handleWorkerLine(WorkerState state, String line) {
        try {
            var payload = TelegramProtocolEnvelope.validate(objectMapper, line);
            var type = TelegramProtocolEnvelope.text(payload, "type");
            if (TelegramProtocolEnvelope.TYPE_REPLY.equals(type)) {
                handleReply(state, payload);
            } else if (TelegramProtocolEnvelope.TYPE_MESSAGE.equals(type)) {
                handleMessage(state, payload);
            } else if (TelegramProtocolEnvelope.TYPE_LOG.equals(type)) {
                handleLog(state, payload);
            } else {
                LOGGER.info("telegram python worker " + state.accountId + " ignored protocol output type: " + type);
            }
        } catch (Exception e) {
            fail(state, "Invalid Python worker event: " + safeError(e));
        }
    }

    private void handleReply(WorkerState state, JsonNode payload) {
        var replyInputId = TelegramProtocolEnvelope.text(payload, "replyInputId");
        var content = payload.get("content");
        TelegramConnectionStatus status = null;
        var statusNode = statusNode(content);
        if (statusNode != null) {
            status = handleStatus(state, statusNode);
        } else if (content != null && content.has("error")) {
            status = fail(state, errorMessage(content.get("error")));
        } else {
            status = state.status();
        }
        var pending = state.pendingReplies.get(replyInputId);
        if (pending != null) {
            pending.status = status;
            pending.complete();
        }
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

    private TelegramConnectionStatus handleStatus(WorkerState state, JsonNode payload) {
        state.authorizationState = AuthorizationState.valueOf(text(payload, "state"));
        if (payload.hasNonNull("activeProxyId")) {
            state.activeProxyId = payload.get("activeProxyId").asLong();
        }
        state.errorMessage = payload.hasNonNull("errorMessage") ? payload.get("errorMessage").asText() : null;
        return publishStatus(state);
    }

    private void handleMessage(WorkerState state, JsonNode payload) {
        if (payload.hasNonNull("replyInputId")) {
            LOGGER.fine("telegram python worker " + state.accountId + " received command-scoped message for "
                    + payload.get("replyInputId").asText());
        }
        var content = payload.has("content") ? payload.get("content") : payload;
        var event = new TelegramMessageEvent(
                state.accountId,
                longValue(content, "chatId"),
                longValue(content, "messageId"),
                text(content, "chatTitle"),
                text(content, "chatType"),
                longValue(content, "senderId"),
                text(content, "senderName"),
                text(content, "senderUsername"),
                instant(content, "receivedAt"),
                text(content, "text")
        );
        LOGGER.info(() -> "telegram python worker " + state.accountId
                + " received message event: chatId=" + event.chatId()
                + ", chatType=" + event.chatType()
                + ", senderId=" + event.senderId()
                + ", senderUsername=" + nullToEmpty(event.senderUsername())
                + ", receivedAt=" + LOG_TIME_FORMATTER.format(event.receivedAt()));
        publishTestMessage(event);
    }

    private void handleLog(WorkerState state, JsonNode payload) {
        var content = payload.get("content");
        if (content == null) {
            return;
        }
        var message = content.isObject() ? text(content, "message") : content.asText();
        var sanitized = sanitize(message);
        state.rememberLog(sanitized);
        LOGGER.info("telegram python worker " + state.accountId + ": " + sanitized);
        if (sanitized != null && sanitized.startsWith("Python worker exited unexpectedly")) {
            fail(state, sanitized);
        }
    }

    private TelegramConnectionStatus fail(WorkerState state, String message) {
        state.authorizationState = AuthorizationState.ERROR;
        state.errorMessage = sanitize(message);
        var status = publishStatus(state);
        state.pendingReplies.values().forEach(pending -> {
            pending.status = status;
            pending.complete();
        });
        return status;
    }

    private TelegramConnectionStatus publishStatus(WorkerState state) {
        var status = state.status();
        statusListeners.forEach(listener -> listener.onStatus(status));
        return status;
    }

    private Long activeProxyId(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return null;
        }
        return proxies.stream().filter(ProxyConfig::enabled).findFirst().map(ProxyConfig::id).orElse(null);
    }

    private List<TelegramSessionProxyConfig> sessionProxyPayload(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return List.of();
        }
        return proxies.stream()
                .filter(ProxyConfig::enabled)
                .map(proxy -> new TelegramSessionProxyConfig(
                        proxy.id(),
                        proxy.protocol().name(),
                        proxy.host(),
                        proxy.port(),
                        proxy.username(),
                        proxy.password()
                ))
                .toList();
    }

    private Path accountDirectory(long accountId) {
        return dataDirectory().resolve("accounts").resolve(String.valueOf(accountId));
    }

    private Path workerDirectory() {
        if (runtimeProperties.getWorkingDirectory() != null) {
            return runtimeProperties.getWorkingDirectory().toAbsolutePath().normalize();
        }
        return dataDirectory().resolve("runtime").resolve("python-worker");
    }

    private Path dataDirectory() {
        return clientProperties.getDataDir().toAbsolutePath().normalize();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String text(JsonNode payload, String field) {
        return payload != null && payload.hasNonNull(field) ? payload.get(field).asText() : "";
    }

    private long longValue(JsonNode payload, String field) {
        return payload != null && payload.hasNonNull(field) ? payload.get(field).asLong() : 0L;
    }

    private Instant instant(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field) || payload.get(field).asText().isBlank()) {
            return Instant.now();
        }
        return Instant.parse(payload.get(field).asText());
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

    private String safeError(Throwable error) {
        var message = error.getMessage();
        return sanitize(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        var output = value;
        if (clientProperties.getApiHash() != null && !clientProperties.getApiHash().isBlank()) {
            output = output.replace(clientProperties.getApiHash(), "******");
        }
        for (var state : workers.values()) {
            if (state.config != null && state.config.proxies() != null) {
                for (ProxyConfig proxy : state.config.proxies()) {
                    if (proxy.password() != null && !proxy.password().isBlank()) {
                        output = output.replace(proxy.password(), "******");
                    }
                }
            }
        }
        return output;
    }

    private static final class WorkerState {
        private final long accountId;
        private final AtomicLong inputIds = new AtomicLong();
        private final Map<String, PendingReply> pendingReplies = new ConcurrentHashMap<>();
        private final ArrayDeque<String> recentLogs = new ArrayDeque<>();
        private volatile TelegramAccountConfig config;
        private volatile TelegramSession session;
        private volatile boolean stopping;
        private volatile AuthorizationState authorizationState = AuthorizationState.LOGGED_OUT;
        private volatile Long activeProxyId;
        private volatile String errorMessage;

        private WorkerState(long accountId) {
            this.accountId = accountId;
        }

        private TelegramConnectionStatus status() {
            return new TelegramConnectionStatus(accountId, authorizationState, activeProxyId, errorMessage);
        }

        private synchronized void rememberLog(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            recentLogs.addLast(line);
            while (recentLogs.size() > 8) {
                recentLogs.removeFirst();
            }
        }
    }

    private static final class PendingReply {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile TelegramConnectionStatus status;

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private void complete() {
            latch.countDown();
        }
    }
}
