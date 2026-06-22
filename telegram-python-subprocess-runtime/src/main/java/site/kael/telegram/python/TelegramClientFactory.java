package site.kael.telegram.python;

import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramConnectionStatus;

import java.util.function.Consumer;

/**
 * 单账号 {@link TelegramClient} 的装配工厂,封装底层 session 创建、进程级配置组装与 worker bootstrap,
 * 使上层(manager)无需感知任何 session 概念。
 *
 * <p>本接口是 runtime 模块对 manager 的边界。其方法签名 MUST NOT 暴露
 * {@code TelegramSession}/{@code TelegramSessionConfig}/{@code TelegramSessionProxyConfig}/
 * {@code SubprocessTelegramSession} 等底层类型——这些是 runtime 模块的实现细节,
 * 仅在工厂与 client 实现内部使用。
 */
public interface TelegramClientFactory {

    /**
     * 预检 worker bootstrap 与全局配置(apiId/apiHash、dataDir 可写性、worker 脚本、Python 依赖),
     * 返回错误字符串;返回 {@code null} 表示通过。供 manager 在 {@code start} 时据以返回
     * {@code ERROR} {@link TelegramConnectionStatus} 而非抛异常。bootstrap 仅在首次调用时执行,结果缓存。
     */
    String validate();

    /**
     * 按账号业务配置装配一个单账号 client。进程级配置与底层 session 在本方法内部创建,
     * 不对调用方暴露任何 session 类型。
     *
     * @param accountConfig 账号业务配置(含代理链等业务字段)
     * @param statusListener 授权状态变更回调(供 manager 扇出),可为 null
     * @return 就绪的 {@link TelegramClient}
     */
    TelegramClient create(TelegramAccountConfig accountConfig, Consumer<TelegramConnectionStatus> statusListener);
}
