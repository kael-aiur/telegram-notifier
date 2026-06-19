package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.ProxyProtocol;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramClientProperties;
import site.kael.telegram.starter.TelegramMessageEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PythonSubprocessTelegramAccountSessionManagerTest {
    @Test
    void reportsMissingPythonModules() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var manager = manager(dataDir, null, null, List.of("definitely_missing_module_for_telegram_notifier"));

        var status = manager.start(config());

        assertThat(status.authorizationState()).isEqualTo(AuthorizationState.ERROR);
        assertThat(status.errorMessage()).contains("Missing Python modules");
        manager.close();
    }

    @Test
    void extractsBundledWorkerResources() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var manager = manager(dataDir, null, null, List.of());

        manager.start(config());

        assertThat(dataDir.resolve("runtime/python-worker/main.py")).isRegularFile();
        assertThat(dataDir.resolve("runtime/python-worker/telegram_worker/security.py")).isRegularFile();
        assertThat(dataDir.resolve("runtime/python-worker/telegram_worker/proxy.py")).isRegularFile();
        assertThat(dataDir.resolve("runtime/python-worker/telegram_worker/messages.py")).isRegularFile();
        manager.close();
    }

    @Test
    void parsesFakeWorkerStatusMessageAndErrorEvents() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, """
                import json
                import sys

                for line in sys.stdin:
                    command = json.loads(line)
                    account_id = command.get("accountId", 0)
                    if command["type"] == "start":
                        print(json.dumps({"type":"status","accountId":account_id,"state":"READY","activeProxyId":9}), flush=True)
                        print(json.dumps({"type":"message","accountId":account_id,"chatId":10,"chatTitle":"Ops","chatType":"group","senderId":20,"senderName":"Ada","senderUsername":"ada","receivedAt":"2026-06-19T01:02:03Z","text":"hello"}), flush=True)
                    elif command["type"] == "submit_code":
                        print(json.dumps({"type":"error","accountId":account_id,"message":"proxy password=secret failed"}), flush=True)
                    elif command["type"] == "stop":
                        print(json.dumps({"type":"status","accountId":account_id,"state":"LOGGED_OUT"}), flush=True)
                        break
                """);

        var runtime = new PythonTelegramRuntimeProperties();
        runtime.setWorkerScript(script);
        runtime.setWorkingDirectory(workerDir);
        runtime.setRequiredModules(List.of());
        var client = clientProperties(dataDir);
        var manager = new PythonSubprocessTelegramAccountSessionManager(client, runtime, new ObjectMapper());
        var statuses = new CopyOnWriteArrayList<AuthorizationState>();
        var messages = new CopyOnWriteArrayList<TelegramMessageEvent>();
        manager.subscribeStatus(status -> statuses.add(status.authorizationState()));
        manager.subscribe(messages::add);

        manager.start(config());
        await(() -> statuses.contains(AuthorizationState.READY));
        await(() -> !messages.isEmpty());
        manager.submitCode(1L, "12345");
        await(() -> manager.status(1L).authorizationState() == AuthorizationState.ERROR);

        assertThat(messages.getFirst().text()).isEqualTo("hello");
        assertThat(manager.status(1L).errorMessage()).doesNotContain("secret");
        assertThat(manager.status(1L).errorMessage()).contains("******");
        manager.close();
    }

    @Test
    void startsWorkerFromRelativePaths() throws Exception {
        var relativeDataDir = Path.of("target", "relative-python-worker-test-" + System.nanoTime());
        var relativeWorkerDir = relativeDataDir.resolve("fake-worker");
        Files.createDirectories(relativeWorkerDir);
        var script = relativeWorkerDir.resolve("worker.py");
        Files.writeString(script, """
                import json
                import sys

                for line in sys.stdin:
                    command = json.loads(line)
                    if command["type"] == "start":
                        print(json.dumps({"type":"status","accountId":command.get("accountId", 0),"state":"WAIT_CODE"}), flush=True)
                    elif command["type"] == "stop":
                        break
                """);
        var manager = manager(relativeDataDir, script, relativeWorkerDir, List.of());
        var statuses = new CopyOnWriteArrayList<AuthorizationState>();
        manager.subscribeStatus(status -> statuses.add(status.authorizationState()));

        manager.start(config());
        await(() -> statuses.contains(AuthorizationState.WAIT_CODE));

        manager.close();
    }

    @Test
    void reportsWorkerProcessExitInsteadOfKeepingOptimisticWaitCode() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker-exit"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, "import sys\nprint('diagnostic reason', file=sys.stderr, flush=True)\nsys.exit(7)\n");
        var manager = manager(dataDir, script, workerDir, List.of());

        manager.start(config());
        await(() -> manager.status(1L).authorizationState() == AuthorizationState.ERROR);

        assertThat(manager.status(1L).errorMessage()).contains("Python worker exited unexpectedly with code 7");
        assertThat(manager.status(1L).errorMessage()).contains("diagnostic reason");
        manager.close();
    }

    private PythonSubprocessTelegramAccountSessionManager manager(java.nio.file.Path dataDir,
                                                                  java.nio.file.Path workerScript,
                                                                  java.nio.file.Path workingDirectory,
                                                                  List<String> requiredModules) {
        var runtime = new PythonTelegramRuntimeProperties();
        runtime.setWorkerScript(workerScript);
        runtime.setWorkingDirectory(workingDirectory);
        runtime.setRequiredModules(requiredModules);
        return new PythonSubprocessTelegramAccountSessionManager(clientProperties(dataDir), runtime, new ObjectMapper());
    }

    private TelegramClientProperties clientProperties(java.nio.file.Path dataDir) {
        var properties = new TelegramClientProperties();
        properties.setApiId(12345);
        properties.setApiHash("hash-secret");
        properties.setDataDir(dataDir);
        return properties;
    }

    private TelegramAccountConfig config() {
        return new TelegramAccountConfig(
                1L,
                "main",
                "+100000",
                Duration.ofSeconds(30),
                Duration.ofSeconds(120),
                List.of(new ProxyConfig(9L, ProxyProtocol.SOCKS5, "127.0.0.1", 1080, "u", "secret", true))
        );
    }

    private void await(Check check) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(check.ok()).isTrue();
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
