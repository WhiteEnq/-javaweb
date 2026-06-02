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
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/cart")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CartResource extends BaseResource {
    @GET
    public Map<String, Object> list(@QueryParam("userId") int userId) throws Exception {
        return ok(query("""
                select c.id, c.user_id, c.product_id, c.count,
                       p.name product_name, p.price, p.stock, p.image,
                       p.price * c.count subtotal
                from cart c
                join product p on p.id = c.product_id
                where c.user_id = ?
                order by c.id desc
                """, userId));
    }

    @POST
    public Map<String, Object> add(CartRequest request) throws Exception {
        require(request.userId != null && request.productId != null, "请先登录并选择商品");
        int count = request.count == null || request.count <= 0 ? 1 : request.count;
        Map<String, Object> product = queryOne("select stock from product where id = ?", request.productId);
        require(product != null, "商品不存在");
        Map<String, Object> item = queryOne("select id, count from cart where user_id = ? and product_id = ?",
                request.userId, request.productId);
        int currentCount = item == null ? 0 : ((Number) item.get("count")).intValue();
        require(currentCount + count <= ((Number) product.get("stock")).intValue(), "库存不足");
        if (item == null) {
            insert("insert into cart(user_id, product_id, count) values(?, ?, ?)",
                    request.userId, request.productId, count);
        } else {
            update("update cart set count = count + ? where id = ?", count, item.get("id"));
        }
        return list(request.userId);
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateCount(@PathParam("id") int id, CartRequest request) throws Exception {
        require(request.count != null && request.count > 0, "数量必须大于 0");
        Map<String, Object> row = queryOne("""
                select p.stock
                from cart c
                join product p on p.id = c.product_id
                where c.id = ?
                """, id);
        require(row != null, "购物车商品不存在");
        require(request.count <= ((Number) row.get("stock")).intValue(), "库存不足");
        requireRows(update("update cart set count = ? where id = ?", request.count, id), "购物车商品不存在");
        return okMessage("购物车已更新");
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteItem(@PathParam("id") int id) throws Exception {
        requireRows(update("delete from cart where id = ?", id), "购物车商品不存在");
        return okMessage("已移出购物车");
    }

    public static class CartRequest {
        public Integer userId;
        public Integer productId;
        public Integer count;
    }
}
