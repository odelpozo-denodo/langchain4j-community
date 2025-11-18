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
     * CreateOption for the index. By default, the index will not be created.
     */
    CreateOption createOption = CreateOption.CREATE_NONE;

    /**
     * Configures the option to create (or not create) an index. The default is
     * {@link CreateOption#CREATE_NONE}, which means that no index will
     * be created.
     *
     * @param createOption The create option.
     *
     * @return This builder.
     *
     * @throws IllegalArgumentException If createOption is null.
     */
    public T createOption(CreateOption createOption) {
        ensureNotNull(createOption, "createOption");
        this.createOption = createOption;
        return (T) this;
    }

    /**
     * Sets the index name.
     * @param indexName The name of the index.
     * @return This builder.
     */
    public T name(String indexName) {
        this.indexName = indexName;
        return (T) this;
    }

    /**
     * Creates an index name given the table name and a suffix.
     * @param tableName The table name.
     * @param suffix The index suffix.
     * @return The index name.
     */
    String buildIndexName(String tableName, String suffix) {
        String name = tableName + suffix;
        if (name.length() > INDEX_NAME_MAX_LENGTH) {
            name = name.substring(0, INDEX_NAME_MAX_LENGTH);
        }
        return name;
    }

    /**
     * Builds the index object configured by this builder.
     * @return The index object.
     */
    public abstract Index build();

    /**
     * Returns the <em>CREATE INDEX</em> SQL statement of the configured index given
     * the embedding table.
     * @param embeddingTable The embedding table.
     * @return The <em>CREATE INDEX</em> SQL statement.
     */
    abstract String getCreateIndexStatement(EmbeddingTable embeddingTable);

    /**
     * Returns the <em>DROP INDEX</em> SQL statement of the configured index given
     * the embedding table.
     * @param embeddingTable The embedding table.
     * @return The <em>DROP INDEX</em> SQL statement.
     */
    String getDropIndexStatement(EmbeddingTable embeddingTable) {
        return "DROP INDEX " + getIndexName(embeddingTable) + " ON " + embeddingTable.tableName();
    }

    /**
     * Returns the name of the index, if a name has not been set, the name is generated.
     * @param embeddingTable The embedding table.
     * @return The index name.
     */
    abstract String getIndexName(EmbeddingTable embeddingTable);

}
