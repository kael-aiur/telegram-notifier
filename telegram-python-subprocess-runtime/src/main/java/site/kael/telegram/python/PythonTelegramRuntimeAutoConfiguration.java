package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramClientProperties;

@AutoConfiguration
@EnableConfigurationProperties({TelegramClientProperties.class, PythonTelegramRuntimeProperties.class})
public class PythonTelegramRuntimeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper telegramPythonRuntimeObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(TelegramAccountSessionManager.class)
    @ConditionalOnProperty(prefix = "telegram.client", name = "mode", havingValue = "PYTHON_SUBPROCESS")
    TelegramAccountSessionManager pythonTelegramAccountSessionManager(
            TelegramClientProperties clientProperties,
            PythonTelegramRuntimeProperties runtimeProperties,
            ObjectMapper objectMapper
    ) {
        return new PythonSubprocessTelegramAccountSessionManager(clientProperties, runtimeProperties, objectMapper);
    }
}
