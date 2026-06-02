# 柚子妹妹网购

基于 Java Web、Jersey、MySQL 的综合网购平台项目，包含商品浏览、搜索、购物车、收藏、订单、评价、物流、管理员商品维护和超级管理员用户授权。

## 运行步骤

1. 确认本机 MySQL 已启动。
2. 复制 `src/main/resources/db.local.properties.example` 为 `src/main/resources/db.local.properties`。
3. 在 `db.local.properties` 中填写自己本机的 MySQL 用户名和密码。
4. 默认数据库名为 `YZMM_Mall`，项目启动时会自动创建数据库和表。
5. 也可以手动执行 `src/main/resources/sql/schema.sql` 初始化数据。
6. 在 IDEA 中重新构建并用 Tomcat 部署 `demo:war exploded`。
7. 访问 `http://localhost:8080/demo/`。

## 数据库配置说明

项目会按下面的优先级读取数据库配置：

```text
环境变量 / JVM 参数 > db.local.properties > db.properties
```

`db.local.properties` 已加入 `.gitignore`，适合每个人保存自己的本地数据库密码，不建议把个人 MySQL 密码提交到 GitHub。

示例：

```properties
db.url=jdbc:mysql://localhost:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
db.name=YZMM_Mall
db.username=root
db.password=你的MySQL密码
```

如果修改数据库配置后仍然打不开，先停止 Tomcat，再重新 Build Artifact 或执行 Maven 打包，避免 Tomcat 继续使用旧的 `target/classes/db.properties`。

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
