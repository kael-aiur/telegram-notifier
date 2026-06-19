package site.kael.telegram.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TelegramProtocolEnvelope {
    static final String TYPE_INPUT = "input";
    static final String TYPE_REPLY = "reply";
    static final String TYPE_LOG = "log";
    static final String TYPE_MESSAGE = "message";

    private TelegramProtocolEnvelope() {
    }

    static String input(ObjectMapper objectMapper, String id, Map<String, ?> content) {
        var envelope = objectMapper.createObjectNode();
        envelope.put("id", requireText(id, "id"));
        envelope.put("type", TYPE_INPUT);
        envelope.set("content", objectMapper.valueToTree(Objects.requireNonNull(content, "content")));
        return write(objectMapper, envelope);
    }

    static String log(ObjectMapper objectMapper, String message) {
        var content = objectMapper.createObjectNode();
        content.put("level", "ERROR");
        content.put("source", "stderr");
        content.put("message", message == null ? "" : message);
        return output(objectMapper, "java-log-" + UUID.randomUUID(), TYPE_LOG, null, content);
    }

    static String output(ObjectMapper objectMapper, String id, String type, String replyInputId, JsonNode content) {
        var envelope = objectMapper.createObjectNode();
        envelope.put("id", requireText(id, "id"));
        envelope.put("type", requireText(type, "type"));
        if (replyInputId != null && !replyInputId.isBlank()) {
            envelope.put("replyInputId", replyInputId);
        }
        envelope.set("content", content == null ? objectMapper.nullNode() : content);
        return write(objectMapper, envelope);
    }

    static JsonNode validate(ObjectMapper objectMapper, String value) throws IOException {
        var node = objectMapper.readTree(value);
        if (!node.isObject()) {
            throw new IllegalArgumentException("Protocol envelope must be a JSON object");
        }
        requireField(node, "id");
        requireField(node, "type");
        if (!node.has("content")) {
            throw new IllegalArgumentException("Protocol envelope is missing content");
        }
        return node;
    }

    static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    static ObjectNode objectContent(ObjectMapper objectMapper, JsonNode envelope) {
        var content = envelope == null ? null : envelope.get("content");
        if (content != null && content.isObject()) {
            return (ObjectNode) content;
        }
        return objectMapper.createObjectNode();
    }

    private static void requireField(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("Protocol envelope is missing " + field);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String write(ObjectMapper objectMapper, JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize protocol envelope", e);
        }
    }
}
