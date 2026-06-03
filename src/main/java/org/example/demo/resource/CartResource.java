package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
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

import java.util.Map;

@Path("/cart")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CartResource extends BaseResource {
    private static final int CART_EXPIRE_MINUTES = 30;

    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("userId") int userId) throws Exception {
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, userId);
        clearExpired(userId);
        String sql = """
                select c.id, c.user_id, c.product_id, c.sku_id, c.count,
                       p.name product_name, p.category, p.image product_image,
                       coalesce(ps.image, p.image) image,
                       coalesce(ps.price, p.price) price,
                       coalesce(ps.stock, p.stock) stock,
                       case when ps.id is null then null else concat(ps.spec_name, ': ', ps.spec_value) end sku_text,
                       coalesce(ps.price, p.price) * c.count subtotal,
                       c.update_time,
                       greatest(0, timestampdiff(second, now(), date_add(c.update_time, interval %d minute))) expires_in_seconds
                from cart c
                join product p on p.id = c.product_id
                left join product_sku ps on ps.id = c.sku_id
                where c.user_id = ? and p.status = 1
                order by c.id desc
                """.formatted(CART_EXPIRE_MINUTES);
        return ok(query(sql, userId));
    }

    @POST
    public Map<String, Object> add(@Context HttpHeaders headers, CartRequest request) throws Exception {
        require(request.userId != null && request.productId != null, "请先登录并选择商品");
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, request.userId);
        clearExpired(request.userId);
        int count = request.count == null || request.count <= 0 ? 1 : request.count;
        Integer skuId = request.skuId == null || request.skuId <= 0 ? null : request.skuId;
        Map<String, Object> stockRow = skuId == null
                ? queryOne("select stock from product where id = ? and status = 1", request.productId)
                : queryOne("""
                        select ps.stock
                        from product_sku ps
                        join product p on p.id = ps.product_id
                        where ps.id = ? and ps.product_id = ? and p.status = 1
                        """, skuId, request.productId);
        require(stockRow != null, "商品规格不存在");
        Map<String, Object> item = queryOne("""
                select id, count
                from cart
                where user_id = ? and product_id = ? and sku_id <=> ?
                """, request.userId, request.productId, skuId);
        int currentCount = item == null ? 0 : ((Number) item.get("count")).intValue();
        require(currentCount + count <= ((Number) stockRow.get("stock")).intValue(), "库存不足");
        if (item == null) {
            insert("insert into cart(user_id, product_id, sku_id, count) values(?, ?, ?, ?)",
                    request.userId, request.productId, skuId, count);
        } else {
            update("update cart set count = count + ?, update_time = now() where id = ?", count, item.get("id"));
        }
        return list(headers, request.userId);
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateCount(@Context HttpHeaders headers,
                                           @PathParam("id") int id,
                                           CartRequest request) throws Exception {
        require(request.count != null && request.count > 0, "数量必须大于 0");
        Map<String, Object> row = queryOne("""
                select c.user_id, coalesce(ps.stock, p.stock) stock
                from cart c
                join product p on p.id = c.product_id
                left join product_sku ps on ps.id = c.sku_id
                where c.id = ? and p.status = 1
                """, id);
        require(row != null, "购物车商品不存在");
        int userId = ((Number) row.get("user_id")).intValue();
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, userId);
        require(request.count <= ((Number) row.get("stock")).intValue(), "库存不足");
        requireRows(update("update cart set count = ?, update_time = now() where id = ?", request.count, id), "购物车商品不存在");
        return okMessage("购物车已更新");
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteItem(@Context HttpHeaders headers,
                                          @PathParam("id") int id) throws Exception {
        Map<String, Object> row = queryOne("select user_id from cart where id = ?", id);
        require(row != null, "购物车商品不存在");
        int userId = ((Number) row.get("user_id")).intValue();
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, userId);
        requireRows(update("delete from cart where id = ?", id), "购物车商品不存在");
        return okMessage("已移出购物车");
    }

    private void clearExpired(int userId) throws Exception {
        update("delete from cart where user_id = ? and update_time < date_sub(now(), interval "
                + CART_EXPIRE_MINUTES + " minute)", userId);
    }

    public static class CartRequest {
        public Integer userId;
        public Integer productId;
        public Integer skuId;
        public Integer count;
    }
}
