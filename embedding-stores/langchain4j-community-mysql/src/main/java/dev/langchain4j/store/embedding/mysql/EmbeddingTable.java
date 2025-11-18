package dev.langchain4j.store.embedding.mysql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Defines the MySQL table used to store embeddings and optional text/metadata.
 * MySQL 9+ supports VECTOR data type and JSON, which we leverage here.
 */
public class EmbeddingTable {

    /**
     * The default catalog for the embedding table.
     */
    public static final String DEFAULT_CATALOG_NAME = null;

    /**
     * The default schema for the embedding table.
     */
    public static final String DEFAULT_SCHEMA_NAME = null;

    /**
     * The default name for the embedding table.
     */
    public static final String DEFAULT_TABLE_NAME = "embeddings";

    /**
     * The default name for the ID column.
     */
    public static final String DEFAULT_ID_COLUMN = "id";

    /**
     * The default name for the embedding column.
     */
    public static final String DEFAULT_EMBEDDING_COLUMN = "embedding";

    /**
     * The default name for the text column.
     */
    public static final String DEFAULT_TEXT_COLUMN = "text";

    /**
     * The default name for the metadata column.
     */
    public static final String DEFAULT_METADATA_COLUMN = "metadata";

    private final String catalogName;
    private final String schemaName;
    private final String tableName;
    private final String idColumn;
    private final String embeddingColumn;
    private final String textColumn;
    private final String metadataColumn;
    private final Integer dimension;
    private final CreateOption createOption;

    private EmbeddingTable(Builder builder) {
        this.catalogName = builder.catalogName != null ? builder.catalogName : DEFAULT_CATALOG_NAME;
        this.schemaName = builder.schemaName != null ? builder.schemaName : DEFAULT_SCHEMA_NAME;
        this.tableName = builder.tableName != null ? builder.tableName : DEFAULT_TABLE_NAME;
        this.idColumn = builder.idColumn != null ? builder.idColumn : DEFAULT_ID_COLUMN;
        this.embeddingColumn = builder.embeddingColumn != null ? builder.embeddingColumn : DEFAULT_EMBEDDING_COLUMN;
        this.textColumn = builder.textColumn != null ? builder.textColumn : DEFAULT_TEXT_COLUMN;
        this.metadataColumn = builder.metadataColumn != null ? builder.metadataColumn : DEFAULT_METADATA_COLUMN;
        this.dimension = builder.dimension;
        this.createOption = builder.createOption != null ? builder.createOption : CreateOption.CREATE;
    }

    /**
     * Creates a builder for configuring an EmbeddingTable.
     *
     * @return A new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the name of the table.
     *
     * @return The table name.
     */
    public String tableName() {
        return tableName;
    }

    /**
     * Returns the name of the ID column.
     *
     * @return The ID column name.
     */
    public String idColumn() {
        return idColumn;
    }

    /**
     * Returns the name of the embedding column.
     *
     * @return The embedding column name.
     */
    public String embeddingColumn() {
        return embeddingColumn;
    }

    /**
     * Returns the name of the text column.
     *
     * @return The text column name.
     */
    public String textColumn() {
        return textColumn;
    }

    /**
     * Returns the name of the metadata column.
     *
     * @return The metadata column name.
     */
    public String metadataColumn() {
        return metadataColumn;
    }

    /**
     * Returns the dimension of the embedding vectors.
     *
     * @return The dimension, or null if not specified.
     */
    public Integer dimension() {
        return dimension;
    }

    /**
     * Creates the table in the database if the create option is configured to do so.
     *
     * @param dataSource The data source to use for database connections.
     * @throws SQLException If an error occurs while creating the table.
     */
    void create(DataSource dataSource) throws SQLException {

        if (createOption == CreateOption.CREATE_NONE) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            if (createOption == CreateOption.CREATE_OR_REPLACE) {
                try (PreparedStatement st = connection.prepareStatement(getDropTableStatement())){
                    st.executeUpdate();
                }
            }
            try (PreparedStatement st = connection.prepareStatement(getCreateTableStatement(
                    createOption == CreateOption.CREATE_IF_NOT_EXISTS))) {
                st.executeUpdate();
            }
        }
    }

    /**
     * Constructs and returns the fully qualified name of the table, including catalog and schema names
     * if they are specified. If neither catalogName nor schemaName is provided, only tableName is returned.
     *
     * @return The fully qualified name of the table, composed of catalogName, schemaName, and tableName
     *         where applicable.
     */
    public String getQualifiedTableName() {
        if (catalogName == null && schemaName == null) {
            return tableName;
        } else if (catalogName == null) {
            return schemaName + '.' + tableName;
        } else {
            if (schemaName == null) {
                return catalogName + '.' + tableName;
            }
            return catalogName + '.' + schemaName + '.' + tableName;
        }
    }

    private String getCreateTableStatement(boolean ifNotExists) {
        String dim = dimension != null ? "(" + dimension + ")" : "";
        // VECTOR(dim) FLOAT32 is the recommended form as of MySQL 9.0 docs
        // We keep text and metadata nullable
        return "CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS ": "") + getQualifiedTableName() + " (" +
                idColumn + " VARCHAR(64) PRIMARY KEY, " +
                embeddingColumn + " VECTOR(" + dim + ") FLOAT32 NOT NULL, " +
                textColumn + " TEXT NULL, " +
                metadataColumn + " JSON NULL" +
                ")";
    }

    private String getDropTableStatement() {
        return "DROP TABLE IF EXISTS " + getQualifiedTableName();
    }

    /**
     * Maps a metadata key to a JSON extraction expression for querying within the metadata column.
     *
     * @param key         The metadata key whose value needs to be extracted.
     * @param valueClass  The expected class type of the metadata value. This parameter does not directly
     *                    affect the generated SQL expression but may guide downstream type handling.
     * @return A SQL string that extracts the value of the specified metadata key from the metadata column.
     */
    String mapMetadataKey(String key, Class<?> valueClass) {
        return "JSON_UNQUOTE(JSON_EXTRACT(" + metadataColumn + ", '$." + key + "'))";
    }

    static final class Builder {
        private String catalogName = DEFAULT_CATALOG_NAME;
        private String schemaName = DEFAULT_SCHEMA_NAME;
        private String tableName = DEFAULT_TABLE_NAME;
        private String idColumn = DEFAULT_ID_COLUMN;
        private String embeddingColumn = DEFAULT_EMBEDDING_COLUMN;
        private String textColumn = DEFAULT_TEXT_COLUMN;
        private String metadataColumn = DEFAULT_METADATA_COLUMN;
        private CreateOption createOption = CreateOption.CREATE_NONE;
        private Integer dimension;

        /**
         * Sets the escaped catalog name.
         *
         * @param catalogName The escaped catalog name.
         * @return This builder.
         */
        public Builder catalogName(String catalogName) {
            this.catalogName = catalogName;
            return this;
        }

        /**
         * Sets the escaped schema name.
         *
         * @param schemaName The escaped schema name.
         * @return This builder.
         */
        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        /**
         * Sets the table name.
         *
         * @param tableName The table name.
         * @return This builder.
         */
        Builder name(String tableName) { this.tableName = tableName; return this; }

        /**
         * Sets the ID column name.
         *
         * @param idColumn The ID column name.
         * @return This builder.
         */
        public Builder idColumn(String idColumn) {
            this.idColumn = idColumn;
            return this;
        }

        /**
         * Sets the embedding column name.
         *
         * @param embeddingColumn The embedding column name.
         * @return This builder.
         */
        public Builder embeddingColumn(String embeddingColumn) {
            this.embeddingColumn = embeddingColumn;
            return this;
        }

        /**
         * Sets the text column name.
         *
         * @param textColumn The text column name.
         * @return This builder.
         */
        public Builder textColumn(String textColumn) {
            this.textColumn = textColumn;
            return this;
        }

        /**
         * Sets the metadata column name.
         *
         * @param metadataColumn The metadata column name.
         * @return This builder.
         */
        public Builder metadataColumn(String metadataColumn) {
            this.metadataColumn = metadataColumn;
            return this;
        }

        /**
         * Sets the create option.
         *
         * @param createOption The create option.
         * @return This builder.
         */
        public Builder createOption(CreateOption createOption) {
            this.createOption = createOption;
            return this;
        }

        /**
         * Sets the dimension of the embedding vectors.
         *
         * @param dimension The dimension.
         * @return This builder.
         */
        public Builder dimension(Integer dimension) {
            this.dimension = dimension;
            return this;
        }

        /**
         * Builds the EmbeddingTable instance.
         *
         * @return The configured EmbeddingTable.
         */
        public EmbeddingTable build() {
            if (dimension == null) {
                throw new IllegalStateException("Dimension must be specified");
            }
            return new EmbeddingTable(this);
        }

        @Override
        public String toString() {
            return "Builder{" + "catalogName='"
                    + catalogName + '\'' + ", schemaName='"
                    + schemaName + '\'' + ", tableName='"
                    + tableName + '\'' + ", idColumn='"
                    + idColumn + '\'' + ", embeddingColumn='"
                    + embeddingColumn + '\'' + ", textColumn='"
                    + textColumn + '\'' + ", metadataColumn='"
                    + metadataColumn + '\'' + ", createOption="
                    + createOption + ", dimension="
                    + dimension + '}';
        }

    }
}
