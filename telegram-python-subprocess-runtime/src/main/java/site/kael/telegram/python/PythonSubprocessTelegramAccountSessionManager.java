package site.kael.telegram.python;

import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.ProxyConfig;
import site.kael.telegram.starter.TelegramAccountConfig;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramConnectionStatus;
import site.kael.telegram.starter.TelegramConnectionStatusListener;
import site.kael.telegram.starter.TelegramMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * 薄注册表实现:持有 {@code Map<accountId, TelegramClient>},把 starter 接口的每个方法委托给对应 client。
 *
 * <p>本类不感知任何 session 概念——{@link TelegramSession}/{@link TelegramSessionConfig}/
 * {@link TelegramSessionProxyConfig}/{@link SubprocessTelegramSession} 及 worker bootstrap 均已下沉到
 * {@link TelegramClientFactory} 与 client 实现内部。本类只负责:按账号委托 client、bootstrap/装配错误转
 * {@code ERROR} status、status 扇出。
 */
public class PythonSubprocessTelegramAccountSessionManager implements TelegramAccountSessionManager, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PythonSubprocessTelegramAccountSessionManager.class.getName());

    private final TelegramClientFactory factory;
    private final Map<Long, TelegramClient> clients = new ConcurrentHashMap<>();
    private final List<TelegramConnectionStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    public PythonSubprocessTelegramAccountSessionManager(TelegramClientFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public TelegramConnectionStatus start(TelegramAccountConfig config) {
        var validationError = factory.validate();
        if (validationError != null) {
            return forward(new TelegramConnectionStatus(
                    config.accountId(), AuthorizationState.ERROR, activeProxyId(config.proxies()), validationError));
        }
        TelegramClient client;
        try {
            client = clients.computeIfAbsent(config.accountId(), id -> factory.create(config, this::forward));
        } catch (RuntimeException e) {
            return forward(new TelegramConnectionStatus(
                    config.accountId(), AuthorizationState.ERROR, activeProxyId(config.proxies()), safeError(e)));
        }
        try {
            client.start();
        } catch (RuntimeException e) {
            LOGGER.fine("telegram start failed for account " + config.accountId() + ": " + safeError(e));
        }
        return statusOf(config.accountId(), client);
    }

    @Override
    public TelegramConnectionStatus stop(long accountId) {
        var client = clients.remove(accountId);
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        return forward(new TelegramConnectionStatus(accountId, AuthorizationState.LOGGED_OUT, null, null));
    }

    @Override
    public TelegramConnectionStatus submitPhone(long accountId, String phoneNumber) {
        return driveSubmit(accountId, "submit_phone", phoneNumber);
    }

    @Override
    public TelegramConnectionStatus submitCode(long accountId, String code) {
        return driveSubmit(accountId, "submit_code", code);
    }

    @Override
    public TelegramConnectionStatus submitPassword(long accountId, String password) {
        return driveSubmit(accountId, "submit_password", password);
    }

    private TelegramConnectionStatus driveSubmit(long accountId, String command, String value) {
        var client = clients.get(accountId);
        if (client == null) {
            return forward(new TelegramConnectionStatus(
                    accountId, AuthorizationState.ERROR, null, "account session not started"));
        }
        // starter 接口是显式 submit_phone/code/password,按精确命令提交,避免多态分发误判。
        try {
            ((DefaultTelegramClient) client).submitAuthorization(command, value);
        } catch (RuntimeException e) {
            LOGGER.fine("telegram " + command + " failed for account " + accountId + ": " + safeError(e));
        }
        return statusOf(accountId, client);
    }

    @Override
    public TelegramConnectionStatus status(long accountId) {
        var client = clients.get(accountId);
        if (client == null) {
            return new TelegramConnectionStatus(accountId, AuthorizationState.LOGGED_OUT, null, null);
        }
        return statusOf(accountId, client);
    }

    @Override
    public void updateProxies(long accountId, List<ProxyConfig> proxies) {
        var client = clients.get(accountId);
        if (client != null) {
            client.updateProxies(proxies);
        }
    }

    @Override
    public List<TelegramMessage> peekUnreadMessages(long accountId, long chatId) {
        var client = clients.get(accountId);
        if (client == null) {
            return List.of();
        }
        try {
            return client.peekUnreadMessage(chatId);
        } catch (RuntimeException e) {
            LOGGER.warning("peekUnreadMessages failed for account " + accountId + ": " + safeError(e));
            return List.of();
        }
    }

    @Override
    public void subscribeStatus(TelegramConnectionStatusListener listener) {
        statusListeners.add(listener);
    }

    @Override
    public void close() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        });
        clients.clear();
    }

    // ---- 辅助 ----

    private TelegramConnectionStatus statusOf(long accountId, TelegramClient client) {
        var impl = (DefaultTelegramClient) client;
        return new TelegramConnectionStatus(
                accountId, impl.getState(), impl.activeProxyId(), impl.errorMessage());
    }

    private TelegramConnectionStatus forward(TelegramConnectionStatus status) {
        statusListeners.forEach(listener -> listener.onStatus(status));
        return status;
    }

    private Long activeProxyId(List<ProxyConfig> proxies) {
        if (proxies == null) {
            return null;
        }
        return proxies.stream().filter(ProxyConfig::enabled).findFirst().map(ProxyConfig::id).orElse(null);
    }

    private String safeError(Throwable error) {
        var message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
