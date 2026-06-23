package site.kael.telegram.notifier.core.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ValidationSupport {

    private ValidationSupport() {
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    public static long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required and must be positive");
        }
        return value;
    }

    public static boolean bool(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    public static long positive(Long value, long defaultValue) {
        var v = value == null ? defaultValue : value;
        if (v <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must be positive");
        }
        return v;
    }

    public static long positive(long value, long defaultValue) {
        return positive(Long.valueOf(value), defaultValue);
    }

    public static int validPort(Integer port) {
        if (port == null || port <= 0 || port > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid port is required");
        }
        return port;
    }

    public static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
