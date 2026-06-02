package org.example.demo.common;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseResource {
    protected Map<String, Object> ok(Object data) {
        return Map.of("success", true, "data", data);
    }

    protected Map<String, Object> okMessage(String message) {
        return Map.of("success", true, "message", message);
    }

    protected Map<String, Object> queryOne(String sql, Object... params) throws Exception {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected List<Map<String, Object>> query(String sql, Object... params) throws Exception {
        try (Connection connection = Db.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            fill(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metaData = resultSet.getMetaData();
                int count = metaData.getColumnCount();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= count; i++) {
                        row.put(metaData.getColumnLabel(i), normalize(resultSet.getObject(i)));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    protected int update(String sql, Object... params) throws Exception {
        try (Connection connection = Db.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            fill(statement, params);
            return statement.executeUpdate();
        }
    }

    protected int insert(String sql, Object... params) throws Exception {
        try (Connection connection = Db.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            fill(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
            return 0;
        }
    }

    protected void requireRows(int rows, String message) {
        if (rows == 0) {
            throw new ApiException(404, message);
        }
    }

    protected void require(boolean expression, String message) {
        if (!expression) {
            throw new ApiException(400, message);
        }
    }

    protected void fill(PreparedStatement statement, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private Object normalize(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value;
    }
}

