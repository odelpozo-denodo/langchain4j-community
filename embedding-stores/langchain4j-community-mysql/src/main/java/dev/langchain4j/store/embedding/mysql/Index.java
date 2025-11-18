package dev.langchain4j.store.embedding.mysql;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Represents a database index built using an {@code IndexBuilder}.
 * This class provides functionality to create, replace, or ensure the existence of an index
 * for a specified database table based on the configuration provided by the builder.
 */
public class Index {

    /**
     * The index builder.
     */
    private final IndexBuilder<?> builder;

    /**
     * The name of the table.
     */
    private String tableName;

    /**
     * Create an index.
     * @param builder The builder.
     */
    Index(IndexBuilder<?> builder) {
        this.builder = builder;
    }

    /**
     * Creates a builder to configure a JSON index on the metadata column of
     * the {@link EmbeddingTable}.
     * @return A builder that allows to configure a JSON index.
     */
    public static JSONIndexBuilder jsonIndexBuilder() {
        return new JSONIndexBuilder();
    }

    /**
     * Returns the name of the index.
     *
     * @return The name of the index or null if the name has not been set and the index
     * has not been created.
     */
    public String name() {
        return builder.indexName;
    }

    /**
     * Returns the name of this table.
     *
     * @return Once the index has been created it returns the table name, otherwise it
     * returns null.
     */
    public String tableName() {
        return tableName;
    }

    /**
     * Creates an index for the specified embedding table based on the configuration of the {@code IndexBuilder}.
     * Depending on the {@code createOption} specified in the builder, this method will create, replace, or skip
     * the index creation process.
     * Indexes do not support the CreateOption.CREATE_IF_NOT_EXISTS option.
     *
     * @param dataSource the data source to obtain a database connection from; must not be null
     * @param embeddingTable the table for which the index is to be created; must not be null
     * @throws SQLException if a database access error occurs or the index creation fails
     */
    void create(DataSource dataSource, EmbeddingTable embeddingTable) throws SQLException {
        ensureNotNull(dataSource, "dataSource");
        ensureNotNull(embeddingTable, "embeddingTable");

        this.tableName = embeddingTable.getQualifiedTableName();
        if (builder.createOption == CreateOption.CREATE_NONE) return;

        try (Connection connection = dataSource.getConnection()) {
            if (builder.createOption == CreateOption.CREATE_OR_REPLACE) {
                try (PreparedStatement st = connection.prepareStatement(builder.getDropIndexStatement(embeddingTable))){
                    st.executeUpdate();
                } catch (SQLException ignored) {
                    // ignore if index didn't exist
                }
            }
            try (PreparedStatement st = connection.prepareStatement(builder.getCreateIndexStatement(embeddingTable))){
                st.executeUpdate();
            }
        }
    }

}
