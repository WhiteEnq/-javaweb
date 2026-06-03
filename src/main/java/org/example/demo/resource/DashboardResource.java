package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DashboardResource extends BaseResource {
    @GET
    public Map<String, Object> overview(@Context HttpHeaders headers) throws Exception {
        requireManager(headers, null, "只有管理员可以查看运营看板");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metrics", queryOne("""
                select
                    (select count(*) from product where status = 1) products,
                    (select count(*) from order_info) orders,
                    (select coalesce(sum(total_price), 0) from order_info where status in (1, 2, 3)) revenue,
                    (select count(*) from `comment`) comments,
                    (select count(*) from `user` where status = 1) active_users,
                    (select count(*) from `user` where status = 0) disabled_users,
                    (select count(*) from after_sale where status in ('pending', 'processing')) pending_after_sales,
                    (select count(*) from coupon where status = 1) active_coupons
                """));
        data.put("orderStatus", query("""
                select status, count(*) count, coalesce(sum(total_price), 0) amount
                from order_info
                group by status
                order by status
                """));
        data.put("categorySales", query("""
                select p.category, coalesce(sum(oi.count), 0) sales, coalesce(sum(oi.count * oi.price), 0) amount
                from product p
                left join order_item oi on oi.product_id = p.id
                where p.status = 1
                group by p.category
                order by sales desc, amount desc
                """));
        data.put("hotProducts", query("""
                select p.id, p.name, p.category, p.stock,
                       coalesce(sum(oi.count), 0) + p.sale_count sales
                from product p
                left join order_item oi on oi.product_id = p.id
                where p.status = 1
                group by p.id, p.name, p.category, p.stock, p.sale_count
                order by sales desc
                limit 8
                """));
        data.put("lowStock", query("""
                select id, name, category, stock
                from product
                where status = 1 and stock <= 20
                order by stock asc, id desc
                limit 8
                """));
        data.put("afterSaleStatus", query("""
                select status, count(*) count
                from after_sale
                group by status
                order by count desc
                """));
        data.put("recentOrders", query("""
                select id, order_no, user_id, total_price, discount_amount, status, create_time
                from order_info
                order by id desc
                limit 8
                """));
        return ok(data);
    }
}
