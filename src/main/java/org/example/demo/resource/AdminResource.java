package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/admins")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource extends BaseResource {
    @GET
    public Map<String, Object> list() throws Exception {
        return ok(query("select id, username from `admin` order by id desc"));
    }

    @POST
    @Path("/login")
    public Map<String, Object> login(LoginRequest request) throws Exception {
        Map<String, Object> admin = queryOne("""
                select id, username
                from `admin`
                where username = ? and password = ?
                """, request.username, request.password);
        if (admin == null) {
            throw new ApiException(401, "Invalid admin username or password");
        }
        return ok(admin);
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
