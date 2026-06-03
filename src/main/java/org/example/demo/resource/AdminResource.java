package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;
import org.example.demo.common.SecurityUtil;

import java.util.Map;

@Path("/admins")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers) throws Exception {
        requireManager(headers, null, "只有管理员可以查看管理员列表");
        return ok(query("""
                select id, username, nickname, phone, admin_level role, admin_level,
                       department, remark, status, create_time admin_create_time
                from `admin`
                where status = 1
                order by id desc
                """));
    }

    @POST
    @Path("/login")
    public Map<String, Object> login(LoginRequest request) throws Exception {
        require(request != null && request.username != null && !request.username.isBlank(), "请填写管理员账号");
        require(request.password != null && !request.password.isBlank(), "请填写管理员密码");
        String username = request.username.trim();
        ensureLoginAllowed(username);
        Map<String, Object> admin = queryOne("""
                select id, username, password, nickname, phone,
                       '' address, '' tags, admin_level role, admin_level,
                       department, remark, create_time admin_create_time
                from `admin`
                where username = ? and status = 1
                """, username);
        if (admin == null || !SecurityUtil.verifyPassword(request.password, String.valueOf(admin.get("password")))) {
            recordLoginFailure(username);
            throw new ApiException(401, "管理员账号或密码错误");
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

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteAdmin(@Context HttpHeaders headers,
                                           @PathParam("id") int id) throws Exception {
        Map<String, Object> actor = requireSuperAdmin(headers, null);
        require(((Number) actor.get("id")).intValue() != id, "不能删除当前登录账号");
        Map<String, Object> target = queryOne("select id, admin_level from `admin` where id = ? and status = 1", id);
        if (target == null) {
            throw new ApiException(404, "管理员不存在");
        }
        require(!"super_admin".equals(target.get("admin_level")), "不能删除超级管理员");
        revokeAllAuthTokens("admin", id);
        requireRows(update("update `admin` set status = 0 where id = ? and status = 1", id), "管理员不存在");
        return okMessage("管理员已禁用");
    }

    @PUT
    @Path("/{id}/restore")
    public Map<String, Object> restoreAdmin(@Context HttpHeaders headers,
                                            @PathParam("id") int id) throws Exception {
        requireSuperAdmin(headers, null);
        requireRows(update("update `admin` set status = 1 where id = ?", id), "管理员不存在");
        return ok(queryOne("""
                select id, username, nickname, phone, admin_level role, admin_level,
                       department, remark, status, create_time admin_create_time
                from `admin`
                where id = ?
                """, id));
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
