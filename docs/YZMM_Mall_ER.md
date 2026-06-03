# YZMM_Mall ER 图

数据来源：`src/main/resources/sql/schema.sql` 与后端自动初始化逻辑。

当前数据库已经设置显式外键约束。用 Navicat、DataGrip、MySQL Workbench 等工具反向工程时，订单、商品、用户、购物车、优惠券、售后等关系会自动连线。

纯 Mermaid 文件见：`docs/YZMM_Mall_ER.mmd`。

```mermaid
erDiagram
    USER {
        int id PK
        varchar username UK
        varchar password
        varchar nickname
        varchar phone
        varchar address
        varchar tags
        tinyint status
    }

    LOGIN_ATTEMPT {
        varchar username PK
        int failed_count
        timestamp locked_until
    }

    AUTH_TOKEN {
        int id PK
        varchar account_type
        int user_id FK
        int admin_id FK
        char token_hash UK
        timestamp expire_time
    }

    ADMIN {
        int id PK
        varchar username UK
        varchar password
        varchar nickname
        varchar phone
        varchar admin_level
        tinyint status
        varchar department
    }

    PRODUCT {
        int id PK
        varchar name
        varchar category
        decimal price
        int stock
        int sale_count
        tinyint status
    }

    PRODUCT_SKU {
        int id PK
        int product_id FK
        varchar spec_name
        varchar spec_value
        decimal price
        int stock
    }

    COUPON {
        int id PK
        varchar code UK
        decimal discount_amount
        decimal threshold_amount
        int stock
        tinyint status
    }

    USER_COUPON {
        int id PK
        int user_id FK
        int coupon_id FK
        int used_order_id FK
        tinyint status
    }

    CART {
        int id PK
        int user_id FK
        int product_id FK
        int sku_id FK
        int count
    }

    FAVORITE {
        int id PK
        int user_id FK
        int product_id FK
    }

    ORDER_INFO {
        int id PK
        varchar order_no UK
        int user_id FK
        int coupon_id FK
        decimal total_price
        decimal discount_amount
        tinyint status
    }

    ORDER_ITEM {
        int id PK
        int order_id FK
        int product_id FK
        int sku_id FK
        varchar sku_text
        int count
        decimal price
    }

    COMMENT {
        int id PK
        int user_id FK
        int product_id FK
        varchar content
        tinyint score
        mediumtext images
    }

    LOGISTICS {
        int id PK
        int order_id FK
        varchar logistics_no
        varchar courier_company
        varchar delivery_method
        varchar status
    }

    AFTER_SALE {
        int id PK
        int order_id FK
        int user_id FK
        varchar type
        varchar status
    }

    USER ||--o{ AUTH_TOKEN : user_id
    ADMIN ||--o{ AUTH_TOKEN : admin_id
    USER ||--o{ CART : user_id
    USER ||--o{ FAVORITE : user_id
    USER ||--o{ ORDER_INFO : user_id
    USER ||--o{ COMMENT : user_id
    USER ||--o{ USER_COUPON : user_id
    USER ||--o{ AFTER_SALE : user_id

    PRODUCT ||--o{ PRODUCT_SKU : product_id
    PRODUCT ||--o{ CART : product_id
    PRODUCT_SKU ||--o{ CART : sku_id
    PRODUCT ||--o{ FAVORITE : product_id
    PRODUCT ||--o{ ORDER_ITEM : product_id
    PRODUCT_SKU ||--o{ ORDER_ITEM : sku_id
    PRODUCT ||--o{ COMMENT : product_id

    COUPON ||--o{ USER_COUPON : coupon_id
    COUPON ||--o{ ORDER_INFO : coupon_id
    ORDER_INFO ||--o{ USER_COUPON : used_order_id
    ORDER_INFO ||--o{ ORDER_ITEM : order_id
    ORDER_INFO ||--o| LOGISTICS : order_id
    ORDER_INFO ||--o{ AFTER_SALE : order_id
```

## 核心关系

| 关系 | 说明 |
| --- | --- |
| `user` | 只保存普通消费者账号和个人资料 |
| `admin` | 独立保存管理员和超级管理员账号 |
| `user` / `admin` → `auth_token` | 登录后生成 token，接口通过 `Authorization: Bearer token` 鉴权 |
| `product` → `product_sku` | 一个商品可拥有多个规格，每个规格独立价格和库存 |
| `user`、`product`、`product_sku` → `cart` | 购物车保留用户选择的商品和规格，支持超时提醒 |
| `coupon` → `user_coupon` → `order_info` | 用户领取优惠券，下单时记录使用的优惠券和优惠金额 |
| `order_info` → `order_item` | 一个订单包含多个商品明细，明细记录购买时的 SKU 文本 |
| `order_info` → `logistics` | 订单发货后生成物流记录，包含快递公司、配送方式、状态 |
| `order_info` → `after_sale` | 已支付订单可以申请退款、退货退款或维修售后 |
| `product` → `comment` | 商品评价支持评分、文字和图片数据 |

## 当前示例数据

| 表名 | 行数 |
| --- | ---: |
| user | 4 |
| admin | 2 |
| product | 20 |
| product_sku | 11 |
| coupon | 3 |
| user_coupon | 6 |
| cart | 7 |
| favorite | 8 |
| order_info | 6 |
| order_item | 10 |
| comment | 18 |
| logistics | 4 |
| after_sale | 2 |

## 设计备注

- `user` 和 `admin` 分表保存：普通用户不会再混在管理员表里，管理员也不会再作为普通用户存储。
- 旧库中残留的 `user.role` 管理员账号和 `admin.user_id` 关联字段会在启动初始化时迁移清理。
- `admin.admin_level` 包含 `admin`、`super_admin`，超级管理员可授权普通用户成为管理员。
- `user.status = 0` 表示禁用普通用户，历史订单保留，禁用账号不能登录。
- `product.status = 0` 表示商品下架，历史订单明细仍保留。
- `user.password` 支持 PBKDF2 哈希；旧明文种子密码会在首次登录成功后自动升级。
- `login_attempt` 用于记录失败次数和锁定时间，减少暴力尝试。
- `cart.update_time` 用于购物车保留倒计时，后端会清理超时项，前端会提前提醒。
- `order_info.total_price` 保存优惠后的应付金额，`discount_amount` 保存优惠金额。
- `schema.sql` 可直接执行，其他成员拉取项目后运行即可得到完整本地数据库。
