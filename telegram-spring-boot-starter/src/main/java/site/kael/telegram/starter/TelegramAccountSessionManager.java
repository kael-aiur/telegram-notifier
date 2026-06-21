package site.kael.telegram.starter;

import java.util.List;

/**
 * 多账号 Telegram 会话管理边界,面向 control-server。
 *
 * <p>通知采用 pull 模型:上层通过 {@link #peekUnreadMessages(long, long)} 主动拉取未读消息,
 * 而不是订阅实时推送。status 变更仍以回调形式通过 {@link #subscribeStatus} 扇出。
 */
public interface TelegramAccountSessionManager {
    TelegramConnectionStatus start(TelegramAccountConfig config);

    TelegramConnectionStatus stop(long accountId);

    TelegramConnectionStatus submitPhone(long accountId, String phoneNumber);

    TelegramConnectionStatus submitCode(long accountId, String code);

    TelegramConnectionStatus submitPassword(long accountId, String password);

    TelegramConnectionStatus status(long accountId);

    void updateProxies(long accountId, List<ProxyConfig> proxies);

    /**
     * 读取指定账号、指定会话的未读消息(peek,不标记已读)。
     *
     * @return 未读消息列表;无未读或账号未就绪时返回空列表
     */
    List<TelegramMessage> peekUnreadMessages(long accountId, long chatId);

    void subscribeStatus(TelegramConnectionStatusListener listener);
}
