package site.kael.telegram.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(TelegramClientProperties.class)
public class TelegramStarterAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "telegram.client", name = "mode", havingValue = "MEMORY", matchIfMissing = true)
    TelegramAccountSessionManager telegramAccountSessionManager(TelegramClientProperties properties) {
        return new InMemoryTelegramAccountSessionManager();
    }
}
