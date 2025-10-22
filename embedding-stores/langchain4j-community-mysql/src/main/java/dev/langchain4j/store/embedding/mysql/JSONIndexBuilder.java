package dev.langchain4j.store.embedding.mysql;

import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builder for creating functional indexes on JSON keys stored in the metadata column.
 * This mirrors the Oracle JSONIndexBuilder idea using MySQL JSON extraction functions.
 */
public class JSONIndexBuilder extends IndexBuilder<JSONIndexBuilder> {

    private boolean unique;

    private final List<MetadataKey> indexExpressions = new ArrayList<>();

    public enum Order { ASC, DESC }

    JSONIndexBuilder() {}

    public JSONIndexBuilder isUnique(boolean unique) {
        this.unique = unique;
        return this;
    }

    /**
     * Add a metadata key to index.
     * @param key metadata key (dot notation supported)
     * @param keyType Java type of the key for proper casting
     * @param order index order
     */
    public JSONIndexBuilder key(String key, Class<?> keyType, Order order) {
        ensureNotBlank(key, "key");
        ensureNotNull(keyType, "keyType");
        ensureNotNull(order, "order");
        indexExpressions.add(new MetadataKey(key, keyType, order));
        return this;
    }

    @Override
    public Index build() {
        return new Index(this);
    }

    @Override
    String getCreateIndexStatement(EmbeddingTable embeddingTable) {
        return "CREATE " + (unique ? "UNIQUE " : "") +
                "INDEX " + getIndexName(embeddingTable) +
                " ON " + embeddingTable.name() + " (" + getIndexExpression(embeddingTable) + ")";
    }

    @Override
    String getIndexName(EmbeddingTable embeddingTable) {
        if (indexName == null) {
            indexName = buildIndexName(
                    embeddingTable.name(),
                    "_METADATA_" + indexExpressions.stream()
                            .map(mk -> mk.key.toUpperCase().replaceAll("[^A-Z0-9_]+", "_"))
                            .collect(Collectors.joining("_"))
            );
        }
        return indexName;
    }

    private String getIndexExpression(EmbeddingTable embeddingTable) {
        return indexExpressions.stream()
                .map(mk -> embeddingTable.mapMetadataKey(mk.key, mk.keyType) + " " + mk.order)
                .collect(Collectors.joining(", "));
    }

    private static class MetadataKey {
        final String key;
        final Class<?> keyType;
        final Order order;
        MetadataKey(String key, Class<?> keyType, Order order) {
            this.key = key; this.keyType = keyType; this.order = order;
        }
    }
}
