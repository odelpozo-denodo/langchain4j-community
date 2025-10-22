package dev.langchain4j.store.embedding.mysql;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Represents a MySQL index configured by an IndexBuilder.
 */
public class Index {

    private final IndexBuilder<?> builder;
    private String tableName;

    Index(IndexBuilder<?> builder) {
        this.builder = builder;
    }

    /**
     * Creates a JSON functional index on the embedding table according to the builder configuration.
     */
    void create(DataSource dataSource, EmbeddingTable embeddingTable) throws SQLException {
        ensureNotNull(dataSource, "dataSource");
        ensureNotNull(embeddingTable, "embeddingTable");
        this.tableName = embeddingTable.name();

        if (builder.createOption == IndexBuilder.CreateOption.CREATE_NONE) return;

        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            if (builder.createOption == IndexBuilder.CreateOption.CREATE_OR_REPLACE) {
                try {
                    st.execute(builder.getDropIndexStatement(embeddingTable));
                } catch (SQLException ignored) {
                    // ignore if index didn't exist
                }
            }
            try {
                st.execute(builder.getCreateIndexStatement(embeddingTable));
            } catch (SQLException e) {
                if (builder.createOption == IndexBuilder.CreateOption.CREATE_IF_NOT_EXISTS) {
                    // Best-effort: ignore duplicate index errors
                    // (there is no portable SQLSTATE, so swallow on IF NOT EXISTS semantics)
                } else {
                    throw e;
                }
            }
        }
    }

    public String name() { return builder.indexName; }

    public String tableName() { return tableName; }

    public static JSONIndexBuilder jsonIndexBuilder() { return new JSONIndexBuilder(); }
}
