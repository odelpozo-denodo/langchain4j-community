package dev.langchain4j.store.embedding.mysql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Defines the MySQL table used to store embeddings and optional text/metadata.
 * MySQL 9+ supports VECTOR data type and JSON, which we leverage here.
 */
class EmbeddingTable {

    enum CreateOption { CREATE_IF_NOT_EXISTS, CREATE_OR_REPLACE, NONE }

    private final String name;
    private final String idColumn;
    private final String embeddingColumn;
    private final String textColumn;
    private final String metadataColumn;
    private final Integer dimension;
    private final CreateOption createOption;

    private EmbeddingTable(Builder builder) {
        this.name = builder.name != null ? builder.name : "embeddings";
        this.idColumn = builder.idColumn != null ? builder.idColumn : "id";
        this.embeddingColumn = builder.embeddingColumn != null ? builder.embeddingColumn : "embedding";
        this.textColumn = builder.textColumn != null ? builder.textColumn : "text";
        this.metadataColumn = builder.metadataColumn != null ? builder.metadataColumn : "metadata";
        this.dimension = builder.dimension;
        this.createOption = builder.createOption != null ? builder.createOption : CreateOption.CREATE_IF_NOT_EXISTS;
    }

    static Builder builder() { return new Builder(); }

    String name() { return name; }
    String idColumn() { return idColumn; }
    String embeddingColumn() { return embeddingColumn; }
    String textColumn() { return textColumn; }
    String metadataColumn() { return metadataColumn; }
    Integer dimension() { return dimension; }

    void create(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            if (createOption == CreateOption.CREATE_OR_REPLACE) {
                st.execute(getDropTableStatement());
            }
            st.execute(getCreateTableStatement());
        }
    }

    String getCreateTableStatement() {
        String dim = dimension != null ? "(" + dimension + ")" : "";
        // VECTOR(dim) FLOAT32 is the recommended form as of MySQL 9.0 docs
        // We keep text and metadata nullable
        return "CREATE TABLE IF NOT EXISTS " + name + " (" +
                idColumn + " VARCHAR(64) PRIMARY KEY, " +
                embeddingColumn + " VECTOR" + dim + " FLOAT32 NOT NULL, " +
                textColumn + " TEXT NULL, " +
                metadataColumn + " JSON NULL" +
                ")";
    }

    String getDropTableStatement() {
        return "DROP TABLE IF EXISTS " + name;
    }

    // Map a metadata key to a MySQL JSON extraction expression
    String mapMetadataKey(String key, Class<?> valueClass) {
        return "JSON_UNQUOTE(JSON_EXTRACT(" + metadataColumn + ", '$." + key + "'))";
    }

    static final class Builder {
        private String name;
        private String idColumn;
        private String embeddingColumn;
        private String textColumn;
        private String metadataColumn;
        private Integer dimension;
        private CreateOption createOption;

        Builder name(String tableName) { this.name = tableName; return this; }
        Builder idColumn(String idColumn) { this.idColumn = idColumn; return this; }
        Builder embeddingColumn(String col) { this.embeddingColumn = col; return this; }
        Builder textColumn(String textColumn) { this.textColumn = textColumn; return this; }
        Builder metadataColumn(String metadataColumn) { this.metadataColumn = metadataColumn; return this; }
        Builder dimension(Integer dimension) { this.dimension = dimension; return this; }
        Builder createOption(CreateOption createOption) { this.createOption = createOption; return this; }
        EmbeddingTable build() { return new EmbeddingTable(this); }
    }
}
