package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.Map;

@Path("/favorites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FavoriteResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("userId") int userId) throws Exception {
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, userId);
        return ok(query("""
                select f.id, f.user_id, f.product_id, p.name, p.category, p.price, p.stock, p.image
                from favorite f
                join product p on p.id = f.product_id
                where f.user_id = ? and p.status = 1
                order by f.id desc
                """, userId));
    }

    @POST
    public Map<String, Object> add(@Context HttpHeaders headers, FavoriteRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        require(request.userId != null && request.productId != null, "请先登录并选择商品");
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, request.userId);
        require(queryOne("select id from product where id = ? and status = 1", request.productId) != null, "商品不存在或已下架");
        if (queryOne("select id from favorite where user_id = ? and product_id = ?",
                request.userId, request.productId) == null) {
            insert("insert into favorite(user_id, product_id) values(?, ?)", request.userId, request.productId);
        }
        return list(headers, request.userId);
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteFavorite(@Context HttpHeaders headers,
                                              @PathParam("id") int id) throws Exception {
        Map<String, Object> row = queryOne("select user_id from favorite where id = ?", id);
        require(row != null, "收藏不存在");
        int userId = ((Number) row.get("user_id")).intValue();
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, userId);
        requireRows(update("delete from favorite where id = ?", id), "收藏不存在");
        return okMessage("已取消收藏");
    }

    public static class FavoriteRequest {
        public Integer userId;
        public Integer productId;
    }
}
