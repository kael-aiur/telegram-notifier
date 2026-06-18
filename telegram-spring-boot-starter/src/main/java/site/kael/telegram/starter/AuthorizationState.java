package site.kael.telegram.starter;

public enum AuthorizationState {
    WAIT_PHONE,
    WAIT_CODE,
    WAIT_PASSWORD,
    READY,
    LOGGED_OUT,
    ERROR
}
