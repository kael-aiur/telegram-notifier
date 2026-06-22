package site.kael.telegram.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTelegramClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static TelegramClientConfig config() {
        return new TelegramClientConfig(1L, "acct", "+8613800000000", 123456,
                "api-hash-secret", Path.of("/tmp/account-1"), List.of());
    }

    /** 进程级 session 配置,仅在测试内部用于构造 client(测试作为 runtime 模块内部消费者可使用 session 类型)。 */
    private static TelegramSessionConfig sessionConfig() {
        return new TelegramSessionConfig(
                "python3", Path.of("/tmp/worker.py"), null, null,
                List.of(), Map.of(), List.of(), null, null);
    }

    private StubSession newSession(Consumer<Command> responder) {
        return new StubSession(mapper, responder);
    }

    /** 默认响应:start→WAIT_CODE,submit_code→READY,fetch_unread→两条消息+reply。 */
    private void defaultResponder(StubSession session) {
        session.responder = cmd -> {
            switch (cmd.command) {
                case "start" -> session.emit(statusReply(mapper, cmd.inputId, "WAIT_CODE"));
                case "submit_phone" -> session.emit(statusReply(mapper, cmd.inputId, "WAIT_CODE"));
                case "submit_code" -> session.emit(statusReply(mapper, cmd.inputId, "READY"));
                case "submit_password" -> session.emit(statusReply(mapper, cmd.inputId, "READY"));
                case "fetch_unread" -> {
                    session.emit(message(mapper, cmd.inputId, 100L, 9001L, "first", "2026-06-19T12:00:00Z"));
                    session.emit(message(mapper, cmd.inputId, 100L, 9002L, "second", "2026-06-19T12:01:00Z"));
                    session.emit(doneReply(mapper, cmd.inputId));
                }
                default -> session.emit(doneReply(mapper, cmd.inputId));
            }
        };
    }

    @Test
    void initialStateIsLoggedOutBeforeAnyOperation() {
        var client = new DefaultTelegramClient(newSession(c -> {}), config(), sessionConfig(), mapper);
        assertEquals(AuthorizationState.LOGGED_OUT, client.getState());
    }

    @Test
    void loginSendsStartAndDrivesSubmitByCurrentState() throws Exception {
        var session = newSession(c -> {});
        defaultResponder(session);
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);

        var flow = client.login();
        assertEquals(AuthorizationState.WAIT_CODE, flow.getState());

        // start 命令携带手机号与 apiId 等业务字段
        var startCmd = session.sent.stream().filter(c -> c.command.equals("start")).findFirst().orElseThrow();
        assertEquals("+8613800000000", startCmd.fields.get("phoneNumber"));
        assertEquals(123456, startCmd.fields.get("apiId"));

        // READY 后再 submit 应报错
        var next = flow.submit("12345");
        assertEquals(AuthorizationState.READY, next);
        assertEquals(AuthorizationState.READY, client.getState());
        assertThrows(IllegalStateException.class, () -> flow.submit("anything"));
    }

    @Test
    void ensureRunningStartsSessionExactlyOnceUnderConcurrency() throws Exception {
        var session = newSession(c -> {});
        defaultResponder(session);
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        client.start();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, session.startCount.get(), "session.start must be called exactly once");
        assertEquals(1, session.sent.stream().filter(c -> c.command.equals("start")).count(),
                "start command must be sent exactly once");
    }

    @Test
    void processLevelFailureTriggersRestartAndReconnect() {
        var session = newSession(c -> {});
        defaultResponder(session);
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);

        client.login();
        assertEquals(1, session.startCount.get());
        assertEquals(1, session.sent.stream().filter(c -> c.command.equals("start")).count());

        // 模拟子进程崩溃:session 进入 FAILED(授权状态可能仍为上次的 READY/WAIT_CODE)
        session.status = SessionStatus.FAILED;

        // 下次操作自动重启并重连(重发 start)
        client.peekUnreadMessage(100L);
        assertEquals(2, session.startCount.get(), "ensureRunning must restart the subprocess after failure");
        assertEquals(2, session.sent.stream().filter(c -> c.command.equals("start")).count(),
                "client must re-send start after a process restart");
    }

    @Test
    void peekUnreadCollectsScopedMessagesUntilReply() {
        var session = newSession(c -> {});
        defaultResponder(session);
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);

        List<TelegramMessage> unread = client.peekUnreadMessage(100L);
        assertEquals(2, unread.size());

        var first = unread.get(0);
        assertEquals(100L, first.chatId());
        assertEquals(9001L, first.messageId());
        assertEquals("first", first.text());

        var expectedAt = Instant.parse("2026-06-19T12:00:00Z")
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        assertEquals(expectedAt, first.receivedAt());
    }

    @Test
    void emptyUnreadReturnsEmptyList() {
        var session = newSession(c -> {});
        session.responder = cmd -> session.emit(doneReply(mapper, cmd.inputId));
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);

        List<TelegramMessage> unread = client.peekUnreadMessage(100L);
        assertTrue(unread.isEmpty());
    }

    @Test
    void realTimeMessageIsLoggedAsMetadataOnlyAndNotCollected() throws Exception {
        var session = newSession(c -> {});
        defaultResponder(session);
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);

        var logger = Logger.getLogger(DefaultTelegramClient.class.getName());
        var recorder = new RecordingHandler();
        recorder.setLevel(Level.ALL);
        logger.addHandler(recorder);
        try {
            // 实时消息:无 replyInputId(或关联不到进行中的 peek)。文本含敏感内容。
            session.emit(realtimeMessage(mapper, 100L, 7000L, "SECRET_BODY_TEXT", "2026-06-19T12:05:00Z"));
        } finally {
            logger.removeHandler(recorder);
        }

        String captured = String.join(" ", recorder.records);
        assertTrue(captured.contains("chatId=100"), "metadata chatId should be logged");
        assertTrue(captured.contains("senderId=200"), "metadata senderId should be logged");
        assertFalse(captured.contains("SECRET_BODY_TEXT"), "message text must never appear in logs");

        // 且不应进入任何 peek 结果(没有进行中的 peek)
        List<TelegramMessage> unread = client.peekUnreadMessage(100L);
        assertFalse(unread.stream().anyMatch(m -> m.messageId() == 7000L),
                "real-time message must not leak into a later peek result");
    }

    @Test
    void closeStopsSessionAndUnblocksWaiters() {
        var session = newSession(c -> {});
        defaultResponder(session);
        var client = new DefaultTelegramClient(session, config(), sessionConfig(), mapper);
        client.login();
        client.close();
        assertEquals(SessionStatus.STOPPED, session.status);
        // 关闭后再操作应抛异常
        assertThrows(IllegalStateException.class, client::login);
    }

    // ---- 协议 envelope 构造 helper ----

    private static String statusReply(ObjectMapper mapper, String inputId, String state) {
        var content = mapper.createObjectNode();
        var status = content.putObject("result").putObject("status");
        status.put("accountId", 1);
        status.put("state", state);
        return TelegramProtocolEnvelope.output(mapper, "py-" + inputId, "reply", inputId, content);
    }

    private static String doneReply(ObjectMapper mapper, String inputId) {
        var content = mapper.createObjectNode();
        content.putObject("result").put("count", 2).put("hasMore", false);
        return TelegramProtocolEnvelope.output(mapper, "py-" + inputId, "reply", inputId, content);
    }

    private static ObjectNode messageContent(ObjectMapper mapper, long chatId, long messageId, String text, String receivedAt) {
        var content = mapper.createObjectNode();
        content.put("accountId", 1);
        content.put("chatId", chatId);
        content.put("messageId", messageId);
        content.put("chatTitle", "chat");
        content.put("chatType", "telegram");
        content.put("senderId", 200);
        content.put("senderName", "sender");
        content.put("senderUsername", "sender");
        content.put("receivedAt", receivedAt);
        content.put("text", text);
        return content;
    }

    private static String message(ObjectMapper mapper, String inputId, long chatId, long messageId, String text, String receivedAt) {
        return TelegramProtocolEnvelope.output(mapper, "py-msg-" + messageId, "message", inputId,
                messageContent(mapper, chatId, messageId, text, receivedAt));
    }

    private static String realtimeMessage(ObjectMapper mapper, long chatId, long messageId, String text, String receivedAt) {
        return TelegramProtocolEnvelope.output(mapper, "py-msg-" + messageId, "message", null,
                messageContent(mapper, chatId, messageId, text, receivedAt));
    }

    // ---- stub session ----

    static final class Command {
        final String inputId;
        final String command;
        final Map<String, Object> fields;

        Command(String inputId, String command, Map<String, Object> fields) {
            this.inputId = inputId;
            this.command = command;
            this.fields = fields;
        }
    }

    static final class StubSession implements TelegramSession {
        private final ObjectMapper mapper;
        private final SubmissionPublisher<String> publisher = new SubmissionPublisher<>(Runnable::run, 256);
        final List<Command> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger startCount = new AtomicInteger();
        volatile SessionStatus status = SessionStatus.NEW;
        volatile Consumer<Command> responder;

        StubSession(ObjectMapper mapper, Consumer<Command> responder) {
            this.mapper = mapper;
            this.responder = responder;
        }

        @Override
        public void start(TelegramSessionConfig cfg) {
            startCount.incrementAndGet();
            status = SessionStatus.RUNNING;
        }

        @Override
        public void send(String str) {
            try {
                var env = mapper.readTree(str);
                var content = (ObjectNode) env.get("content");
                var command = content.get("command").asText();
                var inputId = env.get("id").asText();
                var fields = mapper.convertValue(content, Map.class);
                var cmd = new Command(inputId, command, (Map<String, Object>) fields);
                sent.add(cmd);
                if (responder != null) {
                    responder.accept(cmd);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Flow.Publisher<String> getPublisher() {
            return publisher;
        }

        @Override
        public void stop() {
            status = SessionStatus.STOPPED;
        }

        @Override
        public SessionStatus getStatus() {
            return status;
        }

        void emit(String envelopeJson) {
            publisher.submit(envelopeJson);
        }
    }

    static final class RecordingHandler extends Handler {
        final List<String> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            setLevel(Level.OFF);
        }
    }

}
