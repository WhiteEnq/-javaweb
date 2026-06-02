# 柚子妹妹网购

基于 Java Web、Jersey、MySQL 的综合网购平台项目，包含商品浏览、搜索、购物车、收藏、订单、评价、物流、管理员商品维护和超级管理员用户授权。

## 运行步骤

1. 按本地 MySQL 修改 `src/main/resources/db.properties`。
2. 默认数据库名为 `YZMM_Mall`，项目启动时会自动创建数据库和表。
3. 也可以手动执行 `src/main/resources/sql/schema.sql` 初始化数据。
4. 在 IDEA 中用 Tomcat 部署 `demo:war exploded`。
5. 访问 `http://localhost:8080/demo/`。

## 内置账号

```text
普通用户：zhangsan / 123456
管理员：admin / 123456
超级管理员：superadmin / 123456
```

## 环境变量

```text
YZMM_MALL_DB_URL
YZMM_MALL_DB_NAME
YZMM_MALL_DB_USERNAME
YZMM_MALL_DB_PASSWORD
```
