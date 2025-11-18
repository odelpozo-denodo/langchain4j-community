package dev.langchain4j.store.embedding.mysql.sql;

import dev.langchain4j.store.embedding.mysql.DistanceMetric;

/**
 * Strategy for generating the SQL distance expression used in nearest-neighbor search.
 *
 * Implementations can target different MySQL variants or custom deployments
 * without changing the embedding store logic.
 */
public interface DistanceFunctionStrategy {

    /**
     * Builds the SQL distance expression to be used in SELECT list and ORDER BY.
     * The expression must evaluate to a numeric distance where lower means closer.
     *
     * Example return values:
     * - "DISTANCE(emb, STRING_TO_VECTOR(?) USING COSINE)"
     * - "cosine_distance(emb, STRING_TO_VECTOR(?))"
     *
     * @param embeddingColumn the name of the embedding column
     * @param metric the distance metric
     * @return SQL expression string (without alias)
     */
    String buildDistanceExpression(String embeddingColumn, DistanceMetric metric);
}
