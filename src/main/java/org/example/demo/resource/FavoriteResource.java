package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/favorites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FavoriteResource extends BaseResource {
    @GET
    public Map<String, Object> list(@QueryParam("userId") int userId) throws Exception {
        return ok(query("""
                select f.id, f.user_id, f.product_id, p.name, p.category, p.price, p.stock, p.image
                from favorite f
                join product p on p.id = f.product_id
                where f.user_id = ?
                order by f.id desc
                """, userId));
    }

    @POST
    public Map<String, Object> add(FavoriteRequest request) throws Exception {
        require(request.userId != null && request.productId != null, "请先登录并选择商品");
        if (queryOne("select id from favorite where user_id = ? and product_id = ?",
                request.userId, request.productId) == null) {
            insert("insert into favorite(user_id, product_id) values(?, ?)", request.userId, request.productId);
        }
        return list(request.userId);
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteFavorite(@PathParam("id") int id) throws Exception {
        requireRows(update("delete from favorite where id = ?", id), "收藏不存在");
        return okMessage("已取消收藏");
    }

    public static class FavoriteRequest {
        public Integer userId;
        public Integer productId;
    }
}
