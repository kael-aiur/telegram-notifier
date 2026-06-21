package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramClientProperties;
import site.kael.telegram.starter.TelegramConnectionStatus;

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
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link TelegramClientFactory} 默认实现:持有进程级参数与 worker bootstrap 结果,
 * 在 {@link #create} 内部创建 {@link SubprocessTelegramSession}、组装 {@link TelegramSessionConfig}
 * 并注入 {@link DefaultTelegramClient},不向上层暴露任何 session 类型。
 *
 * <p>worker bootstrap(脚本解析 + Python 依赖检查)惰性执行:首次 {@link #validate} 或 {@link #create}
 * 时触发,结果缓存复用,避免每次创建 client 都 spawn Python 进程检查依赖。
 */
public class DefaultTelegramClientFactory implements TelegramClientFactory {
    private static final List<String> WORKER_RESOURCES = List.of(
            "telegram-python-worker/main.py",
            "telegram-python-worker/telegram_worker/__init__.py",
            "telegram-python-worker/telegram_worker/protocol.py",
            "telegram-python-worker/telegram_worker/security.py",
            "telegram-python-worker/telegram_worker/proxy.py",
            "telegram-python-worker/telegram_worker/messages.py",
            "telegram-python-worker/telegram_worker/worker.py"
    );

    private final TelegramClientProperties clientProperties;
    private final PythonTelegramRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;

    // bootstrap 结果缓存(惰性初始化,由 bootstrapIfNeeded 写入)
    private volatile boolean bootstrapped;
    private volatile String bootstrapError;  // "" 通过; 非空 错误信息
    private volatile Path resolvedWorkerScript;

    public DefaultTelegramClientFactory(TelegramClientProperties clientProperties,
                                        PythonTelegramRuntimeProperties runtimeProperties,
                                        ObjectMapper objectMapper) {
        this.clientProperties = Objects.requireNonNull(clientProperties, "clientProperties");
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String validate() {
        bootstrapIfNeeded();
        return bootstrapError == null || bootstrapError.isBlank() ? null : bootstrapError;
    }

    @Override
    public TelegramClient create(TelegramAccountConfig accountConfig,
                                 Consumer<TelegramConnectionStatus> statusListener) {
        Objects.requireNonNull(accountConfig, "accountConfig");
        var accountDir = accountDirectory(accountConfig.accountId());
        try {
            Files.createDirectories(accountDir);
        } catch (IOException e) {
            throw new IllegalStateException("Telegram account data-dir is not writable: " + accountDir, e);
        }
        bootstrapIfNeeded();
        var clientConfig = new TelegramClientConfig(
                accountConfig.accountId(),
                accountConfig.displayName(),
                accountConfig.phoneNumber(),
                clientProperties.getApiId(),
                clientProperties.getApiHash(),
                accountDir,
                accountConfig.proxies()
        );
        var session = new SubprocessTelegramSession(objectMapper, newSessionSanitizer(clientConfig));
        var sessionConfig = new TelegramSessionConfig(
                runtimeProperties.getExecutable(),
                resolvedWorkerScript,
                workerDirectory(),
                dataDirectory(),
                runtimeProperties.getExtraArgs(),
                Map.of(),
                toSessionProxies(clientConfig.proxies()),
                Duration.ofSeconds(10),
                Duration.ofSeconds(4)
        );
        return new DefaultTelegramClient(session, clientConfig, sessionConfig, objectMapper, statusListener);
    }

    // ---- worker bootstrap(惰性,仅执行一次) ----

    private synchronized void bootstrapIfNeeded() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        bootstrapError = doBootstrap();
    }

    private String doBootstrap() {
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
            return "";
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
        if (runtimeProperties.getRequiredModules().isEmpty()) {
            return "";
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
                return "";
            }
            var missing = output.isBlank() ? String.join(",", runtimeProperties.getRequiredModules()) : output;
            return "Missing Python modules: " + missing
                    + ". Install dependencies before starting real Telegram mode, for example: "
                    + runtimeProperties.getExecutable() + " -m pip install pyrogram tgcrypto";
        } catch (IOException e) {
            return "Failed to run Python executable '" + runtimeProperties.getExecutable()
                    + "': " + safeError(e)
                    + ". Install Python 3 or set telegram.client.python.executable.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while checking Python dependencies";
        }
    }

    // ---- 内部装配辅助 ----

    private List<TelegramSessionProxyConfig> toSessionProxies(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return List.of();
        }
        return proxies.stream()
                .filter(ProxyConfig::enabled)
                .map(proxy -> new TelegramSessionProxyConfig(
                        proxy.id(), proxy.protocol().name(), proxy.host(), proxy.port(),
                        proxy.username(), proxy.password()))
                .toList();
    }

    /** session 级日志脱敏:apiHash + 账号代理密码(stderr 一般不含代理密码,此处做防御性脱敏)。 */
    private Function<String, String> newSessionSanitizer(TelegramClientConfig config) {
        var apiHash = config.apiHash();
        var proxyPasswords = config.proxies().stream()
                .map(ProxyConfig::password)
                .filter(p -> p != null && !p.isBlank())
                .toList();
        return value -> {
            if (value == null) {
                return "";
            }
            var output = value;
            if (apiHash != null && !apiHash.isBlank()) {
                output = output.replace(apiHash, "******");
            }
            for (var password : proxyPasswords) {
                output = output.replace(password, "******");
            }
            return output;
        };
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

    private String safeError(Throwable error) {
        var message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
