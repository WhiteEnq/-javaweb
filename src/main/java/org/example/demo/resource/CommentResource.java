package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;
import org.example.demo.common.ApiException;

import java.util.Map;

@Path("/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommentResource extends BaseResource {
    @GET
    public Map<String, Object> list(@QueryParam("productId") Integer productId,
                                    @QueryParam("actorId") Integer actorId) throws Exception {
        if (productId == null) {
            requireAdmin(actorId);
            return ok(query("""
                    select c.*, u.username, p.name product_name
                    from `comment` c
                    join `user` u on u.id = c.user_id
                    join product p on p.id = c.product_id
                    order by c.id desc
                    """));
        }
        return ok(query("""
                select c.*, u.username
                from `comment` c
                join `user` u on u.id = c.user_id
                where c.product_id = ?
                order by c.id desc
                """, productId));
    }

    @POST
    public Map<String, Object> create(CommentRequest request) throws Exception {
        require(request.userId != null && request.productId != null, "请先登录并选择商品");
        require(request.content != null && !request.content.isBlank(), "请填写评价内容");
        require(request.score != null && request.score >= 1 && request.score <= 5, "评分必须是 1-5");
        int id = insert("""
                insert into `comment`(user_id, product_id, content, score, create_time)
                values(?, ?, ?, ?, now())
                """, request.userId, request.productId, request.content, request.score);
        return ok(queryOne("select * from `comment` where id = ?", id));
    }

    private void requireAdmin(Integer actorId) throws Exception {
        require(actorId != null, "缺少管理员账号");
        Map<String, Object> actor = queryOne("select role from `user` where id = ?", actorId);
        if (actor == null || (!"admin".equals(actor.get("role")) && !"super_admin".equals(actor.get("role")))) {
            throw new ApiException(403, "只有管理员可以查看全部评价");
        }
    }

    public static class CommentRequest {
        public Integer userId;
        public Integer productId;
        public String content;
        public Integer score;
    }
}
