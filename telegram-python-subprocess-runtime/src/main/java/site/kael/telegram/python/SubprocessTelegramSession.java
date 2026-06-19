package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SubprocessTelegramSession implements TelegramSession {
    private static final Logger LOGGER = Logger.getLogger(SubprocessTelegramSession.class.getName());
    private static final String RUNTIME_CONFIG_ENV = "TELEGRAM_SESSION_CONFIG";

    private final ObjectMapper objectMapper;
    private final Function<String, String> sanitizer;
    private final SubmissionPublisher<String> publisher = new SubmissionPublisher<>();

    private volatile SessionStatus status = SessionStatus.NEW;
    private volatile TelegramSessionConfig config;
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile Thread stdoutReader;
    private volatile Thread stderrReader;
    private volatile Thread exitWatcher;
    private volatile boolean stopping;

    public SubprocessTelegramSession(ObjectMapper objectMapper, Function<String, String> sanitizer) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sanitizer = sanitizer == null ? Function.identity() : sanitizer;
    }

    @Override
    public synchronized void start(TelegramSessionConfig config) {
        if (status == SessionStatus.RUNNING || status == SessionStatus.STARTING) {
            throw new IllegalStateException("Telegram session is already running");
        }
        this.config = Objects.requireNonNull(config, "config");
        if (config.workerScript() == null) {
            throw new IllegalArgumentException("workerScript is required");
        }
        status = SessionStatus.STARTING;
        stopping = false;
        try {
            var command = new ArrayList<String>();
            command.add(config.executable());
            command.add(config.workerScript().toString());
            command.addAll(config.extraArgs());
            var builder = new ProcessBuilder(command);
            if (config.workingDirectory() != null) {
                builder.directory(config.workingDirectory().toFile());
            }
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().putAll(config.environment());
            builder.environment().put(RUNTIME_CONFIG_ENV, runtimeConfigJson(config));
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutReader = new Thread(this::readStdout, "telegram-python-session-stdout");
            stderrReader = new Thread(this::readStderr, "telegram-python-session-stderr");
            exitWatcher = new Thread(() -> watchProcessExit(process), "telegram-python-session-exit");
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            exitWatcher.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();
            exitWatcher.start();
            status = SessionStatus.RUNNING;
        } catch (IOException | RuntimeException e) {
            status = SessionStatus.FAILED;
            publishLog("Failed to start Python worker: " + safe(e));
            stopProcess(config.shutdownTimeout());
            throw new IllegalStateException("Failed to start Python worker: " + safe(e), e);
        }
    }

    @Override
    public synchronized void send(String str) {
        if (writer == null || status != SessionStatus.RUNNING) {
            throw new IllegalStateException("Python worker is not running");
        }
        try {
            var envelope = TelegramProtocolEnvelope.validate(objectMapper, str);
            var type = TelegramProtocolEnvelope.text(envelope, "type");
            if (!TelegramProtocolEnvelope.TYPE_INPUT.equals(type)) {
                throw new IllegalArgumentException("Java-to-Python envelope type must be input");
            }
            writer.write(str);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            status = SessionStatus.FAILED;
            publishLog("Failed to send command to Python worker: " + safe(e));
            throw new IllegalStateException("Failed to send command to Python worker: " + safe(e), e);
        }
    }

    @Override
    public Flow.Publisher<String> getPublisher() {
        return publisher;
    }

    @Override
    public synchronized void stop() {
        if (status == SessionStatus.STOPPED || status == SessionStatus.NEW) {
            return;
        }
        stopping = true;
        status = SessionStatus.STOPPING;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
        stopProcess(config == null ? Duration.ofSeconds(4) : config.shutdownTimeout());
        status = SessionStatus.STOPPED;
    }

    @Override
    public SessionStatus getStatus() {
        return status;
    }

    private void readStdout() {
        var activeProcess = process;
        if (activeProcess == null) {
            return;
        }
        try (var reader = new BufferedReader(new InputStreamReader(activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                publishStdout(line);
            }
        } catch (IOException e) {
            if (!stopping) {
                publishLog("Failed to read Python worker stdout: " + safe(e));
            }
        }
    }

    private void readStderr() {
        var activeProcess = process;
        if (activeProcess == null) {
            return;
        }
        try (var reader = new BufferedReader(new InputStreamReader(activeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                publishLog(line);
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to read Python worker stderr", e);
        }
    }

    private void watchProcessExit(Process watchedProcess) {
        try {
            var exitCode = watchedProcess.waitFor();
            if (process == watchedProcess) {
                process = null;
                writer = null;
                if (!stopping) {
                    status = SessionStatus.FAILED;
                    publishLog("Python worker exited unexpectedly with code " + exitCode);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publishStdout(String line) {
        try {
            TelegramProtocolEnvelope.validate(objectMapper, line);
            publisher.submit(line);
        } catch (Exception e) {
            publishLog("Invalid Python worker protocol output: " + safe(e));
        }
    }

    private void publishLog(String message) {
        var sanitized = sanitizer.apply(message == null ? "" : message);
        publisher.submit(TelegramProtocolEnvelope.log(objectMapper, sanitized));
    }

    private void stopProcess(Duration timeout) {
        var activeProcess = process;
        if (activeProcess == null) {
            return;
        }
        try {
            var halfTimeout = Math.max(1, timeout.toMillis() / 2);
            if (!activeProcess.waitFor(halfTimeout, TimeUnit.MILLISECONDS)) {
                activeProcess.destroy();
            }
            if (!activeProcess.waitFor(halfTimeout, TimeUnit.MILLISECONDS)) {
                activeProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            activeProcess.destroyForcibly();
        } finally {
            process = null;
        }
    }

    private String runtimeConfigJson(TelegramSessionConfig config) {
        var payload = new LinkedHashMap<String, Object>();
        if (config.runtimeDataDirectory() != null) {
            payload.put("runtimeDataDir", config.runtimeDataDirectory().toString());
        }
        payload.put("proxies", config.proxies().stream().map(proxy -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", proxy.id());
            value.put("protocol", proxy.protocol());
            value.put("host", proxy.host());
            value.put("port", proxy.port());
            value.put("username", proxy.username());
            value.put("password", proxy.password());
            return value;
        }).toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize runtime config", e);
        }
    }

    private String safe(Throwable error) {
        var message = error.getMessage();
        return sanitizer.apply(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }
}
