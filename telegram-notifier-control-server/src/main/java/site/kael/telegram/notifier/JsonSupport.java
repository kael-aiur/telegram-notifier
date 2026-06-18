package site.kael.telegram.notifier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
class JsonSupport {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };
    private final ObjectMapper objectMapper;

    JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON value", e);
        }
    }

    Map<String, Object> readMap(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON object", e);
        }
    }

    List<Long> readLongList(String value) {
        try {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(value, LONG_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON array", e);
        }
    }
}
