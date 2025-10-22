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

public class SQLFilters {

    public static final SQLFilter EMPTY = new SQLFilter() {
        @Override
        public String toSQL() { return ""; }
        @Override
        public String asWhereClause() { return ""; }
        @Override
        public int setParameters(PreparedStatement preparedStatement, int parameterIndex) { return 0; }
    };

    public static SQLFilter create(Filter filter, BiFunction<String, Class<?>, String> keyMapper) {
        if (filter == null) {
            return EMPTY;
        }
        if (filter instanceof IsEqualTo) {
            IsEqualTo isEqualTo = (IsEqualTo) filter;
            return createComparisonFilter(isEqualTo.key(), isEqualTo.comparisonValue(), "=", keyMapper);
        } else if (filter instanceof IsNotEqualTo) {
            IsNotEqualTo isNotEqualTo = (IsNotEqualTo) filter;
            return createComparisonFilter(isNotEqualTo.key(), isNotEqualTo.comparisonValue(), "<>", keyMapper);
        } else if (filter instanceof IsGreaterThan) {
            IsGreaterThan isGreaterThan = (IsGreaterThan) filter;
            return createComparisonFilter(isGreaterThan.key(), isGreaterThan.comparisonValue(), ">", keyMapper);
        } else if (filter instanceof IsGreaterThanOrEqualTo) {
            IsGreaterThanOrEqualTo isGreaterThanOrEqualTo = (IsGreaterThanOrEqualTo) filter;
            return createComparisonFilter(isGreaterThanOrEqualTo.key(), isGreaterThanOrEqualTo.comparisonValue(), ">=", keyMapper);
        } else if (filter instanceof IsLessThan) {
            IsLessThan isLessThan = (IsLessThan) filter;
            return createComparisonFilter(isLessThan.key(), isLessThan.comparisonValue(), "<", keyMapper);
        } else if (filter instanceof IsLessThanOrEqualTo) {
            IsLessThanOrEqualTo isLessThanOrEqualTo = (IsLessThanOrEqualTo) filter;
            return createComparisonFilter(isLessThanOrEqualTo.key(), isLessThanOrEqualTo.comparisonValue(), "<=", keyMapper);
        } else if (filter instanceof IsIn) {
            IsIn isIn = (IsIn) filter;
            return createInFilter(isIn.key(), isIn.comparisonValues(), keyMapper);
        } else if (filter instanceof IsNotIn) {
            IsNotIn isNotIn = (IsNotIn) filter;
            return createNotInFilter(isNotIn.key(), isNotIn.comparisonValues(), keyMapper);
        } else if (filter instanceof And) {
            And and = (And) filter;
            return createLogicalFilter(and.left(), and.right(), "AND", keyMapper);
        } else if (filter instanceof Or) {
            Or or = (Or) filter;
            return createLogicalFilter(or.left(), or.right(), "OR", keyMapper);
        } else if (filter instanceof Not) {
            Not not = (Not) filter;
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
                return keyMapper.apply(key, valueClass) + " " + operator + " ?";
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
                return keyMapper.apply(key, valueClass) + " IN (" + placeholders + ")";
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
                String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
                return keyMapper.apply(key, valueClass) + " NOT IN (" + placeholders + ")";
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
