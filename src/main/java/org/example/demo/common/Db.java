package org.example.demo.common;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public final class Db {
    private static final Properties PROPERTIES = new Properties();
    private static volatile boolean initialized;

    static {
        try {
            loadProperties("db.properties");
            loadProperties("db.local.properties");
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Db() {
    }

    private static void loadProperties(String resourceName) throws Exception {
        try (InputStream input = Db.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input != null) {
                PROPERTIES.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
    }

    public static Connection getConnection() throws SQLException {
        String url = config("YZMM_MALL_DB_URL", "db.url");
        String dbName = config("YZMM_MALL_DB_NAME", "db.name");
        String username = config("YZMM_MALL_DB_USERNAME", "db.username");
        String password = config("YZMM_MALL_DB_PASSWORD", "db.password");
        Connection connection = DriverManager.getConnection(url, username, password);
        useDatabase(connection, dbName);
        initializeIfNeeded(connection);
        return connection;
    }

    private static String config(String envName, String propertyName) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            value = System.getProperty(propertyName);
        }
        if (value == null || value.isBlank()) {
            value = PROPERTIES.getProperty(propertyName);
        }
        return value;
    }

    private static void useDatabase(Connection connection, String dbName) throws SQLException {
        if (dbName == null || dbName.isBlank()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create database if not exists " + identifier(dbName)
                    + " default character set utf8mb4 collate utf8mb4_unicode_ci");
            statement.executeUpdate("use " + identifier(dbName));
        }
    }

    private static String identifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static void initializeIfNeeded(Connection connection) throws SQLException {
        if (initialized) {
            return;
        }
        synchronized (Db.class) {
            if (initialized) {
                return;
            }
            try {
                SchemaInitializer.initialize(connection);
                initialized = true;
            } catch (SQLException e) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Keep the original initialization error.
                }
                throw e;
            }
        }
    }

    private static final class SchemaInitializer {
        private SchemaInitializer() {
        }

        private static void initialize(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        create table if not exists `admin` (
                            id int primary key auto_increment,
                            username varchar(50) not null unique,
                            password varchar(50) not null
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists `user` (
                            id int primary key auto_increment,
                            username varchar(50) not null unique,
                            password varchar(50) not null,
                            nickname varchar(50),
                            phone varchar(11),
                            address varchar(200),
                            tags varchar(200),
                            role varchar(20) not null default 'user'
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table `user` add column role varchar(20) not null default 'user'");
                addColumnIfMissing(statement, "alter table `user` add column nickname varchar(50)");
                addColumnIfMissing(statement, "alter table `user` add column address varchar(200)");
                addColumnIfMissing(statement, "alter table `user` add column tags varchar(200)");
                statement.executeUpdate("""
                        create table if not exists product (
                            id int primary key auto_increment,
                            name varchar(100) not null,
                            category varchar(50) not null default '综合',
                            subtitle varchar(200),
                            description text,
                            price decimal(10, 2) not null default 0.00,
                            stock int not null default 0,
                            sale_count int not null default 0,
                            image mediumtext
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table product add column category varchar(50) not null default '综合'");
                addColumnIfMissing(statement, "alter table product add column subtitle varchar(200)");
                addColumnIfMissing(statement, "alter table product add column description text");
                addColumnIfMissing(statement, "alter table product add column sale_count int not null default 0");
                statement.executeUpdate("alter table product modify column image mediumtext");
                statement.executeUpdate("""
                        create table if not exists cart (
                            id int primary key auto_increment,
                            user_id int not null,
                            product_id int not null,
                            count int not null default 1,
                            unique key uk_cart_user_product (user_id, product_id),
                            constraint fk_cart_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade,
                            constraint fk_cart_product foreign key (product_id) references product(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists favorite (
                            id int primary key auto_increment,
                            user_id int not null,
                            product_id int not null,
                            unique key uk_favorite_user_product (user_id, product_id),
                            constraint fk_favorite_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade,
                            constraint fk_favorite_product foreign key (product_id) references product(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists order_info (
                            id int primary key auto_increment,
                            order_no varchar(50) not null unique,
                            user_id int not null,
                            total_price decimal(10, 2) not null,
                            status tinyint not null default 0,
                            receiver_name varchar(50),
                            receiver_phone varchar(20),
                            shipping_address varchar(200),
                            create_time timestamp not null default current_timestamp,
                            key idx_order_info_user (user_id),
                            constraint fk_order_info_user foreign key (user_id) references `user`(id)
                                on delete restrict on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table order_info add column receiver_name varchar(50)");
                addColumnIfMissing(statement, "alter table order_info add column receiver_phone varchar(20)");
                addColumnIfMissing(statement, "alter table order_info add column shipping_address varchar(200)");
                statement.executeUpdate("""
                        create table if not exists order_item (
                            id int primary key auto_increment,
                            order_id int not null,
                            product_id int not null,
                            count int not null,
                            price decimal(10, 2) not null,
                            key idx_order_item_order (order_id),
                            key idx_order_item_product (product_id),
                            constraint fk_order_item_order foreign key (order_id) references order_info(id)
                                on delete cascade on update cascade,
                            constraint fk_order_item_product foreign key (product_id) references product(id)
                                on delete restrict on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists `comment` (
                            id int primary key auto_increment,
                            user_id int not null,
                            product_id int not null,
                            content varchar(500) not null,
                            score tinyint not null,
                            create_time timestamp not null default current_timestamp,
                            key idx_comment_user (user_id),
                            key idx_comment_product (product_id),
                            constraint fk_comment_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade,
                            constraint fk_comment_product foreign key (product_id) references product(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists logistics (
                            id int primary key auto_increment,
                            order_id int not null unique,
                            logistics_no varchar(50) not null,
                            status varchar(20) not null default 'pending',
                            update_time timestamp not null default current_timestamp on update current_timestamp,
                            constraint fk_logistics_order foreign key (order_id) references order_info(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                ensureForeignKeys(statement);
                seed(statement);
            }
        }

        private static void ensureForeignKeys(Statement statement) throws SQLException {
            addIndexIfMissing(statement, "alter table order_info add index idx_order_info_user (user_id)");
            addIndexIfMissing(statement, "alter table order_item add index idx_order_item_order (order_id)");
            addIndexIfMissing(statement, "alter table order_item add index idx_order_item_product (product_id)");
            addIndexIfMissing(statement, "alter table `comment` add index idx_comment_user (user_id)");
            addIndexIfMissing(statement, "alter table `comment` add index idx_comment_product (product_id)");

            addForeignKeyIfMissing(statement, """
                    alter table cart add constraint fk_cart_user
                    foreign key (user_id) references `user`(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table cart add constraint fk_cart_product
                    foreign key (product_id) references product(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table favorite add constraint fk_favorite_user
                    foreign key (user_id) references `user`(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table favorite add constraint fk_favorite_product
                    foreign key (product_id) references product(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table order_info add constraint fk_order_info_user
                    foreign key (user_id) references `user`(id)
                    on delete restrict on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table order_item add constraint fk_order_item_order
                    foreign key (order_id) references order_info(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table order_item add constraint fk_order_item_product
                    foreign key (product_id) references product(id)
                    on delete restrict on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table `comment` add constraint fk_comment_user
                    foreign key (user_id) references `user`(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table `comment` add constraint fk_comment_product
                    foreign key (product_id) references product(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table logistics add constraint fk_logistics_order
                    foreign key (order_id) references order_info(id)
                    on delete cascade on update cascade
                    """);
        }

        private static void seed(Statement statement) throws SQLException {
            statement.executeUpdate("insert ignore into `admin`(id, username, password) values (1, 'admin', '123456')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags, role) values (1, 'zhangsan', '123456', '张三', '13800000001', '北京市朝阳区示例路 18 号', '数码,家居', 'user')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags, role) values (2, 'lisi', '123456', '李四', '13800000002', '上海市浦东新区示例大道 66 号', '食品,运动', 'user')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags, role) values (3, 'admin', '123456', '运营管理员', '13800000003', '广东省深圳市南山区示例中心', '运营,商品', 'admin')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags, role) values (4, 'superadmin', '123456', '超级管理员', '13800000000', '浙江省杭州市西湖区示例园区', '管理,数据', 'super_admin')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags, role) values (5, 'wangwu', '123456', '王五', '13800000005', '江苏省南京市鼓楼区示例街 28 号', '办公,美妆,礼品', 'user')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags, role) values (6, 'zhaoliu', '123456', '赵六', '13800000006', '四川省成都市高新区示例路 88 号', '运动,数码,食品', 'user')");
            statement.executeUpdate("update `user` set nickname = coalesce(nickname, username)");
            statement.executeUpdate("update `user` set role = 'admin' where username = 'admin'");
            statement.executeUpdate("update `user` set role = 'super_admin' where username = 'superadmin'");
            statement.executeUpdate("""
                    insert into product(id, name, category, subtitle, description, price, stock, sale_count, image) values
                    (1, '无线蓝牙耳机', '手机数码', '低延迟蓝牙连接，通勤运动都能稳稳佩戴', '高清通话、长续航充电仓、轻量化入耳设计。适合通勤、运动、视频会议等日常场景。', 139.00, 120, 2380, 'https://images.unsplash.com/photo-1606220588913-b3aacb4d2f46?auto=format&fit=crop&w=800&q=80'),
                    (2, '机械键盘', '电脑办公', '全键热插拔轴体，办公和游戏都顺手', '紧凑配列、双色键帽、稳定大键调校，支持多设备桌面使用。', 219.00, 80, 1689, 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=800&q=80'),
                    (3, '通勤双肩包', '服饰箱包', '大容量分区收纳，电脑文件都能装', '防泼水面料、独立电脑仓、舒适肩带，适合上班、短途出行和校园使用。', 129.00, 100, 956, 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=800&q=80'),
                    (4, '保湿面膜套装', '美妆个护', '密集补水，换季护理常备', '温和亲肤膜布，适合日常补水和妆前护理。建议根据肤质进行试用。', 99.00, 110, 1842, 'https://images.unsplash.com/photo-1556228578-8c89e6adf883?auto=format&fit=crop&w=800&q=80'),
                    (5, '多功能空气炸锅', '家用电器', '少油烹饪，一台搞定多种家常菜', '可视化烹饪窗口，多档温控，适合薯条、鸡翅、烤蔬菜等家庭菜品。', 299.00, 180, 764, 'https://images.unsplash.com/photo-1626201850124-70977949c655?auto=format&fit=crop&w=800&q=80'),
                    (6, '不锈钢保温杯', '家居日用', '长效保温保冷，随身携带不漏水', '食品接触级不锈钢内胆，杯盖密封设计，适合办公室、通勤和户外。', 69.90, 200, 3120, 'https://images.unsplash.com/photo-1602143407151-7111542de6e8?auto=format&fit=crop&w=800&q=80'),
                    (7, '轻薄笔记本电脑', '电脑办公', '轻薄机身，高效处理学习办公任务', '高清屏幕、长续航电池、丰富接口，满足文档、课程、会议和轻度创作。', 4999.00, 35, 421, 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=800&q=80'),
                    (8, '每日坚果礼盒', '食品饮料', '多种坚果科学配比，办公室常备零食', '独立小袋包装，方便携带。含多种坚果与果干，适合早餐和下午茶。', 79.90, 150, 2156, 'https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&w=800&q=80'),
                    (9, '家用智能投影仪', '家用电器', '卧室客厅都能用，大屏观影更沉浸', '自动对焦、梯形校正、内置音响，适合追剧、游戏和家庭观影。', 1599.00, 42, 538, 'https://images.unsplash.com/photo-1626379953822-baec19c3accd?auto=format&fit=crop&w=800&q=80'),
                    (10, '缓震跑步鞋', '运动户外', '轻量缓震，日常慢跑和通勤都舒适', '透气鞋面、耐磨外底、稳定支撑，适合日常跑步、健身和步行。', 269.00, 94, 1286, 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=800&q=80'),
                    (11, '智能运动手环', '手机数码', '心率监测、运动记录、消息提醒一屏掌握', '支持多运动模式、睡眠监测和长续航，适合日常健身和通勤佩戴。', 199.00, 160, 931, 'https://images.unsplash.com/photo-1557438159-51eec7a6c9e8?auto=format&fit=crop&w=800&q=80'),
                    (12, '护眼学习台灯', '家居日用', '多档亮度调节，学习办公都舒适', '柔和光源、触控调光、可调节灯臂，适合书桌、床头和办公桌使用。', 159.00, 130, 702, 'https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=800&q=80'),
                    (13, '智能扫地机器人', '家用电器', '自动清扫规划，日常家务更省心', '支持路线规划、自动回充和多档吸力，适合客厅、卧室和办公室清洁。', 1299.00, 58, 368, 'https://images.unsplash.com/photo-1593508512255-86ab42a8e620?auto=format&fit=crop&w=800&q=80'),
                    (14, '夏季防晒外套', '服饰箱包', '轻薄透气，通勤户外都能穿', '防晒面料、宽松版型、可收纳帽檐，适合骑行、旅游和日常出行。', 169.00, 140, 1196, 'https://images.unsplash.com/photo-1542060748-10c28b62716f?auto=format&fit=crop&w=800&q=80'),
                    (15, '氨基酸洁面乳', '美妆个护', '温和清洁，洗后不紧绷', '适合日常洁面，泡沫细腻，建议根据个人肤质进行试用。', 59.90, 220, 2054, 'https://images.unsplash.com/photo-1556228720-195a672e8a03?auto=format&fit=crop&w=800&q=80'),
                    (16, '牛奶燕麦早餐组合', '食品饮料', '早餐搭配省心，办公室也方便', '包含即食燕麦和常温牛奶，适合早餐、加班和宿舍囤货。', 49.90, 260, 3321, 'https://images.unsplash.com/photo-1517673132405-a56a62b18caf?auto=format&fit=crop&w=800&q=80'),
                    (17, '户外折叠露营椅', '运动户外', '轻便可折叠，露营钓鱼都适用', '加厚支架、透气面料、便携收纳袋，适合公园露营、钓鱼和阳台休闲。', 119.00, 90, 654, 'https://images.unsplash.com/photo-1504280390367-361c6d9f38f4?auto=format&fit=crop&w=800&q=80'),
                    (18, '静音无线鼠标', '电脑办公', '轻音按键，多设备办公更安静', '人体工学握感、无线连接、长续航设计，适合办公室、宿舍和会议室。', 89.00, 210, 1765, 'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?auto=format&fit=crop&w=800&q=80'),
                    (19, '便携榨汁杯', '家居日用', '随身鲜榨，宿舍办公室都能用', '充电式设计、食品接触级杯体，适合制作果汁、奶昔和轻食饮品。', 99.00, 170, 887, 'https://images.unsplash.com/photo-1622597467836-f3285f2131b8?auto=format&fit=crop&w=800&q=80'),
                    (20, '儿童学习平板', '手机数码', '护眼大屏，学习资源集中管理', '内置学习模式、家长管控和护眼显示，适合课外学习和家庭辅导。', 899.00, 55, 438, 'https://images.unsplash.com/photo-1588702547919-26089e690ecc?auto=format&fit=crop&w=800&q=80')
                    on duplicate key update
                    category = if(category is null or category = '综合', values(category), category),
                    subtitle = coalesce(subtitle, values(subtitle)),
                    description = coalesce(description, values(description)),
                    stock = greatest(stock, values(stock)),
                    sale_count = greatest(sale_count, values(sale_count)),
                    image = coalesce(image, values(image))
                    """);
            statement.executeUpdate("""
                    insert ignore into `comment`(id, user_id, product_id, content, score, create_time) values
                    (1, 1, 1, '耳机连接很快，通勤路上降噪够用，续航也不错。', 5, now()),
                    (2, 2, 1, '佩戴比较轻，打电话声音清楚。', 4, now()),
                    (3, 1, 2, '键盘手感扎实，办公打字很舒服。', 5, now()),
                    (4, 2, 5, '空气炸锅容量合适，做鸡翅和薯条很方便。', 5, now()),
                    (5, 1, 6, '保温效果不错，杯盖密封性也好。', 5, now()),
                    (6, 2, 8, '坚果分量足，独立包装很适合办公室。', 4, now()),
                    (7, 1, 9, '投影亮度比预期好，晚上看电影很舒服。', 5, now()),
                    (8, 2, 10, '鞋底缓震可以，日常慢跑够用。', 4, now()),
                    (9, 5, 11, '手环运动记录比较准确，消息提醒也及时。', 5, now()),
                    (10, 6, 12, '台灯亮度柔和，晚上写作业不刺眼。', 5, now()),
                    (11, 1, 13, '扫地路线规划清楚，日常浮灰清理很省事。', 4, now()),
                    (12, 5, 14, '防晒衣轻薄透气，骑车通勤刚好。', 4, now()),
                    (13, 2, 15, '洁面后不紧绷，味道也比较清淡。', 5, now()),
                    (14, 6, 16, '早餐组合很方便，放办公室很合适。', 4, now()),
                    (15, 1, 17, '椅子收纳方便，周末露营带着不占地方。', 5, now()),
                    (16, 2, 18, '鼠标点击声音小，会议室用也不打扰。', 5, now()),
                    (17, 5, 19, '榨汁杯清洗方便，杯体大小适合随身带。', 4, now()),
                    (18, 6, 20, '学习模式比较清晰，家长管控功能实用。', 4, now())
                    """);
            statement.executeUpdate("""
                    insert ignore into cart(id, user_id, product_id, count) values
                    (1, 1, 11, 1),
                    (2, 1, 16, 2),
                    (3, 2, 5, 1),
                    (4, 5, 18, 1),
                    (5, 5, 12, 1),
                    (6, 6, 10, 1),
                    (7, 6, 8, 3)
                    """);
            statement.executeUpdate("""
                    insert ignore into favorite(id, user_id, product_id) values
                    (1, 1, 7),
                    (2, 1, 13),
                    (3, 2, 1),
                    (4, 2, 17),
                    (5, 5, 4),
                    (6, 5, 18),
                    (7, 6, 10),
                    (8, 6, 20)
                    """);
            statement.executeUpdate("""
                    insert ignore into order_info(id, order_no, user_id, total_price, status, receiver_name, receiver_phone, shipping_address, create_time) values
                    (1, 'YZMM202606020001', 1, 358.00, 3, '张三', '13800000001', '北京市朝阳区示例路 18 号', '2026-06-02 09:20:00'),
                    (2, 'YZMM202606020002', 2, 378.90, 2, '李四', '13800000002', '上海市浦东新区示例大道 66 号', '2026-06-02 10:35:00'),
                    (3, 'YZMM202606020003', 5, 1599.00, 1, '王五', '13800000005', '江苏省南京市鼓楼区示例街 28 号', '2026-06-02 11:10:00'),
                    (4, 'YZMM202606020004', 6, 338.90, 0, '赵六', '13800000006', '四川省成都市高新区示例路 88 号', '2026-06-02 13:25:00'),
                    (5, 'YZMM202606020005', 1, 4999.00, 3, '张三', '13800000001', '北京市朝阳区示例路 18 号', '2026-06-02 15:40:00'),
                    (6, 'YZMM202606020006', 2, 288.00, 3, '李四', '13800000002', '上海市浦东新区示例大道 66 号', '2026-06-02 16:05:00')
                    """);
            statement.executeUpdate("""
                    insert ignore into order_item(id, order_id, product_id, count, price) values
                    (1, 1, 1, 1, 139.00),
                    (2, 1, 2, 1, 219.00),
                    (3, 2, 5, 1, 299.00),
                    (4, 2, 8, 1, 79.90),
                    (5, 3, 9, 1, 1599.00),
                    (6, 4, 10, 1, 269.00),
                    (7, 4, 6, 1, 69.90),
                    (8, 5, 7, 1, 4999.00),
                    (9, 6, 11, 1, 199.00),
                    (10, 6, 18, 1, 89.00)
                    """);
            statement.executeUpdate("""
                    insert ignore into logistics(id, order_id, logistics_no, status, update_time) values
                    (1, 1, 'WL202606020001', 'signed', '2026-06-02 18:00:00'),
                    (2, 2, 'WL202606020002', 'shipping', '2026-06-02 18:10:00'),
                    (3, 5, 'WL202606020005', 'signed', '2026-06-02 18:20:00'),
                    (4, 6, 'WL202606020006', 'signed', '2026-06-02 18:30:00')
                    """);
        }

        private static void addColumnIfMissing(Statement statement, String sql) throws SQLException {
            try {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                if (e.getErrorCode() != 1060) {
                    throw e;
                }
            }
        }

        private static void addIndexIfMissing(Statement statement, String sql) throws SQLException {
            try {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                if (e.getErrorCode() != 1061) {
                    throw e;
                }
            }
        }

        private static void addForeignKeyIfMissing(Statement statement, String sql) throws SQLException {
            try {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                if (e.getErrorCode() != 1022 && e.getErrorCode() != 1826) {
                    throw e;
                }
            }
        }
    }
}
