package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/logistics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LogisticsResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("orderId") Integer orderId) throws Exception {
        Map<String, Object> user = requireUser(headers, null);
        if (orderId == null) {
            requireManager(headers, null, "只有管理员可以查看全部物流");
            return ok(query("select * from logistics order by id desc"));
        }
        if (!isManager(user)) {
            Map<String, Object> order = queryOne("select user_id from order_info where id = ?", orderId);
            require(order != null, "订单不存在");
            requireSameUser(user, ((Number) order.get("user_id")).intValue());
        }
        return ok(query("select * from logistics where order_id = ?", orderId));
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateLogistics(@Context HttpHeaders headers,
                                               @PathParam("id") int id,
                                               LogisticsRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        requireManager(headers, null, "只有管理员可以更新物流");
        require(request.status != null && !request.status.isBlank(), "物流状态不能为空");
        requireRows(update("""
                        update logistics
                        set status = ?,
                            courier_company = coalesce(?, courier_company),
                            delivery_method = coalesce(?, delivery_method),
                            update_time = now()
                        where id = ?
                        """,
                request.status, blankToNull(request.courierCompany), blankToNull(request.deliveryMethod), id),
                "物流不存在");
        return ok(queryOne("select * from logistics where id = ?", id));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isManager(Map<String, Object> user) {
        return "admin".equals(user.get("role")) || "super_admin".equals(user.get("role"));
    }

    public static class LogisticsRequest {
        public String status;
        public String courierCompany;
        public String deliveryMethod;
    }
}
