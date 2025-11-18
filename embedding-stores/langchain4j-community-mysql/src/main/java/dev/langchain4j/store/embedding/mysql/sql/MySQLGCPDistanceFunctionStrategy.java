package dev.langchain4j.store.embedding.mysql.sql;

import dev.langchain4j.store.embedding.mysql.DistanceMetric;

/**
 * Strategy for Google Cloud MySQL which uses cosine_distance and l2_squared_distance.
 * Uses STRING_TO_VECTOR for converting JSON array strings to VECTOR.
 */
public class MySQLGCPDistanceFunctionStrategy implements DistanceFunctionStrategy {

    @Override
    public String buildDistanceExpression(String embeddingColumn, DistanceMetric metric) {
        String paramVector = "STRING_TO_VECTOR(?))";
        return switch (metric) {
            case COSINE -> "cosine_distance(" + embeddingColumn + ", " + paramVector + ")";
            case EUCLIDEAN -> "SQRT(l2_squared_distance(" + embeddingColumn + ", " + paramVector + "))";
            default -> throw new UnsupportedOperationException("Unsupported distance metric: " + metric);
        };
    }
}
