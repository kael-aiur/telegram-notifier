package site.kael.telegram.python;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 单账号 {@link TelegramClient} 启动与运行所需的全部配置。
 *
 * <p>由注册表(manager)负责组装:业务字段来自账号与全局 client 属性,进程级配置复用
 * {@link TelegramSessionConfig}。client 只接收并使用本配置,不自行解析 apiId/dataDir 等来源。
 *
 * @param accountId    账号 id
 * @param displayName  账号显示名
 * @param phoneNumber  手机号(进入 start 命令,并在 WAIT_PHONE 时由 submit 提交)
 * @param apiId        Telegram api id
 * @param apiHash      Telegram api hash(敏感)
 * @param dataDir      账号数据目录(承载 session 文件)
 * @param sessionConfig Python 子进程运行配置
 */
public record TelegramClientConfig(
        long accountId,
        String displayName,
        String phoneNumber,
        int apiId,
        String apiHash,
        Path dataDir,
        TelegramSessionConfig sessionConfig
) {
    public TelegramClientConfig {
        displayName = displayName == null ? "" : displayName;
        phoneNumber = phoneNumber == null ? "" : phoneNumber;
        apiHash = apiHash == null ? "" : apiHash;
        Objects.requireNonNull(sessionConfig, "sessionConfig");
    }
}
