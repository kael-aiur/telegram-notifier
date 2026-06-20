package site.kael.telegram.notifier.core.model;

import java.util.List;
import java.util.Map;

public record StatisticsResponse(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> rules,
        List<Map<String, Object>> deliveries
) {
}
