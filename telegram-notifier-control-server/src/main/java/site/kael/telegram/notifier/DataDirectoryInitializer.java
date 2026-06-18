package site.kael.telegram.notifier;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

public class DataDirectoryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        var env = applicationContext.getEnvironment();
        var dataDir = env.getProperty("telegram-notifier.data-dir", "./data");
        try {
            Files.createDirectories(Path.of(dataDir));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create telegram-notifier data directory: " + dataDir, e);
        }
    }
}
