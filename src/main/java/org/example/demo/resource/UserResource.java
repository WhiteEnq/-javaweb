package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;
import org.example.demo.common.SecurityUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("actorId") Integer actorId,
                                    @QueryParam("status") String status,
                                    @QueryParam("page") Integer page,
                                    @QueryParam("pageSize") Integer pageSize) throws Exception {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        requireSuperAdmin(headers, null);
        if (status == null || status.isBlank()) {
            where.append(" and status = 1");
        } else if (!"all".equalsIgnoreCase(status)) {
            require("0".equals(status) || "1".equals(status), "用户状态筛选不正确");
            where.append(" and status = ?");
            params.add(Integer.parseInt(status));
        }

        String rowsSql = """
                select id, username, nickname, phone, address, tags, 'user' role, status, 'user' account_type
                from `user`
                """ + where + " order by id desc";
        if (page != null || pageSize != null) {
            return ok(pagedQuery("select count(*) total from `user`" + where, rowsSql, page, pageSize, params.toArray()));
        }
        return ok(query(rowsSql, params.toArray()));
    }

    @GET
    @Path("/{id}")
    public Map<String, Object> detail(@Context HttpHeaders headers,
                                      @PathParam("id") int id) throws Exception {
        Map<String, Object> operator = requireUser(headers, null);
        if (!"admin".equals(operator.get("role")) && !"super_admin".equals(operator.get("role"))) {
            requireSameUser(operator, id);
        }
        return ok(userView(id));
    }

    @POST
    @Path("/register")
    public Map<String, Object> register(UserRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.username != null && !request.username.isBlank(), "请填写用户名");
        require(request.password != null && !request.password.isBlank(), "请填写密码");
        String username = request.username.trim();
        require(queryOne("select id from `user` where username = ?", username) == null, "用户名已存在");
        require(queryOne("select id from `admin` where username = ?", username) == null, "用户名已存在");
        int id = insert("""
                        insert into `user`(username, password, nickname, phone, address, tags, status)
                        values(?, ?, ?, ?, ?, ?, 1)
                        """,
                username, SecurityUtil.hashPassword(request.password),
                textOrDefault(request.nickname, username),
                text(request.phone), text(request.address), normalizeTags(request.tags));
        return ok(withToken(userView(id), createAuthToken(id)));
    }

    @POST
    @Path("/login")
    public Map<String, Object> login(LoginRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.username != null && !request.username.isBlank(), "请填写用户名");
        require(request.password != null && !request.password.isBlank(), "请填写密码");
        String username = request.username.trim();
        ensureLoginAllowed(username);
        Map<String, Object> row = queryOne("""
                select id, username, password, nickname, phone, address, tags, 'user' role, status
                from `user`
                where username = ? and status = 1
                """, username);
        if (row != null && SecurityUtil.verifyPassword(request.password, String.valueOf(row.get("password")))) {
            int id = ((Number) row.get("id")).intValue();
            if (SecurityUtil.needsPasswordUpgrade(String.valueOf(row.get("password")))) {
                update("update `user` set password = ? where id = ?", SecurityUtil.hashPassword(request.password), id);
            }
            recordLoginSuccess(username);
            return ok(withToken(userView(id), createAuthToken("user", id)));
        }

        Map<String, Object> admin = queryOne("""
                select id, username, password, nickname, phone, '' address, '' tags, admin_level role, status
                from `admin`
                where username = ? and status = 1
                """, username);
        if (admin == null || !SecurityUtil.verifyPassword(request.password, String.valueOf(admin.get("password")))) {
            recordLoginFailure(username);
            throw new ApiException(401, "用户名或密码错误");
        }
        int id = ((Number) admin.get("id")).intValue();
        if (SecurityUtil.needsPasswordUpgrade(String.valueOf(admin.get("password")))) {
            update("update `admin` set password = ? where id = ?", SecurityUtil.hashPassword(request.password), id);
        }
        recordLoginSuccess(username);
        admin.remove("password");
        admin.put("account_type", "admin");
        admin.put("token", createAuthToken("admin", id));
        return ok(admin);
    }

    @POST
    @Path("/logout")
    public Map<String, Object> logout(@Context HttpHeaders headers) throws Exception {
        revokeAuthToken(headers);
        return okMessage("已退出登录");
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateUser(@Context HttpHeaders headers,
                                          @PathParam("id") int id,
                                          UserRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        Map<String, Object> operator = requireUser(headers, null);
        requireSameUser(operator, id);
        Map<String, Object> current = queryOne("""
                select id, username, nickname, phone, address, tags
                from `user`
                where id = ? and status = 1
                """, id);
        if (current == null) {
            throw new ApiException(404, "用户不存在");
        }
        String nickname = request.nickname == null
                ? text(current.get("nickname"))
                : textOrDefault(request.nickname, String.valueOf(current.get("username")));
        String phone = request.phone == null ? text(current.get("phone")) : request.phone.trim();
        String address = request.address == null ? text(current.get("address")) : request.address.trim();
        String tags = request.tags == null ? text(current.get("tags")) : normalizeTags(request.tags);
        String password = blankToNull(request.password);
        requireRows(update("""
                        update `user`
                        set nickname = ?,
                            phone = ?,
                            address = ?,
                            tags = ?,
                            password = coalesce(?, password)
                        where id = ? and status = 1
                        """,
                nickname, phone, address, tags,
                password == null ? null : SecurityUtil.hashPassword(password), id), "用户不存在");
        return ok(userView(id));
    }

    @PUT
    @Path("/{id}/role")
    public Map<String, Object> updateRole(@Context HttpHeaders headers,
                                          @PathParam("id") int id,
                                          RoleRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.role != null, "请选择用户身份");
        require("admin".equals(request.role), "当前操作只支持把普通用户设为管理员");
        requireSuperAdmin(headers, null);

        Map<String, Object> target = queryOne("""
                select id, username, password, nickname, phone
                from `user`
                where id = ? and status = 1
                """, id);
        if (target == null) {
            throw new ApiException(404, "用户不存在");
        }
        require(queryOne("select id from `admin` where username = ?", target.get("username")) == null,
                "该账号已经是管理员");
        insert("""
                insert into `admin`(username, password, nickname, phone, admin_level, status, department, remark)
                values(?, ?, ?, ?, 'admin', 1, '运营中心', '由超级管理员授权')
                """, target.get("username"), target.get("password"), target.get("nickname"), target.get("phone"));
        revokeAllAuthTokens("user", id);
        update("update `user` set status = 0 where id = ?", id);
        return okMessage("用户已设为管理员");
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteUser(@Context HttpHeaders headers,
                                          @PathParam("id") int id,
                                          @QueryParam("actorId") Integer actorId) throws Exception {
        requireSuperAdmin(headers, null);
        Map<String, Object> target = queryOne("select id from `user` where id = ? and status = 1", id);
        if (target == null) {
            throw new ApiException(404, "用户不存在");
        }
        revokeAllAuthTokens("user", id);
        requireRows(update("update `user` set status = 0 where id = ? and status = 1", id), "用户不存在");
        return okMessage("账号已禁用，历史订单已保留");
    }

    @PUT
    @Path("/{id}/restore")
    public Map<String, Object> restoreUser(@Context HttpHeaders headers,
                                           @PathParam("id") int id) throws Exception {
        requireSuperAdmin(headers, null);
        Map<String, Object> target = queryOne("select id, status from `user` where id = ?", id);
        if (target == null) {
            throw new ApiException(404, "用户不存在");
        }
        requireRows(update("update `user` set status = 1 where id = ?", id), "用户不存在");
        return ok(userView(id));
    }

    private Map<String, Object> userView(int id) throws Exception {
        Map<String, Object> user = queryOne("""
                select id, username, nickname, phone, address, tags, 'user' role, status, 'user' account_type
                from `user`
                where id = ? and status = 1
                """, id);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        return user;
    }

    private Map<String, Object> withToken(Map<String, Object> user, String token) {
        user.put("token", token);
        return user;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeTags(String value) {
        return value == null ? "" : value.trim().replace('，', ',');
    }

    public static class UserRequest {
        public String username;
        public String password;
        public String nickname;
        public String phone;
        public String address;
        public String tags;
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }

    public static class RoleRequest {
        public Integer actorId;
        public String role;
    }
}
