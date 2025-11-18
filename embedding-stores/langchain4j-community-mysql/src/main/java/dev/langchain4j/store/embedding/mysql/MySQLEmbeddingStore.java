package dev.langchain4j.store.embedding.mysql;

import com.mysql.cj.jdbc.MysqlDataSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.mysql.exception.MySQLLangChain4jException;
import dev.langchain4j.store.embedding.mysql.sql.DistanceFunctionStrategy;
import dev.langchain4j.store.embedding.mysql.sql.MySQLDefaultDistanceFunctionStrategy;
import dev.langchain4j.store.embedding.mysql.sql.MySQLGCPDistanceFunctionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static dev.langchain4j.internal.Utils.randomUUID;
import static dev.langchain4j.internal.ValidationUtils.ensureNotEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * MySQL implementation of EmbeddingStore using MySQL 9+ native VECTOR type and DISTANCE() function.
 */
public class MySQLEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final DistanceFunctionStrategy distanceStrategy;

    private static final Logger logger = LoggerFactory.getLogger(MySQLEmbeddingStore.class);

    private final DataSource dataSource;
    private final EmbeddingTable embeddingTable;
    private final DistanceMetric metric;

    private MySQLEmbeddingStore(DataSource dataSource, EmbeddingTable embeddingTable, java.util.List<Index> indexes, Boolean gcp, DistanceMetric metric) {
            this(dataSource, embeddingTable, indexes, gcp, metric, null);
        }

        private MySQLEmbeddingStore(DataSource dataSource, EmbeddingTable embeddingTable, java.util.List<Index> indexes,
                                    Boolean gcp, DistanceMetric metric, DistanceFunctionStrategy strategy) {
            this.distanceStrategy = strategy != null
                    ? strategy
                    : (Boolean.TRUE.equals(gcp) ? new MySQLGCPDistanceFunctionStrategy() : new MySQLDefaultDistanceFunctionStrategy());
            this.dataSource = dataSource;
            this.embeddingTable = embeddingTable;
            this.metric = metric == null ? DistanceMetric.COSINE : metric;

        try {
            this.embeddingTable.create(this.dataSource);
            if (indexes != null) {
                for (Index index : indexes) {
                    index.create(this.dataSource, this.embeddingTable);
                }
            }
        } catch (SQLException e) {
            throw new MySQLLangChain4jException("Failed to initialize MySQL embedding table", e);
        }
    }

    /**
     * Creates a builder for configuring a MySQLEmbeddingStoreDataSourceBuilder with a java.sql.DataSource.
     *
     * @return A new builder instance.
     */
    public static MySQLEmbeddingStoreDataSourceBuilder dataSourceBuilder() { return new MySQLEmbeddingStoreDataSourceBuilder(); }

    /**
     * Creates a builder for configuring a MySQLEmbeddingStoreConnectionBuilder with connection parameters.
     * @return A new builder instance.
     */
    public static MySQLEmbeddingStoreConnectionBuilder connectionBuilder() { return new MySQLEmbeddingStoreConnectionBuilder(); }

    @Override
    public String add(Embedding embedding) {
        List<String> ids = addAll(Collections.singletonList(embedding));
        return ids.get(0);
    }

    @Override
    public void add(String id, Embedding embedding) {
        addInternal(id, embedding, null);
    }

    public void add(String id, Embedding embedding, TextSegment textSegment) {
        addInternal(id, embedding, textSegment);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        ensureNotNull(embedding, "embedding");
        ensureNotNull(textSegment, "textSegment");
        List<String> ids = addAll(Collections.singletonList(embedding), Collections.singletonList(textSegment));
        return ids.get(0);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        ensureNotNull(embeddings, "embeddings");
        String[] ids = new String[embeddings.size()];

        String sql = String.format(
                "INSERT INTO %s (%s, %s) VALUES (?, STRING_TO_VECTOR(?))",
                embeddingTable.getQualifiedTableName(), embeddingTable.idColumn(), embeddingTable.embeddingColumn());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < embeddings.size(); i++) {
                String id = randomUUID();
                ids[i] = id;
                Embedding emb = MySQLEmbeddingStoreUtil.ensureIndexNotNull(embeddings, i, "embeddings");

                statement.setString(1, id);
                statement.setString(2, MySQLEmbeddingStoreUtil.vectorToJSONArray(emb.vector()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            logger.error("Failed to add embeddings", e);
            throw new MySQLLangChain4jException("Failed to add embeddings", e);
        }
        return Arrays.asList(ids);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        return doAddAll(null, embeddings, textSegments);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> textSegments) {
        doAddAll(ids, embeddings, textSegments);
    }

    private List<String> doAddAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> textSegments) {
        ensureNotNull(embeddings, "embeddings");
        ensureNotNull(textSegments, "textSegments");
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("The list of embeddings and the list of text segments must have the same size");
        }
        boolean generateIds = false;
        if (ids == null) {
            ids = new ArrayList<>(embeddings.size());
            generateIds = true;
        }

        final String sql = String.format(
                """
                INSERT INTO %s (%s, %s, %s, %s) VALUES (?, STRING_TO_VECTOR(?), ?, ?)
                """,
                embeddingTable.getQualifiedTableName(),
                embeddingTable.idColumn(),
                embeddingTable.embeddingColumn(),
                embeddingTable.textColumn(),
                embeddingTable.metadataColumn());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < embeddings.size(); i++) {
                String id;
                if (generateIds) {
                    id = randomUUID();
                    ids.add(id);
                } else {
                    id = MySQLEmbeddingStoreUtil.ensureIndexNotNull(ids, i, "ids");
                }

                Embedding embedding = MySQLEmbeddingStoreUtil.ensureIndexNotNull(embeddings, i, "embeddings");
                TextSegment textSegment = MySQLEmbeddingStoreUtil.ensureIndexNotNull(textSegments, i, "textSegments");

                statement.setString(1, id);
                statement.setString(2, MySQLEmbeddingStoreUtil.vectorToJSONArray(embedding.vector()));
                statement.setString(3, textSegment.text());
                statement.setString(4, textSegment.metadata() != null ? MySQLEmbeddingStoreUtil.metadataToJson(textSegment.metadata()) : null);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            logger.error("Failed to add embeddings", e);
            throw new MySQLLangChain4jException("Failed to add embeddings", e);
        }
        return ids;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Embedding referenceEmbedding = request.queryEmbedding();
        int maxResults = request.maxResults();
        double minScore = request.minScore();
        // Build optional filter
        Filter filter = request.filter();

        final SQLFilter sqlFilter = SQLFilters.create(filter, embeddingTable::mapMetadataKey);

        // Build distance expression depending on metric and environment (official MySQL vs. GCP and any other custom implementation)
        final String distanceExpr = distanceStrategy.buildDistanceExpression(embeddingTable.embeddingColumn(), this.metric);

        final String sql = String.format(
                """
                SELECT
                 %s distance,
                 %s
                 FROM %s
                 %s
                 ORDER BY distance ASC
                 LIMIT %d
                """,
                distanceExpr,
                String.join(
                        ", ",
                        embeddingTable.idColumn(),
                        embeddingTable.embeddingColumn(),
                        embeddingTable.textColumn(),
                        embeddingTable.metadataColumn()),
                embeddingTable.getQualifiedTableName(),
                sqlFilter.asWhereClause(),
                maxResults);

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, MySQLEmbeddingStoreUtil.vectorToJSONArray(referenceEmbedding.vector()));
            sqlFilter.setParameters(ps, 2);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double distance = rs.getDouble("distance");

                    // Convert distance to similarity score in [0,1]
                    double score = metric.distanceToScore(distance) / 2.0;
                    // Apply minScore filtering
                    if (score >= minScore) {

                        String id = rs.getString(embeddingTable.idColumn());
                        String embeddingJson = rs.getString(embeddingTable.embeddingColumn());
                        Embedding embedding = embeddingJson != null ? new Embedding(MySQLEmbeddingStoreUtil.jsonArrayToFloats(embeddingJson)) : null;
                        String text = rs.getString(embeddingTable.textColumn());
                        String metadataJson = rs.getString(embeddingTable.metadataColumn());
                        TextSegment segment = text != null ? TextSegment.from(text, MySQLEmbeddingStoreUtil.jsonToMetadata(metadataJson)) : null;

                        matches.add(new EmbeddingMatch<>(score, id, embedding, segment));
                    } else {
                        logger.debug("Ignoring result with score {} because it is below minScore {}", score, minScore);
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            throw new MySQLLangChain4jException("Failed to search embeddings", e);
        }
        return new EmbeddingSearchResult<>(matches);
    }

    @Override
    public void removeAll(Filter filter) {

        ensureNotNull(filter, "filter");
        SQLFilter sqlFilter = SQLFilters.create(filter, embeddingTable::mapMetadataKey);

        String sql = "DELETE FROM " + embeddingTable.getQualifiedTableName() + sqlFilter.asWhereClause();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            // Set filter parameters starting from index 1
            sqlFilter.setParameters(statement, 1);

            statement.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to removeAll embeddings", e);
            throw new MySQLLangChain4jException("Failed to removeAll", e);
        }
    }

    @Override
    public void removeAll(Collection<String> ids) {
        ensureNotNull(ids, "ids");
        ensureNotEmpty(ids, "ids");
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = String.format(
                "DELETE FROM %s WHERE %s IN (%s)",
                embeddingTable.getQualifiedTableName(), embeddingTable.idColumn(), placeholders);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            for (String id : ids) {
                ps.setString(index++, id);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to removeAll embeddings", e);
            throw new MySQLLangChain4jException("Failed to removeAll embeddings", e);
        }
    }

    @Override
    public void removeAll() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(String.format("TRUNCATE TABLE %s", embeddingTable.getQualifiedTableName()));
        } catch (SQLException e) {
            logger.error("Failed to removeAll embeddings", e);
            throw new MySQLLangChain4jException("Failed to removeAll embeddings", e);
        }
    }

    private void addInternal(String id, Embedding embedding, TextSegment textSegment) {
        ensureNotNull(id, "id");
        ensureNotNull(embedding, "embedding");
        final boolean withText = textSegment != null;

        String sql = String.format(
                """
                INSERT INTO %s (%s, %s, %s, %s) VALUES (?, STRING_TO_VECTOR(?), ?, ?)
                """,
                embeddingTable.getQualifiedTableName(),
                embeddingTable.idColumn(),
                embeddingTable.embeddingColumn(),
                embeddingTable.textColumn(),
                embeddingTable.metadataColumn());

        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, MySQLEmbeddingStoreUtil.vectorToJSONArray(embedding.vector()));
            if (withText) {
                ps.setString(3, textSegment.text());
                ps.setString(4, textSegment.metadata() != null ? MySQLEmbeddingStoreUtil.metadataToJson(textSegment.metadata()) : null);
            } else {
                ps.setNull(3, Types.VARCHAR);
                ps.setNull(4, Types.VARCHAR);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add embedding", e);
            throw new MySQLLangChain4jException("Failed to add embedding", e);
        }
    }



    // ------------------------- Builder -------------------------

    /**
     * A builder class to configure and create instances of {@code MySQLEmbeddingStore}.
     */
    public static abstract class Builder {
        /**
         * Represents the table used for storing embeddings in the SQL server.
         * Configured within the builder to facilitate the creation of SQLServerEmbeddingStore instances.
         */
        EmbeddingTable embeddingTable;

        /**
         * Represents a collection of database indexes configured for the associated
         * embedding table. These indexes are used to optimize database queries,
         * particularly for the metadata column in cases such as JSON indexing.
         * The indexes can be built and added to the builder dynamically during the
         * process of creating an SQLServerEmbeddingStore instance.
         */
        List<Index> indexes;

        /**
         * Indicates whether GCP (Google Cloud Platform) support is enabled for the Builder.
         * This flag can be set to configure cloud-specific behavior or options during the
         * building process of an embedding store instance.
         */
        Boolean gcp = Boolean.FALSE;

        /**
         * Defines the distance metric to be used for similarity calculations between embeddings.
         * The available options include COSINE and EUCLIDEAN, each offering a different method
         * for determining proximity or relevance based on embeddings' vector representations.
         * This variable is configurable within the builder class to customize the behavior
         * of embedding store objects for specific use cases.
         */
        DistanceMetric metric;

        /**
         * Optional strategy to customize how the SQL distance expression is generated.
         * If not provided, a default will be chosen.
         */
        DistanceFunctionStrategy distanceStrategy;

        /**
         * Sets the embedding table to be used for storing embeddings in the SQL server.
         *
         * @param embeddingTable the {@code EmbeddingTable} object representing the table for storing embeddings
         * @return the {@code Builder} instance to allow for method chaining
         */
        public Builder embeddingTable(EmbeddingTable embeddingTable) {
            this.embeddingTable = embeddingTable;
            return this;
        }

        /**
         * Sets the list of database indexes to be associated with the embedding table.
         * These indexes are used to optimize database queries, particularly for metadata columns.
         *
         * @param indexes the list of {@code Index} objects representing the database indexes to be configured
         * @return the {@code Builder} instance to allow for method chaining
         */
        public Builder indexes(List<Index> indexes) {
            this.indexes = indexes;
            return this;
        }

        /**
         * Adds a single {@link Index} to the list of indexes associated with the embedding table.
         * If the list of indexes is null, it initializes the list before adding the index.
         *
         * @param index the {@link Index} object to be added
         * @return the {@code Builder} instance to allow for method chaining
         */
        public Builder addIndex(Index index) {
            if (this.indexes == null) this.indexes = new ArrayList<>();
            this.indexes.add(index);
            return this;
        }

        /**
         * Configures whether Google Cloud Platform (GCP) support is enabled for the Builder.
         *
         * @param gcp a boolean indicating whether GCP support should be enabled (true) or disabled (false)
         * @return the {@code Builder} instance to allow for method chaining
         */
        public Builder gcp(boolean gcp) {
            this.gcp = gcp;
            return this;
        }

        /**
         * Sets the distance metric to be used for calculations. The distance metric
         * determines how similarity or relevance is calculated between stored and
         * queried embeddings.
         *
         * @param metric the {@link DistanceMetric} to be used, such as {@code COSINE} or {@code EUCLIDEAN}
         * @return the {@code Builder} instance to allow for method chaining
         */
        public Builder metric(DistanceMetric metric) {
            this.metric = metric;
            return this;
        }

        /**
         * Constructs and returns an instance of {@code MySQLEmbeddingStore} based on the current configuration
         * of the {@code Builder}.
         *
         * The {@code build} method finalizes the configuration process and creates a new instance of {@code MySQLEmbeddingStore}.
         * The resulting instance incorporates the embedding table, database indexes, and any additional settings configured
         * in the builder, such as GCP support.
         *
         * @return a new {@code MySQLEmbeddingStore} instance configured with the builder's settings
         */
        public Builder distanceStrategy(DistanceFunctionStrategy strategy) {
            this.distanceStrategy = strategy;
            return this;
        }

        /**
         * Constructs and returns an instance of {@code MySQLEmbeddingStore} based on the current
         * configuration of the {@code Builder}.
         * The {@code build} method finalizes the configuration process and creates a new instance
         * of {@code MySQLEmbeddingStore}, incorporating the embedding table, database indexes,
         * distance metric, GCP support, and distance strategy as configured in the builder.
         *
         * @return a new {@code MySQLEmbeddingStore} instance configured with the specified parameters.
         */
        public abstract MySQLEmbeddingStore build();
    }

    /**
     * Builder class for creating MySQLEmbeddingStore instances.
     */
    public static class MySQLEmbeddingStoreDataSourceBuilder extends Builder {
        private DataSource dataSource;

        private MySQLEmbeddingStoreDataSourceBuilder() {
            super();
        }

        /**
         * Sets the data source.
         *
         * @param dataSource The data source.
         * @return This builder.
         */
        public MySQLEmbeddingStoreDataSourceBuilder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        @Override
        public MySQLEmbeddingStore build() {
            ensureNotNull(dataSource, "dataSource");
            ensureNotNull(embeddingTable, "embeddingTable");
            return new MySQLEmbeddingStore(dataSource, embeddingTable, indexes, gcp, metric, distanceStrategy);
        }
    }

    /**
     * A builder class to configure and create instances of {@code MySQLEmbeddingStore}
     * for connecting to a MySQL database. This class provides methods to set up various
     * connection parameters such as host, port, database name, username, password,
     * and additional connection properties.
     *
     * This builder extends from {@code Builder}, inheriting configuration options
     * for embedding tables and database indexes.
     */
    public static class MySQLEmbeddingStoreConnectionBuilder extends Builder {
        private String host;
        private int port = -1;
        private String database;
        private String username;
        private String password;
        private Properties connectionProperties;

        /**
         * Sets the SQL Server host name or IP address.
         *
         * @param host The host name or IP address of the SQL Server instance
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder setHost(String host) { this.host = host; return this; }
        /**
         * Sets the SQL Server port number.
         *
         * @param port The port number (default is 1433 if not specified)
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder setPort(int port) { this.port = port; return this; }
        /**
         * Sets the database name to connect to.
         *
         * @param database The name of the database
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder setDatabase(String database) { this.database = database; return this; }
        /**
         * Sets the username for database authentication.
         *
         * @param username The username for connecting to the database
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder setUsername(String username) { this.username = username; return this; }
        /**
         * Sets the password for database authentication.
         *
         * @param password The password for connecting to the database
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder setPassword(String password) { this.password = password; return this; }
        /**
         * Sets additional connection properties for the SQL Server DataSource.
         *
         * @param connectionProperties A Properties object containing additional connection settings
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder connectionProperties(Properties connectionProperties) {
            this.connectionProperties = connectionProperties;
            return this;
        }

        /**
         * Sets a single connection property.
         *
         * @param key Property name
         * @param value Property value
         * @return This builder
         */
        public MySQLEmbeddingStoreConnectionBuilder connectionProperty(String key, Object value) {
            if (this.connectionProperties == null) {
                this.connectionProperties = new Properties();
            }
            this.connectionProperties.put(key, value);
            return this;
        }

        @Override
        public MySQLEmbeddingStore build() {
            ensureNotNull(host, "host");
            MysqlDataSource ds = new MysqlDataSource();
            if (port > 0 && database != null && !database.isBlank()) {
                ds.setServerName(host);
                ds.setPort(port);
                ds.setDatabaseName(database);
            } else if (database != null && !database.isBlank()) {
                ds.setURL("jdbc:mysql://" + host + "/" + database);
            } else {
                ds.setServerName(host);
                if (port > 0) ds.setPort(port);
            }
            if (username != null) ds.setUser(username);
            if (password != null) ds.setPassword(password);
            // Apply additional connection properties
            if (connectionProperties != null) {
                applyConnectionProperties(ds, connectionProperties);
            }

            return new MySQLEmbeddingStore(ds, embeddingTable, indexes, gcp, metric, distanceStrategy);
        }

        /**
         * Applies connection properties to the MysqlDataSource using reflection.
         * This allows setting any property supported by MysqlDataSource.
         *
         * @param dataSource The MysqlDataSource to configure
         * @param properties The properties to apply
         */
        private void applyConnectionProperties(MysqlDataSource dataSource, Properties properties) {
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                try {
                    // Try to find a setter method for this property
                    String setterName = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                    java.lang.reflect.Method setter = findSetterMethod(dataSource.getClass(), setterName);
                    if (setter != null) {
                        Object convertedValue = convertValue(setter.getParameterTypes()[0], value);
                        setter.invoke(dataSource, convertedValue);
                        logger.debug("Applied connection property: {} = {}", key, value);
                    } else {
                        logger.warn("Could not find setter method for property: {}", key);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to set connection property: {} = {}", key, value, e);
                }
            }
        }

        /**
         * Finds a setter method with the given name and only a parameter
         */
        private java.lang.reflect.Method findSetterMethod(Class<?> clazz, String methodName) {
            java.lang.reflect.Method[] methods = clazz.getMethods();
            for (java.lang.reflect.Method method : methods) {
                if (method.getName().equals(methodName) && method.getParameterCount() == 1) {
                    return method;
                }
            }
            return null;
        }

        /**
         * Converts a string value to the appropriate type for the setter parameter.
         */
        private Object convertValue(Class<?> targetType, String value) {
            if (targetType == String.class) {
                return value;
            } else if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.parseBoolean(value);
            } else if (targetType == int.class || targetType == Integer.class) {
                return Integer.parseInt(value);
            } else if (targetType == long.class || targetType == Long.class) {
                return Long.parseLong(value);
            } else {
                // Default to string representation
                return value;
            }
        }
    }
}
