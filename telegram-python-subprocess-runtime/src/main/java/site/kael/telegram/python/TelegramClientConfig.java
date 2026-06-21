package site.kael.telegram.python;

import site.kael.telegram.starter.ProxyConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * 单账号 {@link TelegramClient} 的业务配置:只承载业务字段,不包含任何底层 session 概念。
 *
 * <p>进程级配置(executable/workerScript/workingDirectory 等)由 {@code TelegramClientFactory} 持有,
 * 在 {@code create()} 时与底层 session 一起注入 client 实现内部,不通过本配置外泄。
 *
 * @param accountId    账号 id
 * @param displayName  账号显示名
 * @param phoneNumber  手机号(进入 start 命令,并在 WAIT_PHONE 时由 submit 提交)
 * @param apiId        Telegram api id
 * @param apiHash      Telegram api hash(敏感)
 * @param dataDir      账号数据目录(承载 session 文件)
 * @param proxies      账号绑定的代理链(业务形态,按优先级排序)
 */
public record TelegramClientConfig(
        long accountId,
        String displayName,
        String phoneNumber,
        int apiId,
        String apiHash,
        Path dataDir,
        List<ProxyConfig> proxies
) {
    public TelegramClientConfig {
        displayName = displayName == null ? "" : displayName;
        phoneNumber = phoneNumber == null ? "" : phoneNumber;
        apiHash = apiHash == null ? "" : apiHash;
        proxies = proxies == null ? List.of() : List.copyOf(proxies);
    }
}
