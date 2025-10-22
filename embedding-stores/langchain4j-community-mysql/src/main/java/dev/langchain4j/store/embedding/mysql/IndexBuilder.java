package dev.langchain4j.store.embedding.mysql;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * Base class for building MySQL indexes for the MySQLEmbeddingStore.
 */
abstract class IndexBuilder<T extends IndexBuilder<T>> {

    static final int INDEX_NAME_MAX_LENGTH = 64; // MySQL identifier max length

    /**
     * The name of the index, or null if no name was set.
     */
    protected String indexName;

    /**
     * Create option for the index. Defaults to CREATE_IF_NOT_EXISTS.
     */
    CreateOption createOption = CreateOption.CREATE_IF_NOT_EXISTS;

    /**
     * Sets the create option.
     */
    public T createOption(CreateOption createOption) {
        ensureNotNull(createOption, "createOption");
        this.createOption = createOption;
        return (T) this;
    }

    /**
     * Sets a custom index name.
     */
    public T name(String indexName) {
        this.indexName = indexName;
        return (T) this;
    }

    String buildIndexName(String tableName, String suffix) {
        String name = tableName + suffix;
        if (name.length() > INDEX_NAME_MAX_LENGTH) {
            name = name.substring(0, INDEX_NAME_MAX_LENGTH);
        }
        return name;
    }

    /** Build the immutable Index wrapper. */
    public abstract Index build();

    /** Render CREATE INDEX statement for this builder. */
    abstract String getCreateIndexStatement(EmbeddingTable embeddingTable);

    /** Render DROP INDEX statement for this builder. */
    String getDropIndexStatement(EmbeddingTable embeddingTable) {
        return "DROP INDEX " + getIndexName(embeddingTable) + " ON " + embeddingTable.name();
    }

    /** Ensure an index name exists and return it. */
    abstract String getIndexName(EmbeddingTable embeddingTable);

    /** Create options for MySQL index creation. */
    enum CreateOption { CREATE_NONE, CREATE_IF_NOT_EXISTS, CREATE_OR_REPLACE }
}
