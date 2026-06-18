package site.kael.telegram.starter;

@FunctionalInterface
public interface TelegramMessageListener {
    void onMessage(TelegramMessageEvent event);
}
