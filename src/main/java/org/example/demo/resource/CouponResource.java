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
import org.example.demo.common.BaseResource;

import java.math.BigDecimal;
import java.util.Map;

@Path("/coupons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CouponResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("userId") Integer userId,
                                    @QueryParam("actorId") Integer actorId) throws Exception {
        if (userId != null) {
            Map<String, Object> user = requireUser(headers, null);
            requireSameUser(user, userId);
            return ok(query("""
                    select uc.id user_coupon_id, uc.status user_status, uc.receive_time, uc.used_time,
                           c.id coupon_id, c.name, c.code, c.discount_amount, c.threshold_amount,
                           c.stock, c.status, c.start_time, c.end_time
                    from user_coupon uc
                    join coupon c on c.id = uc.coupon_id
                    where uc.user_id = ?
                    order by uc.status asc, uc.id desc
                    """, userId));
        }
        if (actorId != null) {
            requireManager(headers, null, "只有管理员可以管理优惠券");
            return ok(query("select * from coupon order by id desc"));
        }
        return ok(query("""
                select *
                from coupon
                where status = 1
                  and stock > 0
                  and (start_time is null or start_time <= now())
                  and (end_time is null or end_time >= now())
                order by threshold_amount asc, discount_amount desc
                """));
    }

    @POST
    @Path("/claim")
    public Map<String, Object> claim(@Context HttpHeaders headers, CouponRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.userId != null && request.couponId != null, "请先登录并选择优惠券");
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, request.userId);
        if (queryOne("select id from user_coupon where user_id = ? and coupon_id = ?",
                request.userId, request.couponId) == null) {
            requireRows(update("""
                    update coupon
                    set stock = stock - 1
                    where id = ?
                      and status = 1
                      and stock > 0
                      and (start_time is null or start_time <= now())
                      and (end_time is null or end_time >= now())
                    """, request.couponId), "优惠券不可领取");
            insert("""
                    insert into user_coupon(user_id, coupon_id, status, receive_time)
                    values(?, ?, 0, now())
                    """, request.userId, request.couponId);
        }
        return list(headers, request.userId, null);
    }

    @POST
    public Map<String, Object> create(@Context HttpHeaders headers, CouponRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        requireManager(headers, request.actorId, "只有管理员可以管理优惠券");
        validate(request);
        int id = insert("""
                insert into coupon(name, code, discount_amount, threshold_amount, stock, status, start_time, end_time)
                values(?, ?, ?, ?, ?, ?, ?, ?)
                """, request.name.trim(), request.code.trim(), request.discountAmount, request.thresholdAmount,
                request.stock, request.status == null ? 1 : request.status, request.startTime, request.endTime);
        return ok(queryOne("select * from coupon where id = ?", id));
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateCoupon(@Context HttpHeaders headers,
                                            @PathParam("id") int id,
                                            CouponRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        requireManager(headers, request.actorId, "只有管理员可以管理优惠券");
        validate(request);
        requireRows(update("""
                update coupon
                set name = ?, code = ?, discount_amount = ?, threshold_amount = ?,
                    stock = ?, status = ?, start_time = ?, end_time = ?
                where id = ?
                """, request.name.trim(), request.code.trim(), request.discountAmount, request.thresholdAmount,
                request.stock, request.status == null ? 1 : request.status, request.startTime, request.endTime, id), "优惠券不存在");
        return ok(queryOne("select * from coupon where id = ?", id));
    }

    private void validate(CouponRequest request) {
        require(request != null, "优惠券信息不能为空");
        require(request.name != null && !request.name.isBlank(), "请填写优惠券名称");
        require(request.code != null && !request.code.isBlank(), "请填写优惠券编码");
        require(request.discountAmount != null && request.discountAmount.compareTo(BigDecimal.ZERO) >= 0, "优惠金额不能小于 0");
        require(request.thresholdAmount != null && request.thresholdAmount.compareTo(BigDecimal.ZERO) >= 0, "使用门槛不能小于 0");
        require(request.stock != null && request.stock >= 0, "库存不能小于 0");
    }

    public static class CouponRequest {
        public Integer actorId;
        public Integer userId;
        public Integer couponId;
        public String name;
        public String code;
        public BigDecimal discountAmount;
        public BigDecimal thresholdAmount;
        public Integer stock;
        public Integer status;
        public String startTime;
        public String endTime;
    }
}
