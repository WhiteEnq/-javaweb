package org.example.demo.resource;

import javax.ws.rs.Consumes;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/after-sales")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AfterSaleResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("userId") Integer userId,
                                    @QueryParam("actorId") Integer actorId,
                                    @QueryParam("page") Integer page,
                                    @QueryParam("pageSize") Integer pageSize) throws Exception {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (userId == null) {
            requireManager(headers, actorId, "只有管理员可以查看售后工单");
        } else {
            Map<String, Object> user = requireUser(headers, null);
            if (!isManager(user)) {
                requireSameUser(user, userId);
            }
            where.append(" and a.user_id = ?");
            params.add(userId);
        }
        String rowsSql = """
                select a.*, o.order_no, o.total_price, u.username, u.nickname
                from after_sale a
                join order_info o on o.id = a.order_id
                join `user` u on u.id = a.user_id
                """ + where + " order by a.id desc";
        String countSql = "select count(*) total from after_sale a" + where;
        if (page != null || pageSize != null) {
            return ok(pagedQuery(countSql, rowsSql, page, pageSize, params.toArray()));
        }
        return ok(query(rowsSql, params.toArray()));
    }

    @POST
    public Map<String, Object> create(@Context HttpHeaders headers, AfterSaleRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.userId != null && request.orderId != null, "请先登录并选择订单");
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, request.userId);
        require(request.type != null && !request.type.isBlank(), "请选择售后类型");
        require(request.reason != null && !request.reason.isBlank(), "请填写售后原因");
        Map<String, Object> order = queryOne("select id, user_id, status from order_info where id = ?", request.orderId);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        require(((Number) order.get("user_id")).intValue() == request.userId, "只能申请自己的订单售后");
        require(((Number) order.get("status")).intValue() >= 1, "待支付订单不能申请售后");
        int id = insert("""
                insert into after_sale(order_id, user_id, type, reason, status, create_time, update_time)
                values(?, ?, ?, ?, 'pending', now(), now())
                """, request.orderId, request.userId, request.type.trim(), request.reason.trim());
        return ok(queryOne("select * from after_sale where id = ?", id));
    }

    @PUT
    @Path("/{id}/status")
    public Map<String, Object> updateStatus(@Context HttpHeaders headers,
                                            @PathParam("id") int id,
                                            AfterSaleRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        requireManager(headers, request.actorId, "只有管理员可以处理售后工单");
        require(request.status != null && !request.status.isBlank(), "请选择售后状态");
        requireRows(update("""
                update after_sale
                set status = ?, reply = coalesce(?, reply), update_time = now()
                where id = ?
                """, request.status.trim(), blankToNull(request.reply), id), "售后工单不存在");
        return ok(queryOne("select * from after_sale where id = ?", id));
    }

    private boolean isManager(Map<String, Object> user) {
        return "admin".equals(user.get("role")) || "super_admin".equals(user.get("role"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static class AfterSaleRequest {
        public Integer actorId;
        public Integer userId;
        public Integer orderId;
        public String type;
        public String reason;
        public String status;
        public String reply;
    }
}
