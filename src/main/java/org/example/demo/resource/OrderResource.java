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
import org.example.demo.common.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource extends BaseResource {
    @GET
    public Map<String, Object> list(@QueryParam("userId") Integer userId,
                                    @QueryParam("actorId") Integer actorId) throws Exception {
        if (userId == null) {
            requireAdmin(actorId);
            return ok(query("select * from order_info order by id desc"));
        }
        return ok(query("select * from order_info where user_id = ? order by id desc", userId));
    }

    @GET
    @Path("/{id}")
    public Map<String, Object> detail(@PathParam("id") int id) throws Exception {
        Map<String, Object> order = queryOne("select * from order_info where id = ?", id);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        order.put("items", query("""
                select oi.*, p.name product_name, p.image
                from order_item oi
                join product p on p.id = oi.product_id
                where oi.order_id = ?
                """, id));
        order.put("logistics", queryOne("select * from logistics where order_id = ?", id));
        return ok(order);
    }

    @POST
    public Map<String, Object> create(OrderRequest request) throws Exception {
        require(request.userId != null, "请先登录");
        require(request.receiverName != null && !request.receiverName.isBlank(), "请填写收货人");
        require(request.receiverPhone != null && !request.receiverPhone.isBlank(), "请填写联系电话");
        require(request.shippingAddress != null && !request.shippingAddress.isBlank(), "请填写收货地址");
        try (Connection connection = Db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<CartLine> lines = loadCart(connection, request.userId);
                if (lines.isEmpty()) {
                    throw new ApiException(400, "购物车为空");
                }
                BigDecimal total = BigDecimal.ZERO;
                for (CartLine line : lines) {
                    if (line.stock < line.count) {
                        throw new ApiException(400, line.name + " 库存不足");
                    }
                    total = total.add(line.price.multiply(BigDecimal.valueOf(line.count)));
                }

                int orderId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into order_info(order_no, user_id, total_price, status,
                                               receiver_name, receiver_phone, shipping_address, create_time)
                        values(?, ?, ?, 0, ?, ?, ?, now())
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, "YZMM" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
                    statement.setInt(2, request.userId);
                    statement.setBigDecimal(3, total);
                    statement.setString(4, request.receiverName.trim());
                    statement.setString(5, request.receiverPhone.trim());
                    statement.setString(6, request.shippingAddress.trim());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getInt(1);
                    }
                }

                for (CartLine line : lines) {
                    try (PreparedStatement item = connection.prepareStatement("""
                            insert into order_item(order_id, product_id, count, price) values(?, ?, ?, ?)
                            """)) {
                        item.setInt(1, orderId);
                        item.setInt(2, line.productId);
                        item.setInt(3, line.count);
                        item.setBigDecimal(4, line.price);
                        item.executeUpdate();
                    }
                    try (PreparedStatement stock = connection.prepareStatement("update product set stock = stock - ? where id = ?")) {
                        stock.setInt(1, line.count);
                        stock.setInt(2, line.productId);
                        stock.executeUpdate();
                    }
                }
                try (PreparedStatement clear = connection.prepareStatement("delete from cart where user_id = ?")) {
                    clear.setInt(1, request.userId);
                    clear.executeUpdate();
                }
                connection.commit();
                return detail(orderId);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    @PUT
    @Path("/{id}/status")
    public Map<String, Object> updateStatus(@PathParam("id") int id, StatusRequest request) throws Exception {
        require(request.status != null && request.status >= 0 && request.status <= 3, "订单状态不正确");
        Map<String, Object> order = queryOne("select id, user_id, status from order_info where id = ?", id);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        if (request.actorId != null) {
            requireAdmin(request.actorId);
        } else {
            require(request.userId != null, "请先登录");
            require(((Number) order.get("user_id")).intValue() == request.userId, "只能操作自己的订单");
            int currentStatus = ((Number) order.get("status")).intValue();
            boolean canPay = currentStatus == 0 && request.status == 1;
            boolean canConfirm = currentStatus == 2 && request.status == 3;
            require(canPay || canConfirm, "当前订单状态不能这样操作");
        }
        requireRows(update("update order_info set status = ? where id = ?", request.status, id), "订单不存在");
        if (request.status == 2 && queryOne("select id from logistics where order_id = ?", id) == null) {
            insert("insert into logistics(order_id, logistics_no, status, update_time) values(?, ?, 'shipping', now())",
                    id, "WL" + System.currentTimeMillis());
        }
        if (request.status == 3) {
            update("update logistics set status = 'signed', update_time = now() where order_id = ?", id);
        }
        return detail(id);
    }

    private void requireAdmin(Integer actorId) throws Exception {
        require(actorId != null, "缺少管理员账号");
        Map<String, Object> actor = queryOne("select role from `user` where id = ?", actorId);
        if (actor == null || (!"admin".equals(actor.get("role")) && !"super_admin".equals(actor.get("role")))) {
            throw new ApiException(403, "只有管理员可以管理订单");
        }
    }

    private List<CartLine> loadCart(Connection connection, int userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select c.product_id, c.count, p.name, p.price, p.stock
                from cart c
                join product p on p.id = c.product_id
                where c.user_id = ?
                """)) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<CartLine> rows = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new CartLine(
                            resultSet.getInt("product_id"),
                            resultSet.getString("name"),
                            resultSet.getBigDecimal("price"),
                            resultSet.getInt("stock"),
                            resultSet.getInt("count")
                    ));
                }
                return rows;
            }
        }
    }

    private record CartLine(int productId, String name, BigDecimal price, int stock, int count) {
    }

    public static class OrderRequest {
        public Integer userId;
        public String receiverName;
        public String receiverPhone;
        public String shippingAddress;
    }

    public static class StatusRequest {
        public Integer status;
        public Integer userId;
        public Integer actorId;
    }
}
