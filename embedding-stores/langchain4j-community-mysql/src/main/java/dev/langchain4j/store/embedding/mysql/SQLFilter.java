package dev.langchain4j.store.embedding.mysql;

import dev.langchain4j.store.embedding.filter.Filter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * A SQL expression which evaluates to a boolean result for MySQL.
 * See SQL Server counterpart for detailed documentation.
 */
interface SQLFilter {

    String toSQL();

    default String asWhereClause() {
        String sql = toSQL();
        return sql == null || sql.isBlank() ? "" : " WHERE " + sql;
    }

    int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException;
}
