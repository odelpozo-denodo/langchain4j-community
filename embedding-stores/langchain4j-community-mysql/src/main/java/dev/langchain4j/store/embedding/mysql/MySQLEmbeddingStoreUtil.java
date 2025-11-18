package dev.langchain4j.store.embedding.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.mysql.exception.MySQLLangChain4jException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MySQLEmbeddingStoreUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static String metadataToJson(Metadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata.toMap());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize metadata to JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Metadata jsonToMetadata(String json) {
        if (json == null || json.isBlank()) return Metadata.from(new HashMap<>());
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return Metadata.from(map);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize metadata JSON", e);
        }
    }

    static <T> T ensureIndexNotNull(List<T> list, int index, String name) {
        T item = list.get(index);
        if (item == null) throw new IllegalArgumentException(name + " contains null at index " + index);
        return item;
    }

    static String vectorToJSONArray(float[] vector) {
        // Represent vector as JSON array string, e.g., [0.1, 0.2]
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    static float[] jsonArrayToFloats(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return null;
        // Very small parser for a flat JSON float array: [0.1,0.2,...]
        String s = jsonArray.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) return new float[0];
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
