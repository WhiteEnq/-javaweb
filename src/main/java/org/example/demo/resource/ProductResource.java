package org.example.demo.resource;

import javax.servlet.ServletContext;
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
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductResource extends BaseResource {
    private static final int MAX_INLINE_IMAGE_BYTES = 3 * 1024 * 1024;

    @Context
    private ServletContext servletContext;

    private static final String PRODUCT_WITH_STATS = """
            select p.*,
                   coalesce(sku.sku_count, 0) sku_count,
                   coalesce(sku.sku_stock, p.stock) sku_stock,
                   coalesce(sku.min_sku_price, p.price) min_sku_price,
                   coalesce(s.actual_sales, 0) + p.sale_count sales_count,
                   coalesce(cm.comment_count, 0) comment_count,
                   coalesce(round(cm.avg_score, 1), 5.0) avg_score,
                   case
                       when coalesce(cm.comment_count, 0) = 0 then 100
                       else round(cm.positive_count * 100 / cm.comment_count)
                   end positive_rate
            from product p
            left join (
                select product_id, count(*) sku_count, sum(stock) sku_stock, min(price) min_sku_price
                from product_sku
                group by product_id
            ) sku on sku.product_id = p.id
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
                                    @QueryParam("category") String category,
                                    @QueryParam("minPrice") BigDecimal minPrice,
                                    @QueryParam("maxPrice") BigDecimal maxPrice,
                                    @QueryParam("stockOnly") Boolean stockOnly,
                                    @QueryParam("sort") String sort,
                                    @QueryParam("page") Integer page,
                                    @QueryParam("pageSize") Integer pageSize) throws Exception {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where p.status = 1");
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(" and (p.name like ? or p.subtitle like ? or p.category like ? or p.description like ?)");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (category != null && !category.isBlank() && !"全部".equals(category)) {
            where.append(" and p.category = ?");
            params.add(category.trim());
        }
        if (minPrice != null) {
            where.append(" and p.price >= ?");
            params.add(minPrice);
        }
        if (maxPrice != null) {
            where.append(" and p.price <= ?");
            params.add(maxPrice);
        }
        if (Boolean.TRUE.equals(stockOnly)) {
            where.append(" and coalesce(sku.sku_stock, p.stock) > 0");
        }

        String orderBy = switch (sort == null ? "" : sort) {
            case "sales" -> " order by sales_count desc, p.id desc";
            case "rating" -> " order by positive_rate desc, avg_score desc, p.id desc";
            case "priceAsc" -> " order by p.price asc, p.id desc";
            case "priceDesc" -> " order by p.price desc, p.id desc";
            default -> " order by p.id desc";
        };
        String rowsSql = PRODUCT_WITH_STATS + where + orderBy;
        String countSql = """
                select count(*) total
                from product p
                left join (
                    select product_id, sum(stock) sku_stock
                    from product_sku
                    group by product_id
                ) sku on sku.product_id = p.id
                """ + where;
        if (page != null || pageSize != null) {
            return ok(pagedQuery(countSql, rowsSql, page, pageSize, params.toArray()));
        }
        return ok(query(rowsSql, params.toArray()));
    }

    @GET
    @Path("/{id}")
    public Map<String, Object> detail(@PathParam("id") int id) throws Exception {
        Map<String, Object> product = queryOne(PRODUCT_WITH_STATS + " where p.id = ? and p.status = 1", id);
        if (product == null) {
            throw new ApiException(404, "商品不存在");
        }
        product.put("skus", query("""
                select id, product_id, spec_name, spec_value, price, stock, image, sort_order
                from product_sku
                where product_id = ?
                order by sort_order asc, id asc
                """, id));
        return ok(product);
    }

    @POST
    public Map<String, Object> create(@Context HttpHeaders headers, ProductRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        requireAdmin(headers, request == null ? null : request.actorId);
        validate(request);
        String image = storeImageIfNeeded(request.image);
        int id = insert("""
                        insert into product(name, category, subtitle, description, price, stock, sale_count, status, image)
                        values(?, ?, ?, ?, ?, ?, ?, 1, ?)
                        """,
                request.name.trim(), category(request), text(request.subtitle), text(request.description),
                request.price, request.stock, saleCount(request), image);
        if (request.skus != null) {
            replaceSkus(id, request.skus);
        }
        return detail(id);
    }

    @PUT
    @Path("/{id}")
    public Map<String, Object> updateProduct(@Context HttpHeaders headers,
                                             @PathParam("id") int id,
                                             ProductRequest request) throws Exception {
        require(request != null, "请求内容不能为空");
        requireAdmin(headers, request == null ? null : request.actorId);
        validate(request);
        String image = storeImageIfNeeded(request.image);
        requireRows(update("""
                        update product
                        set name = ?, category = ?, subtitle = ?, description = ?,
                            price = ?, stock = ?, sale_count = ?, image = ?
                        where id = ? and status = 1
                        """,
                request.name.trim(), category(request), text(request.subtitle), text(request.description),
                request.price, request.stock, saleCount(request), image, id), "商品不存在");
        if (request.skus != null) {
            replaceSkus(id, request.skus);
        }
        return detail(id);
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> deleteProduct(@Context HttpHeaders headers,
                                             @PathParam("id") int id,
                                             @QueryParam("actorId") Integer actorId) throws Exception {
        requireAdmin(headers, actorId);
        requireRows(update("update product set status = 0 where id = ? and status = 1", id), "商品不存在");
        return okMessage("商品已下架");
    }

    private void requireAdmin(HttpHeaders headers, Integer actorId) throws Exception {
        requireManager(headers, null, "只有管理员可以管理商品");
    }

    private void validate(ProductRequest request) {
        require(request != null, "商品信息不能为空");
        require(request.name != null && !request.name.isBlank(), "请填写商品名称");
        require(request.price != null && request.price.compareTo(BigDecimal.ZERO) >= 0, "商品价格不能小于 0");
        require(request.stock != null && request.stock >= 0, "商品库存不能小于 0");
        require(request.saleCount == null || request.saleCount >= 0, "基础销量不能小于 0");
        if (request.skus != null) {
            for (SkuRequest sku : request.skus) {
                require(sku.specName != null && !sku.specName.isBlank(), "SKU 规格名不能为空");
                require(sku.specValue != null && !sku.specValue.isBlank(), "SKU 规格值不能为空");
                require(sku.price != null && sku.price.compareTo(BigDecimal.ZERO) >= 0, "SKU 价格不能小于 0");
                require(sku.stock != null && sku.stock >= 0, "SKU 库存不能小于 0");
            }
        }
    }

    private void replaceSkus(int productId, List<SkuRequest> skus) throws Exception {
        update("delete from product_sku where product_id = ?", productId);
        int order = 1;
        for (SkuRequest sku : skus) {
            if (sku == null || sku.specName == null || sku.specName.isBlank() || sku.specValue == null || sku.specValue.isBlank()) {
                continue;
            }
            insert("""
                    insert into product_sku(product_id, spec_name, spec_value, price, stock, image, sort_order)
                    values(?, ?, ?, ?, ?, ?, ?)
                    """,
                    productId, sku.specName.trim(), sku.specValue.trim(), sku.price, sku.stock,
                    storeImageIfNeeded(sku.image), order++);
        }
    }

    private String category(ProductRequest request) {
        return request.category == null || request.category.isBlank() ? "综合" : request.category.trim();
    }

    private Integer saleCount(ProductRequest request) {
        return request.saleCount == null ? 0 : request.saleCount;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String storeImageIfNeeded(String image) throws Exception {
        if (image == null || image.isBlank() || !image.startsWith("data:image/")) {
            return image;
        }
        int comma = image.indexOf(',');
        require(comma > 0, "图片数据格式不正确");
        String meta = image.substring(5, comma).toLowerCase();
        require(meta.contains(";base64"), "图片数据格式不正确");
        String ext = meta.contains("png") ? ".png" : meta.contains("webp") ? ".webp" : ".jpg";
        byte[] bytes = Base64.getDecoder().decode(image.substring(comma + 1));
        require(bytes.length <= MAX_INLINE_IMAGE_BYTES, "图片不能超过 3MB");
        String root = servletContext == null ? null : servletContext.getRealPath("/uploads");
        if (root == null || root.isBlank()) {
            return image;
        }
        Files.createDirectories(java.nio.file.Path.of(root));
        String fileName = "product-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ext;
        Files.write(java.nio.file.Path.of(root, fileName), bytes);
        return "uploads/" + fileName;
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
        public List<SkuRequest> skus;
    }

    public static class SkuRequest {
        public String specName;
        public String specValue;
        public BigDecimal price;
        public Integer stock;
        public String image;
    }
}
