package org.example.demo.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.example.demo.common.BaseResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommentResource extends BaseResource {
    @GET
    public Map<String, Object> list(@Context HttpHeaders headers,
                                    @QueryParam("productId") Integer productId,
                                    @QueryParam("actorId") Integer actorId,
                                    @QueryParam("page") Integer page,
                                    @QueryParam("pageSize") Integer pageSize) throws Exception {
        List<Object> params = new ArrayList<>();
        String rowsSql;
        String countSql;
        if (productId == null) {
            requireAdmin(headers, actorId);
            rowsSql = """
                    select c.*, u.username, u.nickname, p.name product_name
                    from `comment` c
                    join `user` u on u.id = c.user_id
                    join product p on p.id = c.product_id
                    order by c.id desc
                    """;
            countSql = "select count(*) total from `comment`";
        } else {
            params.add(productId);
            rowsSql = """
                    select c.*, u.username, u.nickname
                    from `comment` c
                    join `user` u on u.id = c.user_id
                    where c.product_id = ?
                    order by c.id desc
                    """;
            countSql = "select count(*) total from `comment` where product_id = ?";
        }
        if (page != null || pageSize != null) {
            return ok(pagedQuery(countSql, rowsSql, page, pageSize, params.toArray()));
        }
        return ok(query(rowsSql, params.toArray()));
    }

    @POST
    public Map<String, Object> create(@Context HttpHeaders headers, CommentRequest request) throws Exception {
        require(request.userId != null && request.productId != null, "请先登录并选择商品");
        Map<String, Object> user = requireUser(headers, null);
        requireSameUser(user, request.userId);
        require(request.content != null && !request.content.isBlank(), "请填写评价内容");
        require(request.score != null && request.score >= 1 && request.score <= 5, "评分必须是 1-5");
        String images = request.images == null ? "" : request.images.trim();
        require(images.length() <= 3 * 1024 * 1024, "评价图片数据不能超过 3MB");
        int id = insert("""
                insert into `comment`(user_id, product_id, content, score, images, create_time)
                values(?, ?, ?, ?, ?, now())
                """, request.userId, request.productId, request.content.trim(), request.score, images);
        return ok(queryOne("select * from `comment` where id = ?", id));
    }

    private void requireAdmin(HttpHeaders headers, Integer actorId) throws Exception {
        requireManager(headers, null, "只有管理员可以查看全部评价");
    }

    public static class CommentRequest {
        public Integer userId;
        public Integer productId;
        public String content;
        public Integer score;
        public String images;
    }
}
