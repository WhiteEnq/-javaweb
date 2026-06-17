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
import org.example.demo.common.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource extends BaseResource {
    private static final String[] COURIER_COMPANIES = {"顺丰速运", "京东快递", "中通快递", "圆通速递", "韵达快递", "申通快递"};
    private static final String[] DELIVERY_METHODS = {"标准配送", "次日达", "普通配送", "同城配送", "预约配送"};
    private static final String ORDER_EXPIRE_SECONDS_SQL =
            "greatest(0, timestampdiff(second, now(), date_add(order_info.create_time, interval 30 minute))) expires_in_seconds";

    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("userId") Integer userId,
                                    @QueryParam("actorId") Integer actorId,
                                    @QueryParam("status") Integer status,
                                    @QueryParam("page") Integer page,
                                    @QueryParam("pageSize") Integer pageSize) throws Exception {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (userId == null) {
            requireAdmin(headers, actorId);
        } else {
            Map<String, Object> user = requireUser(headers, null);
            if (!isManager(user)) {
                requireSameUser(user, userId);
            }
            where.append(" and user_id = ?");
            params.add(userId);
        }
        if (status != null) {
            where.append(" and status = ?");
            params.add(status);
        }
        String rowsSql = "select order_info.*, " + ORDER_EXPIRE_SECONDS_SQL + " from order_info" + where + " order by id desc";
        if (page != null || pageSize != null) {
            return ok(pagedQuery("select count(*) total from order_info" + where, rowsSql, page, pageSize, params.toArray()));
        }
        return ok(query(rowsSql, params.toArray()));
    }

    @GET
    @Path("/{id}")
    public Map<String, Object> detail(@Context HttpHeaders headers,
                                      @PathParam("id") int id) throws Exception {
        Map<String, Object> order = queryOne("select order_info.*, " + ORDER_EXPIRE_SECONDS_SQL + " from order_info where id = ?", id);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        requireOrderAccess(headers, order);
        order.put("items", query("""
                select oi.*, p.name product_name, p.image product_image,
                       coalesce(ps.image, p.image) image,
                       ps.spec_name, ps.spec_value
                from order_item oi
                join product p on p.id = oi.product_id
                left join product_sku ps on ps.id = oi.sku_id
                where oi.order_id = ?
                """, id));
        order.put("logistics", queryOne("select * from logistics where order_id = ?", id));
        order.put("afterSales", query("select * from after_sale where order_id = ? order by id desc", id));
        return ok(order);
    }

    @POST
    public Map<String, Object> create(@Context HttpHeaders headers, OrderRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.userId != null, "请先登录");
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, request.userId);
        require(request.receiverName != null && !request.receiverName.isBlank(), "请填写收货人");
        require(request.receiverPhone != null && !request.receiverPhone.isBlank(), "请填写联系电话");
        require(request.shippingAddress != null && !request.shippingAddress.isBlank(), "请填写收货地址");
        try (Connection connection = Db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                clearExpiredCart(connection, request.userId);
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

                CouponUse couponUse = loadCoupon(connection, request.userId, request.userCouponId, request.couponId, total);
                BigDecimal discount = couponUse == null ? BigDecimal.ZERO : couponUse.discountAmount.min(total);
                BigDecimal payable = total.subtract(discount);

                int orderId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into order_info(order_no, user_id, total_price, discount_amount, coupon_id, status,
                                               receiver_name, receiver_phone, shipping_address, create_time)
                        values(?, ?, ?, ?, ?, 0, ?, ?, ?, now())
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, "YZMM" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
                    statement.setInt(2, request.userId);
                    statement.setBigDecimal(3, payable);
                    statement.setBigDecimal(4, discount);
                    if (couponUse == null) {
                        statement.setObject(5, null);
                    } else {
                        statement.setInt(5, couponUse.couponId);
                    }
                    statement.setString(6, request.receiverName.trim());
                    statement.setString(7, request.receiverPhone.trim());
                    statement.setString(8, request.shippingAddress.trim());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getInt(1);
                    }
                }

                for (CartLine line : lines) {
                    try (PreparedStatement item = connection.prepareStatement("""
                            insert into order_item(order_id, product_id, sku_id, sku_text, count, price)
                            values(?, ?, ?, ?, ?, ?)
                            """)) {
                        item.setInt(1, orderId);
                        item.setInt(2, line.productId);
                        item.setObject(3, line.skuId);
                        item.setString(4, line.skuText);
                        item.setInt(5, line.count);
                        item.setBigDecimal(6, line.price);
                        item.executeUpdate();
                    }
                    int affected = deductStock(connection, line);
                    if (affected == 0) {
                        throw new ApiException(400, line.name + " 库存不足");
                    }
                }
                if (couponUse != null) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            update user_coupon
                            set status = 1, used_order_id = ?, used_time = now()
                            where id = ? and status = 0
                            """)) {
                        statement.setInt(1, orderId);
                        statement.setInt(2, couponUse.userCouponId);
                        if (statement.executeUpdate() == 0) {
                            throw new ApiException(400, "优惠券已被使用");
                        }
                    }
                }
                try (PreparedStatement clear = connection.prepareStatement("delete from cart where user_id = ?")) {
                    clear.setInt(1, request.userId);
                    clear.executeUpdate();
                }
                connection.commit();
                return detail(headers, orderId);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    @PUT
    @Path("/{id}/status")
    public Map<String, Object> updateStatus(@Context HttpHeaders headers,
                                            @PathParam("id") int id,
                                            StatusRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.status != null && request.status >= 0 && request.status <= 3, "订单状态不正确");
        Map<String, Object> order = queryOne("select id, user_id, status from order_info where id = ?", id);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        if (request.actorId != null) {
            requireAdmin(headers, request.actorId);
        } else {
            require(request.userId != null, "请先登录");
            Map<String, Object> user = requireUser(headers, null);
            requireSameUser(user, request.userId);
            require(((Number) order.get("user_id")).intValue() == request.userId, "只能操作自己的订单");
            int currentStatus = ((Number) order.get("status")).intValue();
            boolean canPay = currentStatus == 0 && request.status == 1;
            boolean canConfirm = currentStatus == 2 && request.status == 3;
            require(canPay || canConfirm, "当前订单状态不能这样操作");
        }
        requireRows(update("update order_info set status = ? where id = ?", request.status, id), "订单不存在");
        if (request.status == 2 && queryOne("select id from logistics where order_id = ?", id) == null) {
            insert("""
                            insert into logistics(order_id, logistics_no, courier_company, delivery_method, status, update_time)
                            values(?, ?, ?, ?, 'shipping', now())
                            """,
                    id, "WL" + System.currentTimeMillis(), randomOne(COURIER_COMPANIES), randomOne(DELIVERY_METHODS));
        } else if (request.status == 2) {
            update("""
                            update logistics
                            set status = 'shipping',
                                courier_company = coalesce(courier_company, ?),
                                delivery_method = coalesce(delivery_method, ?),
                                update_time = now()
                            where order_id = ?
                            """,
                    randomOne(COURIER_COMPANIES), randomOne(DELIVERY_METHODS), id);
        }
        if (request.status == 3) {
            update("update logistics set status = 'signed', update_time = now() where order_id = ?", id);
        }
        return detail(headers, id);
    }

    private void requireAdmin(HttpHeaders headers, Integer actorId) throws Exception {
        requireManager(headers, null, "只有管理员可以管理订单");
    }

    private void requireOrderAccess(HttpHeaders headers, Map<String, Object> order) throws Exception {
        Map<String, Object> user = requireUser(headers, null);
        if (isManager(user)) {
            return;
        }
        requireSameUser(user, ((Number) order.get("user_id")).intValue());
    }

    private boolean isManager(Map<String, Object> user) {
        return "admin".equals(user.get("role")) || "super_admin".equals(user.get("role"));
    }

    private String randomOne(String[] values) {
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    private int deductStock(Connection connection, CartLine line) throws Exception {
        if (line.skuId == null) {
            try (PreparedStatement stock = connection.prepareStatement("""
                    update product
                    set stock = stock - ?
                    where id = ? and stock >= ?
                    """)) {
                stock.setInt(1, line.count);
                stock.setInt(2, line.productId);
                stock.setInt(3, line.count);
                return stock.executeUpdate();
            }
        }
        try (PreparedStatement stock = connection.prepareStatement("""
                update product_sku
                set stock = stock - ?
                where id = ? and stock >= ?
                """)) {
            stock.setInt(1, line.count);
            stock.setInt(2, line.skuId);
            stock.setInt(3, line.count);
            return stock.executeUpdate();
        }
    }

    private List<CartLine> loadCart(Connection connection, int userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select c.product_id, c.sku_id, c.count, p.name,
                       coalesce(ps.price, p.price) price,
                       coalesce(ps.stock, p.stock) stock,
                       case when ps.id is null then null else concat(ps.spec_name, ': ', ps.spec_value) end sku_text
                from cart c
                join product p on p.id = c.product_id
                left join product_sku ps on ps.id = c.sku_id
                where c.user_id = ? and p.status = 1
                """)) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<CartLine> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Object skuId = resultSet.getObject("sku_id");
                    rows.add(new CartLine(
                            resultSet.getInt("product_id"),
                            skuId == null ? null : ((Number) skuId).intValue(),
                            resultSet.getString("name"),
                            resultSet.getString("sku_text"),
                            resultSet.getBigDecimal("price"),
                            resultSet.getInt("stock"),
                            resultSet.getInt("count")
                    ));
                }
                return rows;
            }
        }
    }

    private CouponUse loadCoupon(Connection connection, int userId, Integer userCouponId, Integer couponId,
                                 BigDecimal total) throws Exception {
        if ((userCouponId == null || userCouponId <= 0) && (couponId == null || couponId <= 0)) {
            return null;
        }
        boolean byUserCouponId = userCouponId != null && userCouponId > 0;
        String couponCondition = byUserCouponId ? "uc.id = ?" : "c.id = ?";
        String sql = """
                select uc.id user_coupon_id, c.id coupon_id, c.discount_amount, c.threshold_amount
                from user_coupon uc
                join coupon c on c.id = uc.coupon_id
                where %s
                  and uc.user_id = ?
                  and uc.status = 0
                  and c.status = 1
                  and (c.start_time is null or c.start_time <= now())
                  and (c.end_time is null or c.end_time >= now())
                """.formatted(couponCondition);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, byUserCouponId ? userCouponId : couponId);
            statement.setInt(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new ApiException(400, "优惠券不可用");
                }
                BigDecimal threshold = resultSet.getBigDecimal("threshold_amount");
                if (threshold != null && total.compareTo(threshold) < 0) {
                    throw new ApiException(400, "订单金额未达到优惠券使用门槛");
                }
                return new CouponUse(
                        resultSet.getInt("user_coupon_id"),
                        resultSet.getInt("coupon_id"),
                        resultSet.getBigDecimal("discount_amount")
                );
            }
        }
    }

    private void clearExpiredCart(Connection connection, int userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                delete from cart
                where user_id = ?
                  and update_time < date_sub(now(), interval 30 minute)
                """)) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    private record CartLine(int productId, Integer skuId, String name, String skuText,
                            BigDecimal price, int stock, int count) {
    }

    private record CouponUse(int userCouponId, int couponId, BigDecimal discountAmount) {
    }

    public static class OrderRequest {
        public Integer userId;
        public Integer userCouponId;
        public Integer couponId;
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
