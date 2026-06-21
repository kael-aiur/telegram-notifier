package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramClientProperties;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramConnectionStatusListener;
import site.kael.telegram.starter.TelegramMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * 薄注册表实现:持有 {@code Map<accountId, TelegramClient>},把 starter 接口的每个方法委托给对应 client。
 *
 * <p>本类不再直接操作 {@link TelegramSession}、不再订阅 publisher、不再维护协议路由——这些都已下沉到
 * {@link DefaultTelegramClient}。本类只负责:按账号创建/缓存 client、组装 {@link TelegramClientConfig}
 * (业务字段 + 进程级 {@link TelegramSessionConfig})、bootstrap(worker 脚本解析与依赖检查)、status 扇出。
 */
public class PythonSubprocessTelegramAccountSessionManager implements TelegramAccountSessionManager, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PythonSubprocessTelegramAccountSessionManager.class.getName());

    private final TelegramClientProperties clientProperties;
    private final PythonTelegramRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;
    private final Map<Long, DefaultTelegramClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, TelegramAccountConfig> accountConfigs = new ConcurrentHashMap<>();
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
        accountConfigs.put(config.accountId(), config);
        var validationError = validateConfiguration();
        if (validationError != null) {
            return forward(new TelegramConnectionStatus(
                    config.accountId(), AuthorizationState.ERROR, activeProxyId(config.proxies()), validationError));
        }
        try {
            Files.createDirectories(accountDirectory(config.accountId()));
        } catch (IOException e) {
            return forward(new TelegramConnectionStatus(config.accountId(), AuthorizationState.ERROR, null,
                    "Telegram account data-dir is not writable: " + accountDirectory(config.accountId())));
        }
        var client = clients.computeIfAbsent(config.accountId(), id -> createClient(config));
        client.start();
        return statusOf(config.accountId(), client);
    }

    @Override
    public TelegramConnectionStatus stop(long accountId) {
        var client = clients.remove(accountId);
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        return forward(new TelegramConnectionStatus(accountId, AuthorizationState.LOGGED_OUT, null, null));
    }

    @Override
    public TelegramConnectionStatus submitPhone(long accountId, String phoneNumber) {
        return driveSubmit(accountId, "submit_phone", phoneNumber);
    }

    @Override
    public TelegramConnectionStatus submitCode(long accountId, String code) {
        return driveSubmit(accountId, "submit_code", code);
    }

    @Override
    public TelegramConnectionStatus submitPassword(long accountId, String password) {
        return driveSubmit(accountId, "submit_password", password);
    }

    private TelegramConnectionStatus driveSubmit(long accountId, String command, String value) {
        var client = clients.get(accountId);
        if (client == null) {
            return forward(new TelegramConnectionStatus(
                    accountId, AuthorizationState.ERROR, null, "account session not started"));
        }
        // starter 接口是显式 submit_phone/code/password,按精确命令提交,避免多态分发误判。
        try {
            client.submitAuthorization(command, value);
        } catch (RuntimeException e) {
            LOGGER.fine("telegram " + command + " failed for account " + accountId + ": " + safeError(e));
        }
        return statusOf(accountId, client);
    }

    @Override
    public TelegramConnectionStatus status(long accountId) {
        var client = clients.get(accountId);
        if (client == null) {
            return new TelegramConnectionStatus(accountId, AuthorizationState.LOGGED_OUT, null, null);
        }
        return statusOf(accountId, client);
    }

    @Override
    public void updateProxies(long accountId, List<ProxyConfig> proxies) {
        var existing = accountConfigs.get(accountId);
        if (existing != null) {
            accountConfigs.put(accountId, new TelegramAccountConfig(
                    existing.accountId(), existing.displayName(), existing.phoneNumber(),
                    existing.scanFrequency(), existing.unreadAgeThreshold(), proxies));
        }
        var client = clients.get(accountId);
        if (client != null) {
            client.updateProxies(sessionProxyPayload(proxies));
        }
    }

    @Override
    public List<TelegramMessage> peekUnreadMessages(long accountId, long chatId) {
        var client = clients.get(accountId);
        if (client == null) {
            return List.of();
        }
        try {
            return client.peekUnreadMessage(chatId);
        } catch (RuntimeException e) {
            LOGGER.warning("peekUnreadMessages failed for account " + accountId + ": " + safeError(e));
            return List.of();
        }
    }

    @Override
    public void subscribeStatus(TelegramConnectionStatusListener listener) {
        statusListeners.add(listener);
    }

    @Override
    public void close() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        });
        clients.clear();
    }

    // ---- client 装配 ----

    private DefaultTelegramClient createClient(TelegramAccountConfig config) {
        var session = new SubprocessTelegramSession(objectMapper, this::sanitize);
        var clientConfig = new TelegramClientConfig(
                config.accountId(),
                config.displayName(),
                config.phoneNumber(),
                clientProperties.getApiId(),
                clientProperties.getApiHash(),
                accountDirectory(config.accountId()),
                sessionConfig(config)
        );
        return new DefaultTelegramClient(session, clientConfig, objectMapper, this::sanitize, this::forward);
    }

    private TelegramConnectionStatus statusOf(long accountId, DefaultTelegramClient client) {
        return new TelegramConnectionStatus(
                accountId, client.getState(), client.activeProxyId(), client.errorMessage());
    }

    private TelegramConnectionStatus forward(TelegramConnectionStatus status) {
        statusListeners.forEach(listener -> listener.onStatus(status));
        return status;
    }

    // ---- bootstrap:脚本解析、依赖检查、目录、配置组装 ----

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

    private Long activeProxyId(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return null;
        }
        return proxies.stream().filter(ProxyConfig::enabled).findFirst().map(ProxyConfig::id).orElse(null);
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        for (var cfg : accountConfigs.values()) {
            if (cfg.proxies() != null) {
                for (ProxyConfig proxy : cfg.proxies()) {
                    if (proxy.password() != null && !proxy.password().isBlank()) {
                        output = output.replace(proxy.password(), "******");
                    }
                }
            }
        }
        return output;
    }
}
