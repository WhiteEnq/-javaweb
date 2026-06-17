# 柚子妹妹网购

基于 Java Web、Jersey、MySQL、Tomcat 的综合网购平台项目。项目包含商品浏览、分类搜索、SKU 规格、购物车超时提醒、优惠券、收藏、订单、评价图片、物流、售后、个人主页、管理员运营中心和超级管理员用户授权等功能。

本文档用于小组成员拉取项目后的本地配置和运行。请按顺序操作，尤其注意 **Tomcat 必须使用 9.x**。

## 一、环境要求

| 工具 | 推荐版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | `pom.xml` 中编译版本为 17 |
| IntelliJ IDEA | 2023+ | 中文界面也可以，建议使用 Ultimate |
| MySQL | 8.0 | 默认数据库名为 `YZMM_Mall` |
| Tomcat | 9.x | 项目使用 `javax.*`，不要使用 Tomcat 10 |
| Maven | 使用项目自带 Maven Wrapper | Windows 下运行 `mvnw.cmd` |

为什么不能用 Tomcat 10：本项目依赖 `javax.servlet`、`javax.ws.rs` 和 Jersey 2.x，适配 Tomcat 9。Tomcat 10 使用 `jakarta.*`，会导致接口无法正常启动，登录时可能出现“接口返回异常”。

## 二、拉取项目

```powershell
git clone 你的仓库地址
cd 项目目录
```

如果已经拉取过项目，更新代码：

```powershell
git pull
```

用 IDEA 打开项目时，选择包含 `pom.xml` 的项目根目录。

## 三、Maven 依赖加载与打包

### 1. IDEA 中加载 Maven

1. 打开 IDEA。
2. 等待右下角 Maven 自动导入。
3. 如果没有自动导入，打开右侧 `Maven` 面板。
4. 点击刷新按钮，重新加载 `pom.xml`。

### 2. 命令行验证 Maven

在项目根目录运行：

```powershell
.\mvnw.cmd -v
```

打包项目：

```powershell
.\mvnw.cmd clean package -DskipTests
```

打包成功后，会生成：

```text
target/demo-1.0-SNAPSHOT.war
target/demo-1.0-SNAPSHOT/
```

如果 Maven 下载依赖失败，通常是网络或 Maven 镜像问题。可以先在 IDEA 的 Maven 面板点刷新，也可以换网络后重新执行 `.\mvnw.cmd clean package -DskipTests`。

## 四、数据库搭建

### 1. 启动 MySQL

确认本机 MySQL 服务已经启动。

Windows 可以在服务里查看：

```text
Win + R -> services.msc -> 找到 MySQL80 -> 启动
```

也可以在命令行测试：

```powershell
mysql -uroot -p
```

能进入 MySQL 命令行，说明 MySQL 基本可用。

### 2. 创建本地数据库配置

项目不要直接修改 `db.properties` 里的公共配置。每个人应该创建自己的本地配置文件。

复制模板：

```powershell
Copy-Item src\main\resources\db.local.properties.example src\main\resources\db.local.properties
```

然后打开：

```text
src/main/resources/db.local.properties
```

改成自己本机的 MySQL 配置：

```properties
db.url=jdbc:mysql://localhost:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
db.name=YZMM_Mall
db.username=root
db.password=你的MySQL密码
```

`db.local.properties` 已经加入 `.gitignore`，不会被提交到 GitHub，适合保存每个人自己的数据库密码。

项目读取数据库配置的优先级：

```text
环境变量 / JVM 参数 > db.local.properties > db.properties
```

### 3. 初始化完整演示数据库

推荐手动执行项目自带 SQL，这样数据库、外键、演示数据一次到位。

SQL 文件位置：

```text
src/main/resources/sql/schema.sql
```

它会创建并初始化：

```text
数据库：YZMM_Mall
用户、管理员扩展、商品、SKU、优惠券、购物车、收藏、订单、订单明细、评价、物流、售后、登录安全、外键关系
```

如果使用 MySQL 命令行，可以先进入 MySQL：

```powershell
mysql -uroot -p --default-character-set=utf8mb4
```

进入后执行：

```sql
source D:/QiMoJavaWeb/demo/src/main/resources/sql/schema.sql;
```

注意：把路径换成你自己电脑上的项目路径，路径中建议使用 `/`。

如果使用 Navicat、DataGrip 或 IDEA 数据库工具：

1. 连接本机 MySQL。
2. 打开 `src/main/resources/sql/schema.sql`。
3. 选择全部 SQL。
4. 点击运行。
5. 刷新数据库列表，确认出现 `YZMM_Mall`。

### 4. 检查数据库是否成功

执行：

```sql
use YZMM_Mall;
show tables;
```

应该看到这些表：

```text
user
login_attempt
auth_token
admin
product
product_sku
coupon
user_coupon
cart
favorite
order_info
order_item
comment
logistics
after_sale
```

检查数据量：

```sql
select count(*) from product;
select count(*) from user;
select count(*) from order_info;
```

正常情况下大致为：

```text
product: 20
product_sku: 11
coupon: 3
user: 6
order_info: 6
```

## 五、Tomcat 9 配置

### 1. 添加 Tomcat

在 IDEA 中文界面中：

```text
运行 -> 编辑配置 -> 左上角 + -> Tomcat 服务器 -> 本地
```

然后配置：

```text
应用程序服务器：选择 Tomcat 9.x 目录
JRE：选择 JDK 17
```

如果没有 Tomcat 9，需要先下载 Tomcat 9。不要选择 Tomcat 10。

### 2. 添加部署 Artifact

在 Tomcat 配置窗口中：

```text
部署 -> + -> Artifact
```

选择：

```text
demo:war exploded
```

Application context 设置为：

```text
/demo
```

这样启动后的访问路径就是：

```text
http://localhost:8080/demo/
```

### 3. 启动项目

点击 IDEA 右上角绿色运行按钮。

控制台中看到 Tomcat 正常启动后，浏览器访问：

```text
http://localhost:8080/demo/
```

如果页面可以打开，再测试接口：

```text
http://localhost:8080/demo/api/products
```

如果返回 JSON 数据，说明后端接口和数据库连接正常。

## 六、内置账号

```text
普通用户：zhangsan / 123456
普通用户：lisi / 123456
普通用户：wangwu / 123456
普通用户：zhaoliu / 123456
管理员：admin / 123456
超级管理员：superadmin / 123456
```

普通用户账号存放在 `user` 表中；管理员和超级管理员账号存放在 `admin` 表中。前端仍然只有一个登录入口，后端会先匹配普通用户，再匹配管理员，并根据登录来源返回 `role=user/admin/super_admin`。首次登录内置账号时，后端会把旧的 `123456` 明文密码自动升级为 PBKDF2 哈希。

权限说明：

```text
user：普通用户，只能选购商品、领取优惠券、收藏、购物车、下单、评论、申请售后
admin：管理员，账号在 admin 表，可以进入运营中心管理商品、SKU、优惠券、订单、物流、评价、售后，并禁用普通用户
super_admin：超级管理员，账号在 admin 表，可以把普通用户转为管理员，也可以禁用普通用户和管理员
```

页面说明：

```text
1. 登录页不分用户页和管理员页，只有一个统一登录入口。
2. 登录成功后，前端根据账号类型自动切换到用户端或管理端。
3. 超级管理员比普通管理员多一个“用户权限”页面。
4. 用户端使用粉色渐变和动态效果，管理员端保持蓝色简洁风格。
```

安全说明：

```text
1. 登录成功后后端返回 token，前端会自动通过 Authorization: Bearer token 调用需要权限的接口。
2. 管理员接口不再信任前端传入的 actorId，权限以 token 对应账号为准。
3. 删除用户改为禁用账号，user.status = 0 后无法登录，但历史订单会保留。
4. 管理员拖入的商品图片会保存到 Web 应用 uploads 目录，数据库只保存图片路径。
5. 购物车超时不仅前端倒计时提醒，后端查询和下单前也会清理过期项。
6. 商品 SKU、优惠券、订单明细、评价图片和售后工单都有对应表结构与外键约束。
7. user 表只保存消费者账号，admin 表独立保存管理员账号，auth_token 通过 account_type 区分登录身份。
```

## 七、支付配置说明

如果要演示支付宝支付，需要先准备本地支付配置文件。

### 1. 复制本地配置模板

```powershell
Copy-Item src\main\resources\pay.local.properties.example src\main\resources\pay.local.properties
```

### 2. 填写必要配置

打开 `src/main/resources/pay.local.properties`，填写这些内容：

```text
alipay.app-id=你的支付宝沙箱应用ID
alipay.private-key=你的应用私钥
alipay.alipay-public-key=支付宝公钥
alipay.public-base-url=你的公网访问地址/demo
alipay.notify-url=支付异步通知地址
alipay.return-url=支付完成后的跳转地址
```

如果使用 `cloudflared` 或 `trycloudflare`，`alipay.public-base-url` 一般写成：

```text
https://xxxx.trycloudflare.com/demo
```

页面里显示的是“支付”，但课堂演示实际连接的是支付宝沙箱环境。

## 八、常见问题

### 1. 登录提示“接口返回异常”

优先检查：

```text
http://localhost:8080/demo/api/products
```

如果这个地址不是 JSON，而是 404、500 或 HTML 页面，说明后端接口没有正常工作。

常见原因：

```text
1. 使用了 Tomcat 10，而不是 Tomcat 9
2. Application context 不是 /demo
3. 没有部署 demo:war exploded
4. Tomcat 没有重新启动，仍然使用旧配置
5. 数据库连接失败，后端启动时报错
```

### 2. 数据库连接失败

如果控制台出现：

```text
Access denied for user
```

说明 `db.local.properties` 里的用户名或密码错误。

如果出现：

```text
Communications link failure
```

说明 MySQL 没启动，或者端口不是 3306。

如果出现：

```text
Unknown database
```

说明数据库没有创建，先执行 `schema.sql`，或者确认 `db.name=YZMM_Mall`。

### 3. 修改数据库密码后仍然不生效

停止 Tomcat，然后重新构建项目：

```powershell
.\mvnw.cmd clean package -DskipTests
```

再重新部署 `demo:war exploded`。

原因是 Tomcat 可能还在使用旧的 `target/classes/db.properties`。

### 4. Maven 依赖下载失败

先尝试：

```powershell
.\mvnw.cmd clean package -DskipTests
```

如果还是失败：

```text
1. 检查网络
2. 在 IDEA Maven 面板点击刷新
3. 检查 Maven 设置中的本地仓库和镜像源
4. 换网络后重新下载依赖
```

### 5. ER 图没有连线

现在 `schema.sql` 已经包含外键。执行 SQL 后，需要在建模工具里重新：

```text
从数据库导入 / 逆向数据库 / 刷新模型
```

只看旧模型不会自动出现连线。

## 九、项目目录说明

```text
src/main/java/org/example/demo/common
公共工具类、数据库连接、异常处理

src/main/java/org/example/demo/resource
后端 REST 接口，包含用户、商品、购物车、订单、评价等接口

src/main/resources/db.properties
默认数据库配置，不建议每个人直接改

src/main/resources/db.local.properties
本地数据库配置，每个人自己创建，不提交 GitHub

src/main/resources/sql/schema.sql
完整建库建表和演示数据 SQL

src/main/webapp/index.html
前端页面和交互逻辑
```

## 十、快速运行检查清单

```text
JDK 17 已配置
MySQL 8 已启动
db.local.properties 已创建并填写正确密码
schema.sql 已执行
Maven 依赖已加载
Tomcat 使用 9.x
部署项为 demo:war exploded
Application context 为 /demo
能访问 /demo/api/products 并返回 JSON
```
