package site.kael.telegram.python;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record TelegramSessionConfig(
        String executable,
        Path workerScript,
        Path workingDirectory,
        Path runtimeDataDirectory,
        List<String> extraArgs,
        Map<String, String> environment,
        List<TelegramSessionProxyConfig> proxies,
        Duration startupTimeout,
        Duration shutdownTimeout
) {
    public TelegramSessionConfig {
        executable = executable == null || executable.isBlank() ? "python3" : executable;
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        proxies = proxies == null ? List.of() : List.copyOf(proxies);
        startupTimeout = startupTimeout == null ? Duration.ofSeconds(10) : startupTimeout;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(4) : shutdownTimeout;
    }
}
