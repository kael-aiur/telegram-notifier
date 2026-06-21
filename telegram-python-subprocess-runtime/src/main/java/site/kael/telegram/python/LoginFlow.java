package site.kael.telegram.python;

import site.kael.telegram.starter.AuthorizationState;

/**
 * 登录流程句柄,与所属 {@link TelegramClient} 共享同一份授权状态。
 *
 * <p>调用方按当前环节驱动流程:
 * <pre>{@code
 * LoginFlow flow = client.login();
 * while (flow.getState() != AuthorizationState.READY) {
 *     AuthorizationState next = flow.submit(从用户拿到的值);
 * }
 * }</pre>
 *
 * {@link #submit(String)} 根据当前 {@link AuthorizationState} 自动选择提交的命令
 * (WAIT_PHONE 提交手机号、WAIT_CODE 提交验证码、WAIT_PASSWORD 提交二步密码),
 * 并返回提交后的新状态。流程不维护独立状态副本,读写的是 client 的共享状态。
 */
public interface LoginFlow {

    AuthorizationState getState();

    AuthorizationState submit(String value);
}
