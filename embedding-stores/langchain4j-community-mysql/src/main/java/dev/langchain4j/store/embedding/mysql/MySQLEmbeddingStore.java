package dev.langchain4j.store.embedding.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.*;

import static dev.langchain4j.internal.Utils.randomUUID;
import static dev.langchain4j.internal.ValidationUtils.ensureNotEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/**
 * MySQL implementation of EmbeddingStore using MySQL 9+ native VECTOR type and DISTANCE() function.
 */
public class MySQLEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private DataSource dataSource;
    private final EmbeddingTable embeddingTable;

    private MySQLEmbeddingStore(DataSource dataSource, EmbeddingTable embeddingTable, java.util.List<Index> indexes) {
        this.dataSource = dataSource;
        this.embeddingTable = embeddingTable;
        try {
            this.embeddingTable.create(this.dataSource);
            if (indexes != null) {
                for (Index index : indexes) {
                    index.create(this.dataSource, this.embeddingTable);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize MySQL embedding table", e);
        }
    }

    public static DataSourceBuilder builder() { return new DataSourceBuilder(); }

    public static DataSourceBuilder dataSourceBuilder() { return new DataSourceBuilder(); }

    public static ConnectionBuilder connectionBuilder() { return new ConnectionBuilder(); }

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

        final String sql = "INSERT INTO " + embeddingTable.name() + "(" +
                String.join(", ", embeddingTable.idColumn(), embeddingTable.embeddingColumn()) +
                ") VALUES (?, CAST(? AS VECTOR(" + embeddingTable.dimension() + ") FLOAT32))";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < embeddings.size(); i++) {
                String id = randomUUID();
                ids[i] = id;
                statement.setString(1, id);
                Embedding emb = ensureIndexNotNull(embeddings, i, "embeddings");
                statement.setString(2, vectorToJSONArray(emb.vector()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw uncheckSQLException(e);
        }
        return Arrays.asList(ids);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        ensureNotNull(embeddings, "embeddings");
        ensureNotNull(textSegments, "textSegments");
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("The list of embeddings and the list of text segments must have the same size");
        }
        String[] ids = new String[embeddings.size()];

        final String sql = "INSERT INTO " + embeddingTable.name() + "(" +
                String.join(", ", embeddingTable.idColumn(), embeddingTable.embeddingColumn(), embeddingTable.textColumn(), embeddingTable.metadataColumn()) +
                ") VALUES (?, CAST(? AS VECTOR(" + embeddingTable.dimension() + ") FLOAT32), ?, ?)";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < embeddings.size(); i++) {
                String id = randomUUID();
                ids[i] = id;
                statement.setString(1, id);

                Embedding embedding = ensureIndexNotNull(embeddings, i, "embeddings");
                TextSegment textSegment = ensureIndexNotNull(textSegments, i, "textSegments");

                statement.setString(2, vectorToJSONArray(embedding.vector()));
                statement.setString(3, textSegment.text());
                statement.setString(4, textSegment.metadata() != null ? metadataToJson(textSegment.metadata()) : null);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw uncheckSQLException(e);
        }
        return Arrays.asList(ids);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Embedding query = request.queryEmbedding();
        int maxResults = request.maxResults();
        double minScore = request.minScore();

        // Build optional filter
        Filter filter = request.filter();
        SQLFilter sqlFilter = SQLFilters.create(filter, embeddingTable::mapMetadataKey);

        // Cosine distance -> similarity = 1 - distance
        String sql = "SELECT " +
                "DISTANCE(" + embeddingTable.embeddingColumn() + ", CAST(? AS VECTOR(" + embeddingTable.dimension() + ") FLOAT32) USING COSINE) AS distance, " +
                String.join(", ", embeddingTable.idColumn(), embeddingTable.embeddingColumn(), embeddingTable.textColumn(), embeddingTable.metadataColumn()) +
                " FROM " + embeddingTable.name() +
                sqlFilter.asWhereClause() +
                " ORDER BY distance ASC LIMIT ?";

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int paramIndex = 1;
            ps.setString(paramIndex++, vectorToJSONArray(query.vector()));
            int filterParams = sqlFilter.setParameters(ps, paramIndex);
            paramIndex += filterParams;
            ps.setInt(paramIndex, maxResults);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double distance = rs.getDouble("distance");
                    double score = 1.0 - distance;
                    if (score < minScore) continue;
                    String id = rs.getString(embeddingTable.idColumn());
                    String embeddingJson = rs.getString(embeddingTable.embeddingColumn());
                    Embedding embedding = embeddingJson != null ? new Embedding(jsonArrayToFloats(embeddingJson)) : null;
                    String text = rs.getString(embeddingTable.textColumn());
                    String metadataJson = rs.getString(embeddingTable.metadataColumn());
                    TextSegment segment = text != null ? TextSegment.from(text, jsonToMetadata(metadataJson)) : null;
                    matches.add(new EmbeddingMatch<>(score, id, embedding, segment));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search embeddings", e);
        }
        return new EmbeddingSearchResult<>(matches);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        ensureNotNull(ids, "ids");
        ensureNotEmpty(ids, "ids");
        final String sql = "DELETE FROM " + embeddingTable.name() + " WHERE " + embeddingTable.idColumn() + " IN (" +
                String.join(", ", Collections.nCopies(ids.size(), "?")) + ")";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (String id : ids) ps.setString(i++, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw uncheckSQLException(e);
        }
    }

    @Override
    public void removeAll() {
        final String sql = "TRUNCATE TABLE " + embeddingTable.name();
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw uncheckSQLException(e);
        }
    }

    // ------------------------- Helpers -------------------------

    private void addInternal(String id, Embedding embedding, TextSegment textSegment) {
        ensureNotNull(id, "id");
        ensureNotNull(embedding, "embedding");
        final boolean withText = textSegment != null;
        final String base = "INSERT INTO " + embeddingTable.name() + "(" + embeddingTable.idColumn() + ", " + embeddingTable.embeddingColumn();
        final String cols = withText ? ", " + embeddingTable.textColumn() + ", " + embeddingTable.metadataColumn() : "";
        final String sql = base + cols + ") VALUES (?, CAST(? AS VECTOR(" + embeddingTable.dimension() + ") FLOAT32)" + (withText ? ", ?, ?" : "") + ")";

        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, vectorToJSONArray(embedding.vector()));
            if (withText) {
                ps.setString(3, textSegment.text());
                ps.setString(4, textSegment.metadata() != null ? metadataToJson(textSegment.metadata()) : null);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw uncheckSQLException(e);
        }
    }

    private static <T> T ensureIndexNotNull(List<T> list, int index, String name) {
        T item = list.get(index);
        if (item == null) throw new IllegalArgumentException(name + " contains null at index " + index);
        return item;
    }

    private static String vectorToJSONArray(float[] vector) {
        // Represent vector as JSON array string, e.g., [0.1, 0.2]
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            // ensure float precision formatting acceptable for MySQL FLOAT32
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String metadataToJson(Metadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata.toMap());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize metadata to JSON", e);
        }
    }

    private static float[] jsonArrayToFloats(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return null;
        // Very small parser for a flat JSON float array: [0.1,0.2,...]
        String s = jsonArray.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) return new float[0];
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Metadata jsonToMetadata(String json) {
        if (json == null || json.isBlank()) return Metadata.from(new HashMap<>());
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return Metadata.from(map);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize metadata JSON", e);
        }
    }

    private static RuntimeException uncheckSQLException(SQLException e) {
        return new RuntimeException(e.getMessage(), e);
    }

    // ------------------------- Builder -------------------------

    public static abstract class Builder {
        protected String tableName = "embeddings";
        protected String idColumn = "id";
        protected String embeddingColumn = "embedding";
        protected String textColumn = "text";
        protected String metadataColumn = "metadata";
        protected Integer dimension;
        protected java.util.List<Index> indexes;

        public Builder tableName(String tableName) { this.tableName = tableName; return this; }
        public Builder idColumn(String idColumn) { this.idColumn = idColumn; return this; }
        public Builder embeddingColumn(String embeddingColumn) { this.embeddingColumn = embeddingColumn; return this; }
        public Builder textColumn(String textColumn) { this.textColumn = textColumn; return this; }
        public Builder metadataColumn(String metadataColumn) { this.metadataColumn = metadataColumn; return this; }
        public Builder dimension(int dimension) { this.dimension = dimension; return this; }
        public Builder indexes(java.util.List<Index> indexes) { this.indexes = indexes; return this; }
        public Builder addIndex(Index index) { if (this.indexes == null) this.indexes = new ArrayList<>(); this.indexes.add(index); return this; }

        protected EmbeddingTable buildEmbeddingTable() {
            ensureNotNull(dimension, "dimension");
            return EmbeddingTable.builder()
                    .name(tableName)
                    .idColumn(idColumn)
                    .embeddingColumn(embeddingColumn)
                    .textColumn(textColumn)
                    .metadataColumn(metadataColumn)
                    .dimension(dimension)
                    .build();
        }

        protected java.util.List<Index> getIndexes() { return indexes; }

        public abstract MySQLEmbeddingStore build();
    }

    // Builder that accepts a provided DataSource
    public static class DataSourceBuilder extends Builder {
        private DataSource dataSource;
        public DataSourceBuilder dataSource(DataSource dataSource) { this.dataSource = dataSource; return this; }

        @Override
        public MySQLEmbeddingStore build() {
            ensureNotNull(dataSource, "dataSource");
            EmbeddingTable table = buildEmbeddingTable();
            return new MySQLEmbeddingStore(dataSource, table, getIndexes());
        }
    }

    // Builder that constructs its own MysqlDataSource from connection properties
    public static class ConnectionBuilder extends Builder {
        private String host;
        private int port = -1;
        private String database;
        private String username;
        private String password;

        public ConnectionBuilder setHost(String host) { this.host = host; return this; }
        public ConnectionBuilder setPort(int port) { this.port = port; return this; }
        public ConnectionBuilder setDatabase(String database) { this.database = database; return this; }
        public ConnectionBuilder setUsername(String username) { this.username = username; return this; }
        public ConnectionBuilder setPassword(String password) { this.password = password; return this; }

        @Override
        public MySQLEmbeddingStore build() {
            ensureNotNull(host, "host");
            com.mysql.cj.jdbc.MysqlDataSource ds = new com.mysql.cj.jdbc.MysqlDataSource();
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
            EmbeddingTable table = buildEmbeddingTable();
            return new MySQLEmbeddingStore(ds, table, getIndexes());
        }
    }
}
