package dev.langchain4j.store.embedding.mysql.sql;

import dev.langchain4j.store.embedding.mysql.DistanceMetric;

/**
 * Strategy for official MySQL 9+ using the DISTANCE function.
 * Uses STRING_TO_VECTOR for converting JSON array strings to VECTOR.
 */
public class MySQLDefaultDistanceFunctionStrategy implements DistanceFunctionStrategy {

    @Override
    public String buildDistanceExpression(String embeddingColumn, DistanceMetric metric) {
        String paramVector = "STRING_TO_VECTOR(?)";
        String metricParam = metric == DistanceMetric.COSINE ? "COSINE" : "EUCLIDEAN";
        return "DISTANCE(" + embeddingColumn + ", " + paramVector + ", " + metricParam + ")";
    }
}
