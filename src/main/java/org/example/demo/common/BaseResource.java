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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.HttpHeaders;

public abstract class BaseResource {
    protected Map<String, Object> ok(Object data) {
        return Map.of("success", true, "data", data);
    }

    protected Map<String, Object> okMessage(String message) {
        return Map.of("success", true, "message", message);
    }

    protected Map<String, Object> pageResult(List<Map<String, Object>> rows, int total, int page, int pageSize) {
        return Map.of(
                "rows", rows,
                "total", total,
                "page", page,
                "pageSize", pageSize,
                "pages", Math.max(1, (int) Math.ceil(total / (double) pageSize))
        );
    }

    protected Map<String, Object> currentUser(HttpHeaders headers) throws Exception {
        String token = bearerToken(headers);
        if (token == null) {
            return null;
        }
        return queryOne("""
                select u.id, u.username, u.nickname, u.phone, u.address, u.tags,
                       'user' role, u.status, 'user' account_type
                from auth_token t
                join `user` u on t.account_type = 'user' and u.id = t.user_id
                where t.token_hash = ?
                  and t.expire_time > now()
                  and u.status = 1
                union all
                select a.id, a.username, a.nickname, a.phone, '' address, '' tags,
                       a.admin_level role, a.status, 'admin' account_type
                from auth_token t
                join `admin` a on t.account_type = 'admin' and a.id = t.admin_id
                where t.token_hash = ?
                  and t.expire_time > now()
                  and a.status = 1
                limit 1
                """, SecurityUtil.sha256(token), SecurityUtil.sha256(token));
    }

    protected Map<String, Object> requireUser(HttpHeaders headers, Integer fallbackUserId) throws Exception {
        Map<String, Object> user = currentUser(headers);
        if (user != null) {
            return user;
        }
        fallbackUserId = null;
        throw new ApiException(401, "请先登录");
    }

    protected Map<String, Object> requireManager(HttpHeaders headers, Integer fallbackUserId, String message) throws Exception {
        Map<String, Object> user = requireUser(headers, fallbackUserId);
        if (!"admin".equals(user.get("role")) && !"super_admin".equals(user.get("role"))) {
            throw new ApiException(403, message);
        }
        return user;
    }

    protected Map<String, Object> requireSuperAdmin(HttpHeaders headers, Integer fallbackUserId) throws Exception {
        Map<String, Object> user = requireUser(headers, fallbackUserId);
        if (!"super_admin".equals(user.get("role"))) {
            throw new ApiException(403, "只有超级管理员可以管理用户权限");
        }
        return user;
    }

    protected void requireSameUser(Map<String, Object> user, int userId) {
        int currentId = ((Number) user.get("id")).intValue();
        if (!"user".equals(user.get("role")) || currentId != userId) {
            throw new ApiException(403, "只能操作自己的数据");
        }
    }

    protected String createAuthToken(int userId) throws Exception {
        return createAuthToken("user", userId);
    }

    protected String createAuthToken(String accountType, int accountId) throws Exception {
        String token = SecurityUtil.newToken();
        if ("admin".equals(accountType)) {
            update("delete from auth_token where account_type = 'admin' and admin_id = ? and expire_time < now()", accountId);
        } else {
            update("delete from auth_token where account_type = 'user' and user_id = ? and expire_time < now()", accountId);
        }
        insert("""
                insert into auth_token(account_type, user_id, admin_id, token_hash, expire_time, create_time)
                values(?, ?, ?, ?, date_add(now(), interval 7 day), now())
                """, accountType,
                "user".equals(accountType) ? accountId : null,
                "admin".equals(accountType) ? accountId : null,
                SecurityUtil.sha256(token));
        return token;
    }

    protected void revokeAllAuthTokens(int userId) throws Exception {
        revokeAllAuthTokens("user", userId);
    }

    protected void revokeAllAuthTokens(String accountType, int accountId) throws Exception {
        if ("admin".equals(accountType)) {
            update("delete from auth_token where account_type = 'admin' and admin_id = ?", accountId);
        } else {
            update("delete from auth_token where account_type = 'user' and user_id = ?", accountId);
        }
    }

    protected void ensureLoginAllowed(String username) throws Exception {
        Map<String, Object> row = queryOne("""
                select failed_count,
                       timestampdiff(second, now(), locked_until) remaining_seconds,
                       case when locked_until is not null and locked_until > now() then 1 else 0 end locked
                from login_attempt
                where username = ?
                """, username);
        if (row == null || ((Number) row.get("locked")).intValue() == 0) {
            return;
        }
        int seconds = Math.max(1, ((Number) row.get("remaining_seconds")).intValue());
        throw new ApiException(429, "登录失败次数过多，请 " + Math.ceil(seconds / 60.0) + " 分钟后再试");
    }

    protected void recordLoginFailure(String username) throws Exception {
        Map<String, Object> row = queryOne("select failed_count from login_attempt where username = ?", username);
        int failedCount = row == null ? 1 : ((Number) row.get("failed_count")).intValue() + 1;
        insert("""
                insert into login_attempt(username, failed_count, last_failed_time, locked_until)
                values(?, ?, now(), if(? >= 5, date_add(now(), interval 15 minute), null))
                on duplicate key update
                failed_count = values(failed_count),
                last_failed_time = values(last_failed_time),
                locked_until = values(locked_until)
                """, username, failedCount, failedCount);
    }

    protected void recordLoginSuccess(String username) throws Exception {
        insert("""
                insert into login_attempt(username, failed_count, last_success_time, locked_until)
                values(?, 0, now(), null)
                on duplicate key update
                failed_count = 0,
                last_success_time = values(last_success_time),
                locked_until = null
                """, username);
    }

    protected void revokeAuthToken(HttpHeaders headers) throws Exception {
        String token = bearerToken(headers);
        if (token != null) {
            update("delete from auth_token where token_hash = ?", SecurityUtil.sha256(token));
        }
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

    protected Map<String, Object> pagedQuery(String countSql, String rowsSql, Integer page, Integer pageSize,
                                             Object... params) throws Exception {
        int safePage = sanitizePage(page);
        int safePageSize = sanitizePageSize(pageSize);
        int offset = (safePage - 1) * safePageSize;
        Map<String, Object> totalRow = queryOne(countSql, params);
        int total = totalRow == null ? 0 : ((Number) totalRow.get("total")).intValue();
        Object[] pageParams = Arrays.copyOf(params, params.length + 2);
        pageParams[params.length] = safePageSize;
        pageParams[params.length + 1] = offset;
        return pageResult(query(rowsSql + " limit ? offset ?", pageParams), total, safePage, safePageSize);
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

    protected int sanitizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    protected int sanitizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 12;
        }
        return Math.min(pageSize, 100);
    }

    protected void fill(PreparedStatement statement, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private String bearerToken(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorization.substring(prefix.length()).trim();
        return token.isBlank() ? null : token;
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

