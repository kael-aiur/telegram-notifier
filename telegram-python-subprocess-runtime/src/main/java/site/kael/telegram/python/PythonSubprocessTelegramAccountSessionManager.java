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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PythonSubprocessTelegramAccountSessionManager implements TelegramAccountSessionManager, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PythonSubprocessTelegramAccountSessionManager.class.getName());
    private static final long WORKER_READY_TIMEOUT_SECONDS = 10;
    private static final long COMMAND_RESPONSE_TIMEOUT_SECONDS = 120;

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
                "dataDir", accountDirectory(config.accountId()).toString(),
                "proxies", proxyPayload(config.proxies())
        ));
    }

    @Override
    public TelegramConnectionStatus stop(long accountId) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        if (state.writer != null) {
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
    public void scan(long accountId) {
        var state = workers.computeIfAbsent(accountId, WorkerState::new);
        sendCommand(state, "scan", Map.of("accountId", accountId));
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
            state.stopping = false;
            state.resetWorkerReady();
            Files.createDirectories(accountDirectory(state.accountId));
            var command = new ArrayList<String>();
            command.add(runtimeProperties.getExecutable());
            command.add(resolvedWorkerScript.toString());
            command.addAll(runtimeProperties.getExtraArgs());
            var builder = new ProcessBuilder(command);
            builder.directory(workerDirectory().toFile());
            builder.environment().put("PYTHONUNBUFFERED", "1");
            state.process = builder.start();
            state.writer = new BufferedWriter(new OutputStreamWriter(state.process.getOutputStream(), StandardCharsets.UTF_8));
            state.stdoutReader = new Thread(() -> readStdout(state), "telegram-python-worker-" + state.accountId + "-stdout");
            state.stderrReader = new Thread(() -> readStderr(state), "telegram-python-worker-" + state.accountId + "-stderr");
            state.exitWatcher = new Thread(() -> watchProcessExit(state, state.process), "telegram-python-worker-" + state.accountId + "-exit");
            state.stdoutReader.setDaemon(true);
            state.stderrReader.setDaemon(true);
            state.exitWatcher.setDaemon(true);
            state.stdoutReader.start();
            state.stderrReader.start();
            state.exitWatcher.start();
            return awaitWorkerReady(state);
        } catch (IOException e) {
            fail(state, "Failed to start Python worker: " + safeError(e));
            return false;
        }
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
        if (state.writer != null) {
            try {
                state.writer.close();
            } catch (IOException ignored) {
            }
            state.writer = null;
        }
        var process = state.process;
        if (process != null) {
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy();
                }
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            state.process = null;
        }
    }

    private void sendCommand(WorkerState state, String type, Map<String, ?> fields) {
        if (state.writer == null) {
            fail(state, "Python worker is not running");
            return;
        }
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", type);
            payload.putAll(fields);
            state.writer.write(objectMapper.writeValueAsString(payload));
            state.writer.newLine();
            state.writer.flush();
        } catch (IOException e) {
            fail(state, "Failed to send command to Python worker: " + safeError(e));
        }
    }

    private TelegramConnectionStatus sendCommandAndWaitStatus(WorkerState state, String type, Map<String, ?> fields) {
        return sendCommandAndWaitStatus(state, type, fields, COMMAND_RESPONSE_TIMEOUT_SECONDS);
    }

    private TelegramConnectionStatus sendCommandAndWaitStatus(WorkerState state, String type, Map<String, ?> fields, long timeoutSeconds) {
        if (state.writer == null) {
            return fail(state, "Python worker is not running");
        }
        state.beginStatusResponse();
        sendCommand(state, type, fields);
        return awaitStatusResponse(state, type, timeoutSeconds);
    }

    private boolean awaitWorkerReady(WorkerState state) {
        try {
            if (state.workerReady.await(WORKER_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return state.authorizationState != AuthorizationState.ERROR && state.writer != null;
            }
            fail(state, "Python worker did not report readiness within " + WORKER_READY_TIMEOUT_SECONDS + " seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(state, "Interrupted while waiting for Python worker readiness");
        }
        stopWorker(state);
        return false;
    }

    private TelegramConnectionStatus awaitStatusResponse(WorkerState state, String commandType, long timeoutSeconds) {
        try {
            var response = state.awaitStatusResponse(timeoutSeconds, TimeUnit.SECONDS);
            state.errorMessage = response.errorMessage();
            return response;
        } catch (TimeoutException e) {
            return fail(state, "Python worker did not return a status for command '" + commandType
                    + "' within " + timeoutSeconds + " seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(state, "Interrupted while waiting for Python worker command response");
        } finally {
            state.clearStatusResponse();
        }
    }

    private void readStdout(WorkerState state) {
        try (var reader = new BufferedReader(new InputStreamReader(state.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleWorkerLine(state, line);
            }
        } catch (IOException e) {
            fail(state, "Failed to read Python worker stdout: " + safeError(e));
        }
    }

    private void readStderr(WorkerState state) {
        try (var reader = new BufferedReader(new InputStreamReader(state.process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var sanitized = sanitize(line);
                state.rememberStderr(sanitized);
                LOGGER.info("telegram python worker " + state.accountId + ": " + sanitized);
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to read Python worker stderr", e);
        }
    }

    private void watchProcessExit(WorkerState state, Process process) {
        try {
            var exitCode = process.waitFor();
            if (state.process == process) {
                state.process = null;
                state.writer = null;
                if (!state.stopping) {
                    var stderr = state.recentStderr();
                    fail(state, "Python worker exited unexpectedly with code " + exitCode
                            + (stderr.isBlank() ? "" : ". Recent stderr: " + stderr));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleWorkerLine(WorkerState state, String line) {
        try {
            var payload = objectMapper.readTree(line);
            var workerState = text(payload, "worker_state");
            if ("read".equals(workerState)) {
                state.markWorkerReady();
                return;
            }
            var type = text(payload, "type");
            if ("status".equals(type)) {
                handleStatus(state, payload);
            } else if ("message".equals(type)) {
                handleMessage(state, payload);
            } else if ("error".equals(type)) {
                fail(state, text(payload, "message"));
            } else {
                fail(state, "Invalid Python worker event: missing type");
            }
        } catch (Exception e) {
            fail(state, "Invalid Python worker event: " + safeError(e));
        }
    }

    private void handleStatus(WorkerState state, JsonNode payload) {
        state.authorizationState = AuthorizationState.valueOf(text(payload, "state"));
        if (payload.hasNonNull("activeProxyId")) {
            state.activeProxyId = payload.get("activeProxyId").asLong();
        }
        state.errorMessage = payload.hasNonNull("errorMessage") ? payload.get("errorMessage").asText() : null;
        var status = publishStatus(state);
        state.completeStatusResponse(status);
    }

    private void handleMessage(WorkerState state, JsonNode payload) {
        publishTestMessage(new TelegramMessageEvent(
                state.accountId,
                longValue(payload, "chatId"),
                text(payload, "chatTitle"),
                text(payload, "chatType"),
                longValue(payload, "senderId"),
                text(payload, "senderName"),
                text(payload, "senderUsername"),
                instant(payload, "receivedAt"),
                text(payload, "text")
        ));
    }

    private TelegramConnectionStatus fail(WorkerState state, String message) {
        state.authorizationState = AuthorizationState.ERROR;
        state.errorMessage = sanitize(message);
        var status = publishStatus(state);
        state.markWorkerReady();
        state.completeStatusResponse(status);
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

    private List<Map<String, Object>> proxyPayload(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return List.of();
        }
        return proxies.stream()
                .filter(ProxyConfig::enabled)
                .map(proxy -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", proxy.id());
                    value.put("protocol", proxy.protocol().name());
                    value.put("host", proxy.host());
                    value.put("port", proxy.port());
                    value.put("username", proxy.username());
                    value.put("password", proxy.password());
                    return value;
                })
                .toList();
    }

    private java.nio.file.Path accountDirectory(long accountId) {
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
        return payload.hasNonNull(field) ? payload.get(field).asText() : "";
    }

    private long longValue(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asLong() : 0L;
    }

    private Instant instant(JsonNode payload, String field) {
        if (!payload.hasNonNull(field) || payload.get(field).asText().isBlank()) {
            return Instant.now();
        }
        return Instant.parse(payload.get(field).asText());
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
        private volatile TelegramAccountConfig config;
        private volatile Process process;
        private volatile BufferedWriter writer;
        private volatile Thread stdoutReader;
        private volatile Thread stderrReader;
        private volatile Thread exitWatcher;
        private volatile boolean stopping;
        private volatile CountDownLatch workerReady = new CountDownLatch(1);
        private final AtomicReference<CountDownLatch> statusResponse = new AtomicReference<>();
        private final ArrayDeque<String> recentStderr = new ArrayDeque<>();
        private volatile AuthorizationState authorizationState = AuthorizationState.LOGGED_OUT;
        private volatile Long activeProxyId;
        private volatile String errorMessage;

        private WorkerState(long accountId) {
            this.accountId = accountId;
        }

        private TelegramConnectionStatus status() {
            return new TelegramConnectionStatus(accountId, authorizationState, activeProxyId, errorMessage);
        }

        private void resetWorkerReady() {
            workerReady = new CountDownLatch(1);
        }

        private void markWorkerReady() {
            workerReady.countDown();
        }

        private void beginStatusResponse() {
            statusResponse.set(new CountDownLatch(1));
        }

        private void completeStatusResponse(TelegramConnectionStatus status) {
            var latch = statusResponse.get();
            if (latch != null) {
                latch.countDown();
            }
        }

        private TelegramConnectionStatus awaitStatusResponse(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
            var latch = statusResponse.get();
            if (latch == null || latch.await(timeout, unit)) {
                return status();
            }
            throw new TimeoutException();
        }

        private void clearStatusResponse() {
            statusResponse.set(null);
        }

        private synchronized void rememberStderr(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            recentStderr.addLast(line);
            while (recentStderr.size() > 8) {
                recentStderr.removeFirst();
            }
        }

        private synchronized String recentStderr() {
            return String.join(" | ", recentStderr);
        }
    }
}
