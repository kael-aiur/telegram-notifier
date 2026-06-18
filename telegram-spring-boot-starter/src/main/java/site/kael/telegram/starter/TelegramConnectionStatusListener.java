package site.kael.telegram.starter;

@FunctionalInterface
public interface TelegramConnectionStatusListener {
    void onStatus(TelegramConnectionStatus status);
}
