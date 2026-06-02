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
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;

import java.math.BigDecimal;
import java.util.Map;

@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductResource extends BaseResource {
    private static final String PRODUCT_WITH_STATS = """
            select p.*,
                   coalesce(s.actual_sales, 0) + p.sale_count sales_count,
                   coalesce(cm.comment_count, 0) comment_count,
                   coalesce(round(cm.avg_score, 1), 5.0) avg_score,
                   case
                       when coalesce(cm.comment_count, 0) = 0 then 100
                       else round(cm.positive_count * 100 / cm.comment_count)
                   end positive_rate
            from product p
            left join (
                select product_id, sum(count) actual_sales
                from order_item
                group by product_id
            ) s on s.product_id = p.id
            left join (
                select product_id,
                       count(*) comment_count,
                       avg(score) avg_score,
                       sum(case when score >= 4 then 1 else 0 end) positive_count
                from `comment`
                group by product_id
            ) cm on cm.product_id = p.id
            """;

    @GET
    public Map<String, Object> list(@QueryParam("keyword") String keyword,
                                    @QueryParam("category") String category) throws Exception {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasCategory = category != null && !category.isBlank() && !"全部".equals(category);
        if (!hasKeyword && !hasCategory) {
            return ok(query(PRODUCT_WITH_STATS + " order by p.id desc"));
        }
        if (hasKeyword && hasCategory) {
            String like = "%" + keyword.trim() + "%";
            return ok(query(PRODUCT_WITH_STATS + """
                    where (p.name like ? or p.subtitle like ? or p.category like ?)
                      and p.category = ?
                    order by p.id desc
                    """, like, like, like, category.trim()));
        }
        if (hasKeyword) {
            String like = "%" + keyword.trim() + "%";
            return ok(query(PRODUCT_WITH_STATS + """
                    where p.name like ? or p.subtitle like ? or p.category like ?
                    order by p.id desc
                    """, like, like, like));
        }
        return ok(query(PRODUCT_WITH_STATS + " where p.category = ? order by p.id desc", category.trim()));
    }

    @GET
    @Path("/{id}")
    public Map<String, Object> detail(@PathParam("id") int id) throws Exception {
        Map<String, Object> product = queryOne(PRODUCT_WITH_STATS + " where p.id = ?", id);
        if (product == null) {
            throw new ApiException(404, "商品不存在");
        }
        return ok(product);
    }

    @POST
    public Map<String, Object> create(ProductRequest request) throws Exception {
        requireAdmin(request.actorId);
        validate(request);
        int id = insert("""
                        insert into product(name, category, subtitle, description, price, stock, sale_count, image)
                        values(?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                request.name, category(request), request.subtitle, request.description,
                request.price, request.stock, saleCount(request), request.image);
        return detail(id);
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateProduct(@PathParam("id") int id, ProductRequest request) throws Exception {
        requireAdmin(request.actorId);
        validate(request);
        requireRows(update("""
                        update product
                        set name = ?, category = ?, subtitle = ?, description = ?,
                            price = ?, stock = ?, sale_count = ?, image = ?
                        where id = ?
                        """,
                request.name, category(request), request.subtitle, request.description,
                request.price, request.stock, saleCount(request), request.image, id), "商品不存在");
        return detail(id);
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteProduct(@PathParam("id") int id, @QueryParam("actorId") Integer actorId) throws Exception {
        requireAdmin(actorId);
        requireRows(update("delete from product where id = ?", id), "商品不存在");
        return okMessage("商品已删除");
    }

    private void requireAdmin(Integer actorId) throws Exception {
        require(actorId != null, "缺少操作账号");
        Map<String, Object> actor = queryOne("select role from `user` where id = ?", actorId);
        if (actor == null || (!"admin".equals(actor.get("role")) && !"super_admin".equals(actor.get("role")))) {
            throw new ApiException(403, "只有管理员可以管理商品");
        }
    }

    private void validate(ProductRequest request) {
        require(request.name != null && !request.name.isBlank(), "请填写商品名称");
        require(request.price != null && request.price.compareTo(BigDecimal.ZERO) >= 0, "商品价格不能小于 0");
        require(request.stock != null && request.stock >= 0, "商品库存不能小于 0");
        require(request.saleCount == null || request.saleCount >= 0, "基础销量不能小于 0");
    }

    private String category(ProductRequest request) {
        return request.category == null || request.category.isBlank() ? "综合" : request.category.trim();
    }

    private Integer saleCount(ProductRequest request) {
        return request.saleCount == null ? 0 : request.saleCount;
    }

    public static class ProductRequest {
        public String name;
        public String category;
        public String subtitle;
        public String description;
        public BigDecimal price;
        public Integer stock;
        public Integer saleCount;
        public String image;
        public Integer actorId;
    }
}
