package site.kael.telegram.python;

import java.util.concurrent.Flow;

public interface TelegramSession extends AutoCloseable {
    void start(TelegramSessionConfig config);

    void send(String str);

    Flow.Publisher<String> getPublisher();

    void stop();

    SessionStatus getStatus();

    @Override
    default void close() {
        stop();
    }
}
