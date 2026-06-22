package site.kael.telegram.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内测试桩实现:不启动真实 Telegram,仅维护登录状态机与活动代理。
 * {@link #peekUnreadMessages} 恒返回空列表(测试桩不模拟未读消息)。
 */
public class InMemoryTelegramAccountSessionManager implements TelegramAccountSessionManager {
    private final Map<Long, SessionState> sessions = new ConcurrentHashMap<>();
    private final List<TelegramConnectionStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    @Override
    public TelegramConnectionStatus start(TelegramAccountConfig config) {
        var proxies = enabledProxies(config.proxies());
        var activeProxy = proxies.isEmpty() ? null : proxies.getFirst().id();
        var state = sessions.computeIfAbsent(config.accountId(), id -> new SessionState());
        state.authorizationState = AuthorizationState.WAIT_PHONE;
        state.activeProxyId = activeProxy;
        state.errorMessage = null;
        state.proxies = proxies;
        return publishStatus(config.accountId());
    }

    @Override
    public TelegramConnectionStatus stop(long accountId) {
        var state = sessions.computeIfAbsent(accountId, id -> new SessionState());
        state.authorizationState = AuthorizationState.LOGGED_OUT;
        state.activeProxyId = null;
        return publishStatus(accountId);
    }

    @Override
    public TelegramConnectionStatus submitPhone(long accountId, String phoneNumber) {
        var state = sessions.computeIfAbsent(accountId, id -> new SessionState());
        state.authorizationState = AuthorizationState.WAIT_CODE;
        return publishStatus(accountId);
    }

    @Override
    public TelegramConnectionStatus submitCode(long accountId, String code) {
        var state = sessions.computeIfAbsent(accountId, id -> new SessionState());
        state.authorizationState = "password".equalsIgnoreCase(code) ? AuthorizationState.WAIT_PASSWORD : AuthorizationState.READY;
        return publishStatus(accountId);
    }

    @Override
    public TelegramConnectionStatus submitPassword(long accountId, String password) {
        var state = sessions.computeIfAbsent(accountId, id -> new SessionState());
        state.authorizationState = AuthorizationState.READY;
        return publishStatus(accountId);
    }

    @Override
    public TelegramConnectionStatus status(long accountId) {
        var state = sessions.get(accountId);
        if (state == null) {
            return new TelegramConnectionStatus(accountId, AuthorizationState.LOGGED_OUT, null, null);
        }
        return new TelegramConnectionStatus(accountId, state.authorizationState, state.activeProxyId, state.errorMessage);
    }

    @Override
    public void updateProxies(long accountId, List<ProxyConfig> proxies) {
        var state = sessions.computeIfAbsent(accountId, id -> new SessionState());
        state.proxies = enabledProxies(proxies);
        state.activeProxyId = state.proxies.isEmpty() ? null : state.proxies.getFirst().id();
        publishStatus(accountId);
    }

    @Override
    public List<TelegramMessage> peekUnreadMessages(long accountId, long chatId) {
        return List.of();
    }

    @Override
    public void subscribeStatus(TelegramConnectionStatusListener listener) {
        statusListeners.add(listener);
    }

    private List<ProxyConfig> enabledProxies(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return List.of();
        }
        return proxies.stream().filter(ProxyConfig::enabled).toList();
    }

    private TelegramConnectionStatus publishStatus(long accountId) {
        var status = status(accountId);
        statusListeners.forEach(listener -> listener.onStatus(status));
        return status;
    }

    private static final class SessionState {
        private AuthorizationState authorizationState = AuthorizationState.LOGGED_OUT;
        private Long activeProxyId;
        private String errorMessage;
        private List<ProxyConfig> proxies = new ArrayList<>();
    }
}
