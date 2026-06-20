package site.kael.telegram.notifier;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramMessageEvent;
import site.kael.telegram.notifier.core.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
class SystemController {
    private final AdminService adminService;

    SystemController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/bootstrap-status")
    BootstrapStatus bootstrapStatus() {
        return new BootstrapStatus(!adminService.hasAdmin());
    }

    @PostMapping("/admin-init")
    @ResponseStatus(HttpStatus.CREATED)
    BootstrapStatus initialize(@RequestBody InitAdminRequest request) {
        adminService.initialize(request.username(), request.password());
        return new BootstrapStatus(false);
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AdminService adminService;
    private final TokenService tokenService;

    AuthController(AdminService adminService, TokenService tokenService) {
        this.adminService = adminService;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    LoginResponse login(@RequestBody LoginRequest request) {
        if (!adminService.hasAdmin()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "administrator initialization required");
        }
        if (!adminService.authenticate(request.username(), request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        return new LoginResponse(tokenService.issue());
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request) {
        tokenService.revoke(request.getHeader("X-Auth-Token"));
    }
}

@RestController
@RequestMapping("/api/accounts")
class TelegramAccountController {
    private final TelegramAccountService accounts;

    TelegramAccountController(TelegramAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<TelegramAccount> list() {
        return accounts.list();
    }

    @PostMapping
    TelegramAccount create(@RequestBody TelegramAccountRequest request) {
        return accounts.create(request);
    }

    @GetMapping("/{id}")
    TelegramAccount get(@PathVariable long id) {
        return accounts.get(id);
    }

    @PutMapping("/{id}")
    TelegramAccount update(@PathVariable long id, @RequestBody TelegramAccountRequest request) {
        return accounts.update(id, request);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        accounts.delete(id);
    }

    @PostMapping("/{id}/start")
    TelegramConnectionStatus start(@PathVariable long id) {
        return accounts.start(id);
    }

    @PostMapping("/{id}/stop")
    TelegramConnectionStatus stop(@PathVariable long id) {
        return accounts.stop(id);
    }

    @GetMapping("/{id}/status")
    TelegramConnectionStatus status(@PathVariable long id) {
        return accounts.status(id);
    }

    @PostMapping("/{id}/login/phone")
    TelegramConnectionStatus submitPhone(@PathVariable long id, @RequestBody LoginInput input) {
        return accounts.submitPhone(id, input.value());
    }

    @PostMapping("/{id}/login/code")
    TelegramConnectionStatus submitCode(@PathVariable long id, @RequestBody LoginInput input) {
        return accounts.submitCode(id, input.value());
    }

    @PostMapping("/{id}/login/password")
    TelegramConnectionStatus submitPassword(@PathVariable long id, @RequestBody LoginInput input) {
        return accounts.submitPassword(id, input.value());
    }

    @PutMapping("/{id}/scan-settings")
    TelegramAccount updateScan(@PathVariable long id, @RequestBody Map<String, Long> body) {
        return accounts.updateScan(id, body.getOrDefault("scanFrequencySeconds", 60L),
                body.getOrDefault("unreadAgeThresholdSeconds", 3600L));
    }
}

@RestController
@RequestMapping("/api/proxies")
class ProxyController {
    private final ProxyService proxies;

    ProxyController(ProxyService proxies) {
        this.proxies = proxies;
    }

    @GetMapping
    List<ProxyServer> list() {
        return proxies.list();
    }

    @PostMapping
    ProxyServer create(@RequestBody ProxyServerRequest request) {
        return proxies.create(request);
    }

    @GetMapping("/{id}")
    ProxyServer get(@PathVariable long id) {
        return proxies.get(id).masked();
    }

    @PutMapping("/{id}")
    ProxyServer update(@PathVariable long id, @RequestBody ProxyServerRequest request) {
        return proxies.update(id, request);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        proxies.delete(id);
    }
}

@RestController
@RequestMapping("/api/accounts/{accountId}/proxies")
class AccountProxyController {
    private final ProxyService proxies;
    private final TelegramAccountService accounts;

    AccountProxyController(ProxyService proxies, TelegramAccountService accounts) {
        this.proxies = proxies;
        this.accounts = accounts;
    }

    @GetMapping
    List<Long> bindings(@PathVariable long accountId) {
        accounts.get(accountId);
        return proxies.bindings(accountId);
    }

    @PutMapping
    List<Long> bind(@PathVariable long accountId, @RequestBody AccountProxyBindingRequest request) {
        accounts.get(accountId);
        proxies.bind(accountId, request.proxyIds());
        accounts.refreshProxies(accountId);
        return proxies.bindings(accountId);
    }
}

@RestController
@RequestMapping("/api/channels")
class PushChannelController {
    private final PushChannelService channels;

    PushChannelController(PushChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    List<PushChannel> list() {
        return channels.list();
    }

    @PostMapping
    PushChannel create(@RequestBody PushChannelRequest request) {
        return channels.create(request);
    }

    @GetMapping("/{id}")
    PushChannel get(@PathVariable long id) {
        return channels.get(id).masked();
    }

    @PutMapping("/{id}")
    PushChannel update(@PathVariable long id, @RequestBody PushChannelRequest request) {
        return channels.update(id, request);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        channels.delete(id);
    }

    @PostMapping("/{id}/test")
    DeliveryResult test(@PathVariable long id) {
        return channels.test(id);
    }
}

@RestController
@RequestMapping("/api/rules")
class NotificationRuleController {
    private final NotificationRuleService rules;

    NotificationRuleController(NotificationRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    List<NotificationRule> list() {
        return rules.list();
    }

    @PostMapping
    NotificationRule create(@RequestBody NotificationRuleRequest request) {
        return rules.create(request);
    }

    @GetMapping("/{id}")
    NotificationRule get(@PathVariable long id) {
        return rules.get(id);
    }

    @PutMapping("/{id}")
    NotificationRule update(@PathVariable long id, @RequestBody NotificationRuleRequest request) {
        return rules.update(id, request);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        rules.delete(id);
    }
}

@RestController
@RequestMapping("/api/statistics")
class StatisticsController {
    private final StatisticsService statistics;

    StatisticsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping
    StatisticsResponse query() {
        return statistics.query();
    }
}

@RestController
@RequestMapping("/api/test")
class TestMessageController {
    private final TelegramAccountSessionManager sessions;

    TestMessageController(TelegramAccountSessionManager sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/messages")
    void publish(@RequestBody Map<String, Object> body) {
        var event = new TelegramMessageEvent(
                longValue(body.get("accountId"), 1L),
                longValue(body.get("chatId"), 1L),
                longValue(body.get("messageId"), 0L),
                stringValue(body.get("chatTitle"), "服务器"),
                stringValue(body.get("chatType"), "private"),
                longValue(body.get("senderId"), 1L),
                stringValue(body.get("senderName"), "sender"),
                stringValue(body.get("senderUsername"), "sender"),
                instantValue(body.get("receivedAt")),
                stringValue(body.get("text"), "")
        );
        sessions.publishTestMessage(event);
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return defaultValue;
    }

    private Instant instantValue(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return Instant.parse(text);
        }
        return Instant.now();
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }
}
