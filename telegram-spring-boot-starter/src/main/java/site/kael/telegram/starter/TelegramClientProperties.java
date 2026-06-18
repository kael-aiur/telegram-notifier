package site.kael.telegram.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "telegram.client")
public class TelegramClientProperties {
    private TelegramClientMode mode = TelegramClientMode.MEMORY;
    private Integer apiId;
    private String apiHash;
    private Path dataDir = Path.of("data", "telegram-client");

    public TelegramClientMode getMode() {
        return mode;
    }

    public void setMode(TelegramClientMode mode) {
        this.mode = mode == null ? TelegramClientMode.MEMORY : mode;
    }

    public Integer getApiId() {
        return apiId;
    }

    public void setApiId(Integer apiId) {
        this.apiId = apiId;
    }

    public String getApiHash() {
        return apiHash;
    }

    public void setApiHash(String apiHash) {
        this.apiHash = apiHash;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir == null ? Path.of("data", "telegram-client") : dataDir;
    }
}
