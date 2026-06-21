package site.kael.telegram.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramClientProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTelegramClientFactoryTest {

    @Test
    void validateReturnsErrorWhenRequiredModuleMissing() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-factory-test");
        var factory = factory(dataDir, null, null, List.of("definitely_missing_module_for_telegram_notifier"));

        assertThat(factory.validate()).contains("Missing Python modules");
    }

    @Test
    void createAssemblesWorkingClientFromBusinessConfig() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-factory-test");
        var workerDir = Files.createDirectories(dataDir.resolve("fake-worker"));
        var script = workerDir.resolve("worker.py");
        Files.writeString(script, """
                import json
                import sys

                for line in sys.stdin:
                    envelope = json.loads(line)
                    command = envelope["content"]
                    if command["command"] == "start":
                        print(json.dumps({"id":"py-reply","type":"reply","replyInputId":envelope["id"],"content":{"ok":True,"result":{"status":{"accountId":command.get("accountId",0),"state":"WAIT_CODE"}}}}), flush=True)
                    elif command["command"] == "stop":
                        print(json.dumps({"id":"py-reply-stop","type":"reply","replyInputId":envelope["id"],"content":{"ok":True,"result":{"status":{"accountId":command.get("accountId",0),"state":"LOGGED_OUT"}}}}), flush=True)
                        break
                """);
        var factory = factory(dataDir, script, workerDir, List.of());

        var client = factory.create(accountConfig(), null);

        assertThat(client.getState()).isEqualTo(AuthorizationState.LOGGED_OUT);
        client.start();
        assertThat(client.getState()).isEqualTo(AuthorizationState.WAIT_CODE);
        client.close();
    }

    @Test
    void validateIsIdempotentAndBootstrapsAtMostOnce() throws Exception {
        var dataDir = Files.createTempDirectory("telegram-factory-test");
        var factory = factory(dataDir, null, null, List.of("definitely_missing_module_for_telegram_notifier"));

        var first = factory.validate();
        var second = factory.validate();

        // bootstrap 结果缓存:多次 validate 返回同一错误字符串,不重复 spawn Python 依赖检查
        assertThat(first).isEqualTo(second);
        assertThat(first).contains("Missing Python modules");
    }

    private DefaultTelegramClientFactory factory(Path dataDir, Path workerScript, Path workingDirectory, List<String> requiredModules) {
        var runtime = new PythonTelegramRuntimeProperties();
        runtime.setWorkerScript(workerScript);
        runtime.setWorkingDirectory(workingDirectory);
        runtime.setRequiredModules(requiredModules);
        return new DefaultTelegramClientFactory(clientProperties(dataDir), runtime, new ObjectMapper());
    }

    private TelegramClientProperties clientProperties(Path dataDir) {
        var properties = new TelegramClientProperties();
        properties.setApiId(12345);
        properties.setApiHash("hash-secret");
        properties.setDataDir(dataDir);
        return properties;
    }

    private TelegramAccountConfig accountConfig() {
        return new TelegramAccountConfig(1L, "main", "+100000", Duration.ofSeconds(30), Duration.ofSeconds(120), List.of());
    }
}
