package dev.langchain4j.store.embedding.mysql;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static dev.langchain4j.store.embedding.mysql.IndexBuilder.CreateOption;

@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
public class MySQLEmbeddingStoreIT {

    @Container
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:9.0")
            .withUsername("test")
            .withPassword("test")
            .withDatabaseName("testdb");

    private DataSource ds;

    @BeforeAll
    void setupDataSource() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(mysql.getJdbcUrl());
        dataSource.setUser(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        this.ds = dataSource;
    }

    private String uniqueTableName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private MySQLEmbeddingStore newStore(String table, int dim) {
        return MySQLEmbeddingStore.builder()
                .dataSource(ds)
                .tableName(table)
                .dimension(dim)
                .build();
    }

    @Test
    void createTableOnBuild() throws Exception {
        String table = uniqueTableName("embeddings");
        MySQLEmbeddingStore store = newStore(table, 4);
        // verify
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void createTableWithJsonIndex() throws Exception {
        String table = uniqueTableName("emb_idx");
        MySQLEmbeddingStore store = MySQLEmbeddingStore.builder()
                .dataSource(ds)
                .tableName(table)
                .dimension(4)
                .addIndex(Index.jsonIndexBuilder()
                        .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                        .key("country", String.class, JSONIndexBuilder.Order.ASC)
                        .key("city", String.class, JSONIndexBuilder.Order.ASC)
                        .build())
                .build();

        String expectedIndexPrefix = table + "_METADATA_COUNTRY_CITY";
        // verify index created
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = false;
                while (rs.next()) {
                    String idx = rs.getString(1);
                    if (idx != null && idx.toUpperCase().startsWith(expectedIndexPrefix.toUpperCase())) {
                        found = true; break;
                    }
                }
                assertTrue(found, "Expected JSON index not found for table " + table);
            }
        }
    }

    @Test
    void insertSingleAndMultiple() throws Exception {
        String table = uniqueTableName("emb_ins");
        MySQLEmbeddingStore store = newStore(table, 4);

        float[] v1 = new float[]{1f, 0f, 0f, 0f};
        float[] v2 = new float[]{0f, 1f, 0f, 0f};
        float[] v3 = new float[]{0f, 0f, 1f, 0f};

        // single without text/metadata
        String id1 = store.add(new Embedding(v1));

        // single with text/metadata
        Metadata m2 = Metadata.from(Map.of("country", "ES", "city", "Madrid"));
        String id2 = store.add(new Embedding(v2), TextSegment.from("hola", m2));

        // batch
        List<String> ids = store.addAll(Arrays.asList(new Embedding(v2), new Embedding(v3)),
                Arrays.asList(TextSegment.from("adios", Metadata.from(Map.of("country", "ES"))),
                              TextSegment.from("ciao", Metadata.from(Map.of("country", "IT")))));

        // verify counts
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1));
        }

        assertNotNull(id1); assertNotNull(id2); assertEquals(2, ids.size());
    }

    @Test
    void searchWithMetadataFilters() {
        String table = uniqueTableName("emb_search");
        MySQLEmbeddingStore store = newStore(table, 3);

        float[] v_es = new float[]{0.9f, 0.1f, 0f};
        float[] v_it = new float[]{0.1f, 0.9f, 0f};
        float[] v_mx = new float[]{0.8f, 0.2f, 0f};

        store.add(new Embedding(v_es), TextSegment.from("hola es", Metadata.from(Map.of("country", "ES"))));
        store.add(new Embedding(v_it), TextSegment.from("ciao", Metadata.from(Map.of("country", "IT"))));
        store.add(new Embedding(v_mx), TextSegment.from("hola mx", Metadata.from(Map.of("country", "MX"))));

        // query close to ES vector
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(new Embedding(new float[]{0.95f, 0.05f, 0f}))
                .maxResults(5)
                .minScore(0.0)
                .filter(new IsIn("country", Arrays.asList("ES", "MX")))
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        assertTrue(result.matches().size() >= 1);
        List<String> texts = result.matches().stream()
                .map(m -> m.embedded() != null ? m.embedded().text() : null)
                .collect(Collectors.toList());
        assertTrue(texts.stream().anyMatch(t -> "hola es".equals(t) || "hola mx".equals(t)));
        assertFalse(texts.stream().anyMatch("ciao"::equals));
    }

    @Test
    void removeByIdsAndRemoveAll() throws Exception {
        String table = uniqueTableName("emb_del");
        MySQLEmbeddingStore store = newStore(table, 3);
        String id1 = store.add(new Embedding(new float[]{1f,0f,0f}));
        String id2 = store.add(new Embedding(new float[]{0f,1f,0f}));
        String id3 = store.add(new Embedding(new float[]{0f,0f,1f}));

        store.removeAll(List.of(id1, id3));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }

        store.removeAll();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }
}
