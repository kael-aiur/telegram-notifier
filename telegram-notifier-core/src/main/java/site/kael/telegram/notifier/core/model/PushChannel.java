package site.kael.telegram.notifier.core.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record PushChannel(
        long id,
        String name,
        String type,
        boolean enabled,
        Map<String, Object> config,
        Instant createdAt,
        Instant updatedAt
) {
    public PushChannel masked() {
        if (config == null || !config.containsKey("deviceKey")) {
            return this;
        }
        var copy = new LinkedHashMap<>(config);
        copy.put("deviceKey", "******");
        return new PushChannel(id, name, type, enabled, copy, createdAt, updatedAt);
    }
}
