package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource extends BaseResource {
    @GET
    public Map<String, Object> list(@QueryParam("actorId") Integer actorId) throws Exception {
        requireSuperAdmin(actorId);
        return ok(query("select id, username, nickname, phone, address, tags, role from `user` order by id desc"));
    }

    @GET
    @Path("/{id}")
    public Map<String, Object> detail(@PathParam("id") int id) throws Exception {
        Map<String, Object> user = queryOne("select id, username, nickname, phone, address, tags, role from `user` where id = ?", id);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        return ok(user);
    }

    @POST
    @Path("/register")
    public Map<String, Object> register(UserRequest request) throws Exception {
        require(request.username != null && !request.username.isBlank(), "请填写用户名");
        require(request.password != null && !request.password.isBlank(), "请填写密码");
        require(queryOne("select id from `user` where username = ?", request.username) == null, "用户名已存在");
        int id = insert("insert into `user`(username, password, nickname, phone, address, tags, role) values(?, ?, ?, ?, ?, ?, 'user')",
                request.username, request.password, textOrDefault(request.nickname, request.username),
                request.phone, request.address, request.tags);
        return ok(queryOne("select id, username, nickname, phone, address, tags, role from `user` where id = ?", id));
    }

    @POST
    @Path("/login")
    public Map<String, Object> login(LoginRequest request) throws Exception {
        require(request.username != null && !request.username.isBlank(), "请填写用户名");
        require(request.password != null && !request.password.isBlank(), "请填写密码");
        Map<String, Object> user = queryOne("""
                select id, username, nickname, phone, address, tags, role
                from `user`
                where username = ? and password = ?
                """, request.username, request.password);
        if (user == null) {
            throw new ApiException(401, "用户名或密码错误");
        }
        return ok(user);
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateUser(@PathParam("id") int id, UserRequest request) throws Exception {
        Map<String, Object> current = queryOne("select id, username, nickname, phone, address, tags from `user` where id = ?", id);
        if (current == null) {
            throw new ApiException(404, "用户不存在");
        }
        String nickname = request.nickname == null
                ? text(current.get("nickname"))
                : textOrDefault(request.nickname, String.valueOf(current.get("username")));
        String phone = request.phone == null ? text(current.get("phone")) : request.phone.trim();
        String address = request.address == null ? text(current.get("address")) : request.address.trim();
        String tags = request.tags == null ? text(current.get("tags")) : normalizeTags(request.tags);
        requireRows(update("""
                        update `user`
                        set nickname = ?,
                            phone = ?,
                            address = ?,
                            tags = ?,
                            password = coalesce(?, password)
                        where id = ?
                        """,
                nickname, phone, address, tags, blankToNull(request.password), id), "用户不存在");
        return detail(id);
    }

    @PUT
    @Path("/{id}/role")
    public Map<String, Object> updateRole(@PathParam("id") int id, RoleRequest request) throws Exception {
        require(request.role != null, "请选择用户身份");
        require("user".equals(request.role) || "admin".equals(request.role), "身份只能是普通用户或管理员");
        requireSuperAdmin(request.actorId);

        Map<String, Object> target = queryOne("select id, role from `user` where id = ?", id);
        if (target == null) {
            throw new ApiException(404, "用户不存在");
        }
        if ("super_admin".equals(target.get("role"))) {
            throw new ApiException(400, "超级管理员不能被修改");
        }

        update("update `user` set role = ? where id = ?", request.role, id);
        return detail(id);
    }

    private void requireSuperAdmin(Integer actorId) throws Exception {
        require(actorId != null, "缺少超级管理员账号");
        Map<String, Object> actor = queryOne("select id, role from `user` where id = ?", actorId);
        if (actor == null || !"super_admin".equals(actor.get("role"))) {
            throw new ApiException(403, "只有超级管理员可以管理用户权限");
        }
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
