package site.kael.telegram.notifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ControlServerIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        var dir = Files.createTempDirectory("telegram-notifier-test");
        registry.add("telegram-notifier.data-dir", dir::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dir.resolve(UUID.randomUUID() + ".db"));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("delete from account_monitoring_logs");
        jdbc.execute("delete from account_proxies");
        jdbc.execute("delete from proxy_servers");
        jdbc.execute("delete from notified_telegram_messages");
        jdbc.execute("delete from delivery_stats");
        jdbc.execute("delete from rule_stats");
        jdbc.execute("delete from message_stats");
        jdbc.execute("delete from notification_rules");
        jdbc.execute("delete from push_channels");
        jdbc.execute("delete from telegram_accounts");
        jdbc.execute("delete from administrators");
        jdbc.execute("delete from sqlite_sequence");
    }

    @Test
    void bootstrapLoginAndProtectedApisWork() throws Exception {
        mvc.perform(get("/api/system/bootstrap-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAdminInit").value(true));

        mvc.perform(get("/settings/accounts"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        mvc.perform(post("/api/system/admin-init")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/system/admin-init")
                        .contentType("application/json")
                        .content("{\"username\":\"other\",\"password\":\"secret\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());

        var token = loginToken();
        mvc.perform(get("/api/accounts").header("X-Auth-Token", token))
                .andExpect(status().isOk());
    }

    @Test
    void accountLifecycleProxyBindingAndRulesDoNotPersistMessageBody() throws Exception {
        ensureAdmin();
        var token = loginToken();

        mvc.perform(post("/api/accounts")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"displayName":"main","phoneNumber":"+100000","enabled":true,"scanFrequencySeconds":30,"unreadAgeThresholdSeconds":120}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("main"));

        mvc.perform(post("/api/proxies")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"name":"local","protocol":"SOCKS5","host":"127.0.0.1","port":1080,"username":"u","password":"p","enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value("******"));

        mvc.perform(put("/api/accounts/1/proxies")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("{\"proxyIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(1));

        mvc.perform(get("/api/accounts/1").header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationState").value("LOGGED_OUT"))
                .andExpect(jsonPath("$.activeProxyId").value(1));

        mvc.perform(post("/api/accounts/1/start").header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationState").value("WAIT_PHONE"))
                .andExpect(jsonPath("$.activeProxyId").value(1));

        mvc.perform(post("/api/accounts/1/login/phone")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("{\"value\":\"+100000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationState").value("WAIT_CODE"));

        mvc.perform(post("/api/accounts/1/login/code")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("{\"value\":\"12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationState").value("READY"));

        mvc.perform(post("/api/channels")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"name":"bark","type":"BARK","enabled":false,"config":{"serverUrl":"https://api.day.app","deviceKey":"device"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.deviceKey").value("******"));

        mvc.perform(post("/api/accounts/1/rules")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"name":"server notice","enabled":true,"sourceLabel":"服务器","condition":{"field":"text","op":"contains","value":"alarm"},"template":"{{receivedAt}} 收到来自{{sourceLabel}}的通知消息","channelIds":[1]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/test/messages")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"chatTitle":"服务器","text":"alarm secret body"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/statistics").header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].message_count").value(1))
                .andExpect(jsonPath("$.rules[0].hit_count").value(1))
                .andExpect(jsonPath("$.deliveries[0].failure_count").value(1))
                .andExpect(content().string(not(containsString("alarm secret body"))));

        var persistedBodyHits = jdbc.queryForObject("""
                select
                    (select count(*) from notification_rules where condition_json like '%alarm secret body%' or template like '%alarm secret body%') +
                    (select count(*) from message_stats where bucket like '%alarm secret body%') +
                    (select count(*) from rule_stats where bucket like '%alarm secret body%') +
                    (select count(*) from delivery_stats where last_error like '%alarm secret body%')
                """, Integer.class);
        assertThat(persistedBodyHits).isZero();
    }

    @Test
    void unreadMessagesAreThresholdFilteredDeduplicatedAndDoNotPersistBody() throws Exception {
        ensureAdmin();
        var token = loginToken();
        var oldMessageTime = Instant.now().minusSeconds(120).toString();
        var recentMessageTime = Instant.now().toString();

        mvc.perform(post("/api/accounts")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"displayName":"main","phoneNumber":"+100000","enabled":true,"scanFrequencySeconds":30,"unreadAgeThresholdSeconds":60}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/channels")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"name":"bark","type":"BARK","enabled":false,"config":{"serverUrl":"https://api.day.app","deviceKey":"device"}}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/accounts/1/rules")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"name":"chat rule","enabled":true,"sourceLabel":"Telegram","condition":{"field":"chatId","op":"equals","value":"42"},"template":"{{messageId}} {{text}}","channelIds":[1]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/test/messages")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"chatId":42,"messageId":100,"chatTitle":"Ops","receivedAt":"%s","text":"alarm secret body"}
                                """.formatted(oldMessageTime)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from notified_telegram_messages where account_id = 1 and chat_id = 42 and message_id = 100", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select failure_count from delivery_stats where rule_id = 1 and channel_id = 1", Integer.class)).isEqualTo(1);

        mvc.perform(post("/api/test/messages")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"chatId":42,"messageId":100,"chatTitle":"Ops","receivedAt":"%s","text":"alarm secret body"}
                                """.formatted(oldMessageTime)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select failure_count from delivery_stats where rule_id = 1 and channel_id = 1", Integer.class)).isEqualTo(1);

        mvc.perform(post("/api/test/messages")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"chatId":42,"messageId":101,"chatTitle":"Ops","receivedAt":"%s","text":"alarm secret body"}
                                """.formatted(recentMessageTime)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/test/messages")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"chatId":99,"messageId":102,"chatTitle":"Other","receivedAt":"%s","text":"alarm secret body"}
                                """.formatted(oldMessageTime)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from notified_telegram_messages", Integer.class)).isEqualTo(1);

        mvc.perform(post("/api/test/messages")
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("""
                                {"accountId":1,"chatId":42,"messageId":101,"chatTitle":"Ops","receivedAt":"%s","text":"alarm secret body"}
                                """.formatted(oldMessageTime)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from notified_telegram_messages where account_id = 1 and chat_id = 42 and message_id = 101", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select failure_count from delivery_stats where rule_id = 1 and channel_id = 1", Integer.class)).isEqualTo(2);

        var persistedBodyHits = jdbc.queryForObject("""
                select
                    (select count(*) from notified_telegram_messages where notified_at like '%alarm secret body%') +
                    (select count(*) from message_stats where bucket like '%alarm secret body%') +
                    (select count(*) from rule_stats where bucket like '%alarm secret body%') +
                    (select count(*) from delivery_stats where last_error like '%alarm secret body%')
                """, Integer.class);
        assertThat(persistedBodyHits).isZero();
    }

    private void ensureAdmin() throws Exception {
        Integer count = jdbc.queryForObject("select count(*) from administrators", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        mvc.perform(post("/api/system/admin-init")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isCreated());
    }

    private String loginToken() throws Exception {
        var result = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
