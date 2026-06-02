package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/logistics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LogisticsResource extends BaseResource {
    @GET
    public Map<String, Object> list(@QueryParam("orderId") Integer orderId) throws Exception {
        if (orderId == null) {
            return ok(query("select * from logistics order by id desc"));
        }
        return ok(query("select * from logistics where order_id = ?", orderId));
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateLogistics(@PathParam("id") int id, LogisticsRequest request) throws Exception {
        require(request.status != null && !request.status.isBlank(), "Logistics status is required");
        requireRows(update("update logistics set status = ?, update_time = now() where id = ?", request.status, id),
                "Logistics not found");
        return ok(queryOne("select * from logistics where id = ?", id));
    }

    public static class LogisticsRequest {
        public String status;
    }
}
