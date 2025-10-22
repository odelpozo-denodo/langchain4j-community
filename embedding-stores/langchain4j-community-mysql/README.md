# langchain4j-community-mysql

MySQL 9+ Embedding Store for LangChain4j using native VECTOR and JSON types and the DISTANCE() function.

## Features
- Stores embeddings in a MySQL table using native VECTOR(dim) FLOAT32 column
- Stores optional text and JSON metadata
- Similarity search using MySQL's DISTANCE(... USING COSINE)

## Requirements
- MySQL 9.0+ with VECTOR data type support
- Java 11+

## Installation
Add the module to your Maven build by using the parent `langchain4j-community` BOM or include directly:

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-community-mysql</artifactId>
  <version>${langchain4j.version}</version>
</dependency>
```

This module uses MySQL Connector/J (mysql-connector-j).

## Table Schema
By default, the store creates a table like the following (if not present):

```sql
CREATE TABLE IF NOT EXISTS embeddings (
  id VARCHAR(64) PRIMARY KEY,
  embedding VECTOR(1536) FLOAT32 NOT NULL,
  text TEXT NULL,
  metadata JSON NULL
);
```

The dimension (e.g. 1536) is provided in the builder.

## Usage

Option A: provide a DataSource

```java
import com.mysql.cj.jdbc.MysqlDataSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.mysql.MySQLEmbeddingStore;

import javax.sql.DataSource;

// Configure your DataSource
MysqlDataSource ds = new MysqlDataSource();
ds.setURL("jdbc:mysql://localhost:3306/mydb");
ds.setUser("user");
ds.setPassword("password");

// Create the store (specify vector dimension)
MySQLEmbeddingStore store = MySQLEmbeddingStore.builder()
    .dataSource(ds)
    .tableName("embeddings")
    .dimension(1536)
    .build();
```

Option B: provide connection properties (the builder will create the DataSource)

```java
MySQLEmbeddingStore store = MySQLEmbeddingStore.builder()
    .setHost("localhost")
    .setPort(3306)
    .setDatabase("mydb")
    .setUsername("user")
    .setPassword("password")
    .tableName("embeddings")
    .dimension(1536)
    .build();
```

Then use the store:

```java
// Add embeddings
float[] vec = new float[]{0.1f, 0.2f, 0.3f};
String id = store.add(new Embedding(vec));

// Add embedding with text and metadata
TextSegment segment = TextSegment.from("Hello world");
String id2 = store.add(new Embedding(vec), segment);

// Search
EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
    .queryEmbedding(new Embedding(vec))
    .maxResults(5)
    .minScore(0.75)
    .build();
EmbeddingSearchResult<TextSegment> result = store.search(req);
result.matches().forEach(match -> {
    System.out.println("score=" + match.score() + ", id=" + match.id());
});
```

## Indexing JSON metadata
You can create functional indexes on JSON metadata keys using the MySQL Index API, similar to the Oracle module:

```java
import static dev.langchain4j.store.embedding.mysql.Index.jsonIndexBuilder;

MySQLEmbeddingStore store = MySQLEmbeddingStore.builder()
    .dataSource(ds)
    .tableName("embeddings")
    .dimension(1536)
    .addIndex(
        jsonIndexBuilder()
            .name("idx_embeddings_metadata_country_city")
            .isUnique(false)
            .createOption(dev.langchain4j.store.embedding.mysql.IndexBuilder.CreateOption.CREATE_IF_NOT_EXISTS)
            .key("country", String.class, dev.langchain4j.store.embedding.mysql.JSONIndexBuilder.Order.ASC)
            .key("city", String.class, dev.langchain4j.store.embedding.mysql.JSONIndexBuilder.Order.ASC)
            .build()
    )
    .build();
```

## Notes
- This implementation focuses on create/insert/search/delete capabilities.
- For performance, consider adding secondary indexes appropriate for your workload.
