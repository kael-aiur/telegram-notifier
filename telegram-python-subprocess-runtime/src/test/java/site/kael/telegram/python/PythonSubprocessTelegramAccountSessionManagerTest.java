package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.ProxyProtocol;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramClientProperties;
import site.kael.telegram.starter.TelegramMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
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
        var manager = manager(dataDir, null, null, List.of("definitely_missing_module_for_telegram_notifier"));

        var status = manager.start(config());

        assertThat(status.authorizationState()).isEqualTo(AuthorizationState.ERROR);
        assertThat(status.errorMessage()).contains("Missing Python modules");
        assertThat(dataDir.resolve("runtime/python-worker/main.py")).isRegularFile();
        assertThat(dataDir.resolve("runtime/python-worker/telegram_worker/security.py")).isRegularFile();
        assertThat(dataDir.resolve("runtime/python-worker/telegram_worker/proxy.py")).isRegularFile();
        assertThat(dataDir.resolve("runtime/python-worker/telegram_worker/messages.py")).isRegularFile();
        manager.close();
    }

    @Test
    void sessionPublishesJsonStdoutAndSanitizedStderr() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-session-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-session"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, """
                import json
                import sys

                print(json.dumps({"id":"py-log-ready","type":"log","content":{"message":"ready"}}), flush=True)
                for line in sys.stdin:
                    envelope = json.loads(line)
                    print(json.dumps({"id":"py-reply-1","type":"reply","replyInputId":envelope["id"],"content":{"ok":True}}), flush=True)
                    print("stderr contains secret", file=sys.stderr, flush=True)
                    break
                """);
        var objectMapper = new ObjectMapper();
        var session = new SubprocessTelegramSession(objectMapper, value -> value.replace("secret", "******"));
        var outputs = new CopyOnWriteArrayList<String>();
        session.getPublisher().subscribe(collectingSubscriber(outputs));

        session.start(new TelegramSessionConfig(
                "python3",
                script,
                workerDir,
                dataDir,
                List.of(),
                null,
                List.of(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        ));
        session.send(TelegramProtocolEnvelope.input(objectMapper, "java-1", java.util.Map.of("command", "ping")));
        await(() -> outputs.stream().anyMatch(line -> line.contains("replyInputId")));
        await(() -> outputs.stream().anyMatch(line -> line.contains("stderr contains ******")));

        assertThat(session.getStatus()).isIn(SessionStatus.RUNNING, SessionStatus.FAILED);
        assertThat(outputs).allSatisfy(line -> assertThat(objectMapper.readTree(line).hasNonNull("type")).isTrue());
        assertThat(String.join("\n", outputs)).doesNotContain("secret");
        session.stop();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.STOPPED);
    }

    @Test
    void parsesFakeWorkerReplyAndSanitizedErrorEvents() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, fakeEnvelopeWorker());

        var runtime = new PythonTelegramRuntimeProperties();
        runtime.setWorkerScript(script);
        runtime.setWorkingDirectory(workerDir);
        runtime.setRequiredModules(List.of());
        var client = clientProperties(dataDir);
        var factory = new DefaultTelegramClientFactory(client, runtime, new ObjectMapper());
        var manager = new PythonSubprocessTelegramAccountSessionManager(factory);
        var statuses = new CopyOnWriteArrayList<AuthorizationState>();
        manager.subscribeStatus(status -> statuses.add(status.authorizationState()));

        manager.start(config());
        await(() -> manager.status(1L).authorizationState() == AuthorizationState.WAIT_CODE);
        manager.submitCode(1L, "12345");
        await(() -> manager.status(1L).authorizationState() == AuthorizationState.ERROR);

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

                def reply(input_id, account_id, state):
                    print(json.dumps({"id":"py-reply-" + input_id,"type":"reply","replyInputId":input_id,"content":{"ok":True,"result":{"status":{"accountId":account_id,"state":state}}}}), flush=True)

                for line in sys.stdin:
                    envelope = json.loads(line)
                    command = envelope["content"]
                    if command["command"] == "start":
                        reply(envelope["id"], command.get("accountId", 0), "WAIT_CODE")
                    elif command["command"] == "stop":
                        reply(envelope["id"], command.get("accountId", 0), "LOGGED_OUT")
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
    void startReturnsStatusProducedByWorkerInsteadOfOptimisticPreviousStatus() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker-delayed"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, """
                import json
                import sys
                import time

                def reply(input_id, account_id, state):
                    print(json.dumps({"id":"py-reply-" + input_id,"type":"reply","replyInputId":input_id,"content":{"ok":True,"result":{"status":{"accountId":account_id,"state":state}}}}), flush=True)

                for line in sys.stdin:
                    envelope = json.loads(line)
                    command = envelope["content"]
                    if command["command"] == "start":
                        time.sleep(0.25)
                        reply(envelope["id"], command.get("accountId", 0), "WAIT_CODE")
                    elif command["command"] == "stop":
                        reply(envelope["id"], command.get("accountId", 0), "LOGGED_OUT")
                        break
                """);
        var manager = manager(dataDir, script, workerDir, List.of());

        var startedAt = System.nanoTime();
        var status = manager.start(config());
        var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(status.authorizationState()).isEqualTo(AuthorizationState.WAIT_CODE);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(200);
        manager.close();
    }

    @Test
    void waitsForMatchingReplyInputId() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker-reply-id"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, """
                import json
                import sys

                for line in sys.stdin:
                    envelope = json.loads(line)
                    command = envelope["content"]
                    if command["command"] == "start":
                        print(json.dumps({"id":"py-reply-other","type":"reply","replyInputId":"other-input","content":{"ok":True,"result":{"status":{"accountId":1,"state":"ERROR","errorMessage":"wrong reply"}}}}), flush=True)
                        print(json.dumps({"id":"py-reply-real","type":"reply","replyInputId":envelope["id"],"content":{"ok":True,"result":{"status":{"accountId":command.get("accountId", 0),"state":"READY"}}}}), flush=True)
                    elif command["command"] == "stop":
                        print(json.dumps({"id":"py-reply-stop","type":"reply","replyInputId":envelope["id"],"content":{"ok":True,"result":{"status":{"accountId":command.get("accountId", 0),"state":"LOGGED_OUT"}}}}), flush=True)
                        break
                """);
        var manager = manager(dataDir, script, workerDir, List.of());

        var status = manager.start(config());

        assertThat(status.authorizationState()).isEqualTo(AuthorizationState.READY);
        manager.close();
    }

    @Test
    void peeksUnreadMessagesForRequestedChat() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-python-runtime-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker-peek"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, fakePeekWorker());
        var manager = manager(dataDir, script, workerDir, List.of());

        manager.start(config());

        var messages = manager.peekUnreadMessages(1L, 10L);

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().chatId()).isEqualTo(10L);
        assertThat(messages.getFirst().messageId()).isEqualTo(99L);
        assertThat(messages.getFirst().text()).isEqualTo("hello");
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
        manager.close();
    }

    private String fakeEnvelopeWorker() {
        return """
                import json
                import sys

                def reply(input_id, account_id, state, active_proxy_id=None):
                    status = {"accountId": account_id, "state": state}
                    if active_proxy_id is not None:
                        status["activeProxyId"] = active_proxy_id
                    print(json.dumps({"id":"py-reply-" + input_id,"type":"reply","replyInputId":input_id,"content":{"ok":True,"result":{"status":status}}}), flush=True)

                for line in sys.stdin:
                    envelope = json.loads(line)
                    command = envelope["content"]
                    account_id = command.get("accountId", 0)
                    if command["command"] == "start":
                        reply(envelope["id"], account_id, "WAIT_CODE", 9)
                    elif command["command"] == "submit_code":
                        print(json.dumps({"id":"py-reply-error","type":"reply","replyInputId":envelope["id"],"content":{"ok":False,"error":{"message":"proxy password=secret failed"}}}), flush=True)
                    elif command["command"] == "stop":
                        reply(envelope["id"], account_id, "LOGGED_OUT")
                        break
                """;
    }

    private String fakePeekWorker() {
        return """
                import json
                import sys

                def reply(input_id, account_id, state):
                    print(json.dumps({"id":"py-reply-" + input_id,"type":"reply","replyInputId":input_id,"content":{"ok":True,"result":{"status":{"accountId":account_id,"state":state}}}}), flush=True)

                for line in sys.stdin:
                    envelope = json.loads(line)
                    command = envelope["content"]
                    account_id = command.get("accountId", 0)
                    if command["command"] == "start":
                        reply(envelope["id"], account_id, "READY")
                    elif command["command"] == "fetch_unread":
                        print(json.dumps({"id":"py-msg-peek","type":"message","replyInputId":envelope["id"],"content":{"accountId":account_id,"chatId":10,"messageId":99,"chatTitle":"Ops","chatType":"group","senderId":20,"senderName":"Ada","senderUsername":"ada","receivedAt":"2026-06-19T01:02:03Z","text":"hello"}}), flush=True)
                        print(json.dumps({"id":"py-reply-peek","type":"reply","replyInputId":envelope["id"],"content":{"ok":True,"result":{"count":1,"hasMore":False}}}), flush=True)
                    elif command["command"] == "stop":
                        reply(envelope["id"], account_id, "LOGGED_OUT")
                        break
                """;
    }

    private PythonSubprocessTelegramAccountSessionManager manager(Path dataDir,
                                                                  Path workerScript,
                                                                  Path workingDirectory,
                                                                  List<String> requiredModules) {
        var runtime = new PythonTelegramRuntimeProperties();
        runtime.setWorkerScript(workerScript);
        runtime.setWorkingDirectory(workingDirectory);
        runtime.setRequiredModules(requiredModules);
        var factory = new DefaultTelegramClientFactory(clientProperties(dataDir), runtime, new ObjectMapper());
        return new PythonSubprocessTelegramAccountSessionManager(factory);
    }

    private TelegramClientProperties clientProperties(Path dataDir) {
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

    private Flow.Subscriber<String> collectingSubscriber(CopyOnWriteArrayList<String> outputs) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                outputs.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        };
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
