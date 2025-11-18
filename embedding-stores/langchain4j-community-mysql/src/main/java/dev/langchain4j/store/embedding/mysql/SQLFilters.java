package dev.langchain4j.store.embedding.mysql;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.*;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Utility class for creating SQL filters from high-level filter abstractions.
 * This class provides methods to transform logical and comparison-based
 * filters into SQL-compatible representations, as well as applying parameterized
 * values to a prepared statement.
 */
public class SQLFilters {

    /**
     * An SQLFilter that applies no filtering. The {@link SQLFilter#toSQL()} method of this filter returns an empty
     * string.
     */
    public static final SQLFilter EMPTY = new SQLFilter() {
        @Override
        public String toSQL() { return ""; }
        @Override
        public String asWhereClause() { return ""; }
        @Override
        public int setParameters(PreparedStatement preparedStatement, int parameterIndex) { return 0; }
    };

    /**
     * Creates a SQLFilter representation of the given Filter.
     *
     * @param filter The input filter to be converted to a SQLFilter. Can be null.
     * @param keyMapper A function that maps the filter key and its associated class type to a SQL string. Not null.
     * @return A SQLFilter instance representing the input filter. Returns SQLFilters.EMPTY if the filter is null.
     * @throws UnsupportedOperationException If the given filter type is not supported.
     */
    public static SQLFilter create(Filter filter, BiFunction<String, Class<?>, String> keyMapper) {
        if (filter == null) {
            return EMPTY;
        }
        if (filter instanceof IsEqualTo isEqualTo) {
            return createComparisonFilter(isEqualTo.key(), isEqualTo.comparisonValue(), "=", keyMapper);
        } else if (filter instanceof IsNotEqualTo isNotEqualTo) {
            return createIsNotEqualToFilter(isNotEqualTo.key(), isNotEqualTo.comparisonValue(), "<>", keyMapper);
        } else if (filter instanceof IsGreaterThan isGreaterThan) {
            return createComparisonFilter(isGreaterThan.key(), isGreaterThan.comparisonValue(), ">", keyMapper);
        } else if (filter instanceof IsGreaterThanOrEqualTo isGreaterThanOrEqualTo) {
            return createComparisonFilter(isGreaterThanOrEqualTo.key(), isGreaterThanOrEqualTo.comparisonValue(), ">=", keyMapper);
        } else if (filter instanceof final IsLessThan isLessThan) {
            return createComparisonFilter(isLessThan.key(), isLessThan.comparisonValue(), "<", keyMapper);
        } else if (filter instanceof final IsLessThanOrEqualTo isLessThanOrEqualTo) {
            return createComparisonFilter(isLessThanOrEqualTo.key(), isLessThanOrEqualTo.comparisonValue(), "<=", keyMapper);
        } else if (filter instanceof final IsIn isIn) {
            return createInFilter(isIn.key(), isIn.comparisonValues(), keyMapper);
        } else if (filter instanceof final IsNotIn isNotIn) {
            return createNotInFilter(isNotIn.key(), isNotIn.comparisonValues(), keyMapper);
        } else if (filter instanceof final And and) {
            return createLogicalFilter(and.left(), and.right(), "AND", keyMapper);
        } else if (filter instanceof final Or or) {
            return createLogicalFilter(or.left(), or.right(), "OR", keyMapper);
        } else if (filter instanceof final Not not) {
            SQLFilter expression = create(not.expression(), keyMapper);
            return createNotFilter(expression);
        } else {
            throw new UnsupportedOperationException("Unsupported filter type: " + filter.getClass().getSimpleName());
        }
    }

    private static SQLFilter createComparisonFilter(String key, Object value, String operator, BiFunction<String, Class<?>, String> keyMapper) {
        return new SQLFilter() {
            @Override
            public String toSQL() {
                Class<?> valueClass = value != null ? value.getClass() : String.class;
                final String columnExpression = keyMapper.apply(key, valueClass);
                return columnExpression + " IS NOT NULL AND " + columnExpression + ' ' + operator + " ? ";
            }

            @Override
            public int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException {
                preparedStatement.setObject(parameterIndex, value);
                return 1;
            }
        };
    }

    private static SQLFilter createIsNotEqualToFilter(String key, Object value, String operator, BiFunction<String, Class<?>, String> keyMapper) {
        return new SQLFilter() {
            @Override
            public String toSQL() {
                Class<?> valueClass = value != null ? value.getClass() : String.class;
                final String columnExpression = keyMapper.apply(key, valueClass);
                // IsNotEqualTo should be true if the key is null or the value is not the given values
                return '(' + columnExpression + " IS NULL OR " + columnExpression + " <> ?)";
            }

            @Override
            public int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException {
                preparedStatement.setObject(parameterIndex, value);
                return 1;
            }
        };
    }

    private static SQLFilter createInFilter(String key, Collection<?> values, BiFunction<String, Class<?>, String> keyMapper) {
        return new SQLFilter() {
            @Override
            public String toSQL() {
                if (values.isEmpty()) {
                    return "1=0"; // Always false
                }
                Class<?> valueClass = values.iterator().next().getClass();
                String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
                final String columnExpression = keyMapper.apply(key, valueClass);
                return columnExpression + " IS NOT NULL AND " + columnExpression + " IN (" + placeholders + ')';
            }

            @Override
            public int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException {
                int index = parameterIndex;
                for (Object value : values) {
                    preparedStatement.setObject(index++, value);
                }
                return values.size();
            }
        };
    }

    private static SQLFilter createNotInFilter(String key, Collection<?> values, BiFunction<String, Class<?>, String> keyMapper) {
        return new SQLFilter() {
            @Override
            public String toSQL() {
                if (values.isEmpty()) {
                    return "1=1"; // Always true
                }
                Class<?> valueClass = values.iterator().next().getClass();
                String columnExpression = keyMapper.apply(key, valueClass);
                String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
                // IsNotIn should be true if the key is null or the value is not in the given values
                return '(' + columnExpression + " IS NULL OR " + columnExpression + " NOT IN (" + placeholders + "))";
            }

            @Override
            public int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException {
                int index = parameterIndex;
                for (Object value : values) {
                    preparedStatement.setObject(index++, value);
                }
                return values.size();
            }
        };
    }

    private static SQLFilter createLogicalFilter(Filter left, Filter right, String operator, BiFunction<String, Class<?>, String> keyMapper) {
        SQLFilter leftFilter = create(left, keyMapper);
        SQLFilter rightFilter = create(right, keyMapper);

        return new SQLFilter() {
            @Override
            public String toSQL() {
                return "(" + leftFilter.toSQL() + " " + operator + " " + rightFilter.toSQL() + ")";
            }

            @Override
            public int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException {
                int leftParams = leftFilter.setParameters(preparedStatement, parameterIndex);
                int rightParams = rightFilter.setParameters(preparedStatement, parameterIndex + leftParams);
                return leftParams + rightParams;
            }
        };
    }

    private static SQLFilter createNotFilter(SQLFilter expression) {
        return new SQLFilter() {
            @Override
            public String toSQL() { return "NOT (" + expression.toSQL() + ")"; }

            @Override
            public int setParameters(PreparedStatement preparedStatement, int parameterIndex) throws SQLException {
                return expression.setParameters(preparedStatement, parameterIndex);
            }
        };
    }
}
