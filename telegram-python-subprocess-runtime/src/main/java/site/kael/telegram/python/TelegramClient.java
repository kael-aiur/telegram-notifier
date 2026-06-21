package site.kael.telegram.python;

import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramMessage;

import java.util.List;

/**
 * 单账号 Telegram 业务能力层,把底层 {@link TelegramSession} 包装成完整的 Telegram 业务对象。
 *
 * <p>本接口只暴露业务能力,不包含任何调度或轮询策略。上层(getState 之外的)操作会按需懒启动
 * Python 子进程,无需显式调用进程启动逻辑。
 */
public interface TelegramClient extends AutoCloseable {

    /** 当前账号的业务授权状态。纯查询,不触发子进程启动。 */
    AuthorizationState getState();

    /**
     * 进入/继续登录流程:按需启动子进程并发送 start 命令,返回与 client 共享状态的 {@link LoginFlow}。
     */
    LoginFlow login();

    /**
     * 读取指定会话的未读消息(仅 peek,不标记已读),返回完整元数据,时间为系统时区 {@code LocalDateTime}。
     */
    List<TelegramMessage> peekUnreadMessage(long chatId);

    /** 更新代理链并重启会话,使新代理生效,授权状态机尽量延续。 */
    void updateProxies(List<TelegramSessionProxyConfig> proxies);

    /** 显式立即启动并连接(供注册表 eager start 使用)。 */
    void start();

    /** 释放资源、停止子进程。 */
    @Override
    void close();
}
