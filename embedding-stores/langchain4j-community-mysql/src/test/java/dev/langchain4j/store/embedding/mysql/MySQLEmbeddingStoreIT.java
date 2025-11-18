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
import dev.langchain4j.store.embedding.mysql.CreateOption;

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


}
