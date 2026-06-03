package org.example.demo.common;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
                        create table if not exists `user` (
                            id int primary key auto_increment,
                            username varchar(50) not null unique,
                            password varchar(255) not null,
                            nickname varchar(50),
                            phone varchar(11),
                            address varchar(200),
                            tags varchar(200),
                            status tinyint not null default 1
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("alter table `user` modify column password varchar(255) not null");
                addColumnIfMissing(statement, "alter table `user` add column nickname varchar(50)");
                addColumnIfMissing(statement, "alter table `user` add column address varchar(200)");
                addColumnIfMissing(statement, "alter table `user` add column tags varchar(200)");
                addColumnIfMissing(statement, "alter table `user` add column status tinyint not null default 1");
                statement.executeUpdate("update `user` set status = 1 where status is null");
                statement.executeUpdate("""
                        create table if not exists auth_token (
                            id int primary key auto_increment,
                            account_type varchar(20) not null default 'user',
                            user_id int,
                            admin_id int,
                            token_hash char(64) not null unique,
                            expire_time timestamp not null,
                            create_time timestamp not null default current_timestamp,
                            key idx_auth_token_user (user_id),
                            key idx_auth_token_admin (admin_id)
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table auth_token add column account_type varchar(20) not null default 'user'");
                addColumnIfMissing(statement, "alter table auth_token add column admin_id int");
                dropForeignKeyIfExists(statement, "alter table auth_token drop foreign key fk_auth_token_user");
                statement.executeUpdate("alter table auth_token modify column user_id int");
                statement.executeUpdate("""
                        create table if not exists login_attempt (
                            username varchar(50) primary key,
                            failed_count int not null default 0,
                            last_failed_time timestamp null,
                            locked_until timestamp null,
                            last_success_time timestamp null
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists `admin` (
                            id int primary key auto_increment,
                            username varchar(50) not null unique,
                            password varchar(255) not null,
                            nickname varchar(50),
                            phone varchar(11),
                            admin_level varchar(20) not null default 'admin',
                            status tinyint not null default 1,
                            department varchar(50),
                            remark varchar(200),
                            create_time timestamp not null default current_timestamp
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table `admin` add column username varchar(50)");
                addColumnIfMissing(statement, "alter table `admin` add column password varchar(255)");
                addColumnIfMissing(statement, "alter table `admin` add column nickname varchar(50)");
                addColumnIfMissing(statement, "alter table `admin` add column phone varchar(11)");
                addColumnIfMissing(statement, "alter table `admin` add column admin_level varchar(20) not null default 'admin'");
                addColumnIfMissing(statement, "alter table `admin` add column status tinyint not null default 1");
                addColumnIfMissing(statement, "alter table `admin` add column department varchar(50)");
                addColumnIfMissing(statement, "alter table `admin` add column remark varchar(200)");
                addColumnIfMissing(statement, "alter table `admin` add column create_time timestamp not null default current_timestamp");
                ignoreError(statement, "alter table `admin` modify column user_id int", 1054);
                ignoreError(statement, """
                        update `admin` a
                        join `user` u on u.id = a.user_id
                        set a.username = coalesce(a.username, u.username),
                            a.password = coalesce(a.password, u.password),
                            a.nickname = coalesce(a.nickname, u.nickname),
                            a.phone = coalesce(a.phone, u.phone),
                            a.admin_level = case when u.username = 'superadmin' then 'super_admin' else a.admin_level end
                        where a.username is null or a.password is null
                        """, 1054);
                ignoreError(statement, """
                        insert into `admin`(username, password, nickname, phone, admin_level, status, department, remark)
                        select username, password, coalesce(nickname, username), phone,
                               case when role = 'super_admin' then 'super_admin' else 'admin' end,
                               coalesce(status, 1), '运营中心', '由旧用户角色迁移'
                        from `user`
                        where role in ('admin', 'super_admin')
                        on duplicate key update
                        password = values(password),
                        nickname = values(nickname),
                        phone = values(phone),
                        admin_level = values(admin_level),
                        status = values(status)
                        """, 1054);
                ignoreError(statement, "update `user` set status = 0 where role in ('admin', 'super_admin')", 1054);
                dropForeignKeysForColumnIfExists(connection, "admin", "user_id");
                ignoreError(statement, "alter table `admin` drop column user_id", 1091);
                ignoreError(statement, "alter table `user` drop column role", 1091);
                statement.executeUpdate("update `admin` set status = 1 where status is null");
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
                            status tinyint not null default 1,
                            image mediumtext
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table product add column category varchar(50) not null default '综合'");
                addColumnIfMissing(statement, "alter table product add column subtitle varchar(200)");
                addColumnIfMissing(statement, "alter table product add column description text");
                addColumnIfMissing(statement, "alter table product add column sale_count int not null default 0");
                addColumnIfMissing(statement, "alter table product add column status tinyint not null default 1");
                statement.executeUpdate("update product set status = 1 where status is null");
                statement.executeUpdate("alter table product modify column image mediumtext");
                statement.executeUpdate("""
                        create table if not exists product_sku (
                            id int primary key auto_increment,
                            product_id int not null,
                            spec_name varchar(50) not null,
                            spec_value varchar(80) not null,
                            price decimal(10, 2) not null default 0.00,
                            stock int not null default 0,
                            image mediumtext,
                            sort_order int not null default 0,
                            unique key uk_product_sku_spec (product_id, spec_name, spec_value),
                            key idx_product_sku_product (product_id),
                            constraint fk_product_sku_product foreign key (product_id) references product(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists coupon (
                            id int primary key auto_increment,
                            name varchar(80) not null,
                            code varchar(50) not null unique,
                            discount_amount decimal(10, 2) not null default 0.00,
                            threshold_amount decimal(10, 2) not null default 0.00,
                            stock int not null default 0,
                            status tinyint not null default 1,
                            start_time timestamp null,
                            end_time timestamp null,
                            create_time timestamp not null default current_timestamp
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists user_coupon (
                            id int primary key auto_increment,
                            user_id int not null,
                            coupon_id int not null,
                            status tinyint not null default 0,
                            used_order_id int,
                            receive_time timestamp not null default current_timestamp,
                            used_time timestamp null,
                            unique key uk_user_coupon (user_id, coupon_id),
                            key idx_user_coupon_user (user_id),
                            key idx_user_coupon_coupon (coupon_id),
                            constraint fk_user_coupon_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade,
                            constraint fk_user_coupon_coupon foreign key (coupon_id) references coupon(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                statement.executeUpdate("""
                        create table if not exists cart (
                            id int primary key auto_increment,
                            user_id int not null,
                            product_id int not null,
                            sku_id int,
                            count int not null default 1,
                            update_time timestamp not null default current_timestamp on update current_timestamp,
                            unique key uk_cart_user_product_sku (user_id, product_id, sku_id),
                            constraint fk_cart_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade,
                            constraint fk_cart_product foreign key (product_id) references product(id)
                                on delete cascade on update cascade,
                            constraint fk_cart_sku foreign key (sku_id) references product_sku(id)
                                on delete set null on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table cart add column sku_id int");
                addColumnIfMissing(statement, "alter table cart add column update_time timestamp not null default current_timestamp on update current_timestamp");
                dropIndexIfExists(statement, "alter table cart drop index uk_cart_user_product");
                addIndexIfMissing(statement, "alter table cart add unique key uk_cart_user_product_sku (user_id, product_id, sku_id)");
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
                            discount_amount decimal(10, 2) not null default 0.00,
                            coupon_id int,
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
                addColumnIfMissing(statement, "alter table order_info add column discount_amount decimal(10, 2) not null default 0.00");
                addColumnIfMissing(statement, "alter table order_info add column coupon_id int");
                addColumnIfMissing(statement, "alter table order_info add column receiver_name varchar(50)");
                addColumnIfMissing(statement, "alter table order_info add column receiver_phone varchar(20)");
                addColumnIfMissing(statement, "alter table order_info add column shipping_address varchar(200)");
                statement.executeUpdate("""
                        create table if not exists order_item (
                            id int primary key auto_increment,
                            order_id int not null,
                            product_id int not null,
                            sku_id int,
                            sku_text varchar(120),
                            count int not null,
                            price decimal(10, 2) not null,
                            key idx_order_item_order (order_id),
                            key idx_order_item_product (product_id),
                            key idx_order_item_sku (sku_id),
                            constraint fk_order_item_order foreign key (order_id) references order_info(id)
                                on delete cascade on update cascade,
                            constraint fk_order_item_product foreign key (product_id) references product(id)
                                on delete restrict on update cascade,
                            constraint fk_order_item_sku foreign key (sku_id) references product_sku(id)
                                on delete set null on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table order_item add column sku_id int");
                addColumnIfMissing(statement, "alter table order_item add column sku_text varchar(120)");
                statement.executeUpdate("""
                        create table if not exists `comment` (
                            id int primary key auto_increment,
                            user_id int not null,
                            product_id int not null,
                            content varchar(500) not null,
                            score tinyint not null,
                            images mediumtext,
                            create_time timestamp not null default current_timestamp,
                            key idx_comment_user (user_id),
                            key idx_comment_product (product_id),
                            constraint fk_comment_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade,
                            constraint fk_comment_product foreign key (product_id) references product(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table `comment` add column images mediumtext");
                statement.executeUpdate("""
                        create table if not exists logistics (
                            id int primary key auto_increment,
                            order_id int not null unique,
                            logistics_no varchar(50) not null,
                            courier_company varchar(50),
                            delivery_method varchar(50),
                            status varchar(20) not null default 'pending',
                            update_time timestamp not null default current_timestamp on update current_timestamp,
                            constraint fk_logistics_order foreign key (order_id) references order_info(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                addColumnIfMissing(statement, "alter table logistics add column courier_company varchar(50)");
                addColumnIfMissing(statement, "alter table logistics add column delivery_method varchar(50)");
                statement.executeUpdate("""
                        create table if not exists after_sale (
                            id int primary key auto_increment,
                            order_id int not null,
                            user_id int not null,
                            type varchar(20) not null,
                            reason varchar(500) not null,
                            status varchar(20) not null default 'pending',
                            reply varchar(500),
                            create_time timestamp not null default current_timestamp,
                            update_time timestamp not null default current_timestamp on update current_timestamp,
                            key idx_after_sale_order (order_id),
                            key idx_after_sale_user (user_id),
                            constraint fk_after_sale_order foreign key (order_id) references order_info(id)
                                on delete cascade on update cascade,
                            constraint fk_after_sale_user foreign key (user_id) references `user`(id)
                                on delete cascade on update cascade
                        ) engine=InnoDB default charset=utf8mb4
                        """);
                ensureForeignKeys(statement);
                seed(statement);
            }
        }

        private static void ensureForeignKeys(Statement statement) throws SQLException {
            addIndexIfMissing(statement, "alter table `admin` add unique key uk_admin_username (username)");
            addIndexIfMissing(statement, "alter table auth_token add index idx_auth_token_user (user_id)");
            addIndexIfMissing(statement, "alter table auth_token add index idx_auth_token_admin (admin_id)");
            addIndexIfMissing(statement, "alter table product_sku add index idx_product_sku_product (product_id)");
            addIndexIfMissing(statement, "alter table user_coupon add index idx_user_coupon_user (user_id)");
            addIndexIfMissing(statement, "alter table user_coupon add index idx_user_coupon_coupon (coupon_id)");
            addIndexIfMissing(statement, "alter table user_coupon add index idx_user_coupon_used_order (used_order_id)");
            addIndexIfMissing(statement, "alter table order_info add index idx_order_info_user (user_id)");
            addIndexIfMissing(statement, "alter table order_info add index idx_order_info_coupon (coupon_id)");
            addIndexIfMissing(statement, "alter table order_item add index idx_order_item_order (order_id)");
            addIndexIfMissing(statement, "alter table order_item add index idx_order_item_product (product_id)");
            addIndexIfMissing(statement, "alter table order_item add index idx_order_item_sku (sku_id)");
            addIndexIfMissing(statement, "alter table `comment` add index idx_comment_user (user_id)");
            addIndexIfMissing(statement, "alter table `comment` add index idx_comment_product (product_id)");
            addIndexIfMissing(statement, "alter table after_sale add index idx_after_sale_order (order_id)");
            addIndexIfMissing(statement, "alter table after_sale add index idx_after_sale_user (user_id)");

            addForeignKeyIfMissing(statement, """
                    alter table auth_token add constraint fk_auth_token_user
                    foreign key (user_id) references `user`(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table auth_token add constraint fk_auth_token_admin
                    foreign key (admin_id) references `admin`(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table product_sku add constraint fk_product_sku_product
                    foreign key (product_id) references product(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table user_coupon add constraint fk_user_coupon_user
                    foreign key (user_id) references `user`(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table user_coupon add constraint fk_user_coupon_coupon
                    foreign key (coupon_id) references coupon(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table user_coupon add constraint fk_user_coupon_used_order
                    foreign key (used_order_id) references order_info(id)
                    on delete set null on update cascade
                    """);
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
                    alter table cart add constraint fk_cart_sku
                    foreign key (sku_id) references product_sku(id)
                    on delete set null on update cascade
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
                    alter table order_info add constraint fk_order_info_coupon
                    foreign key (coupon_id) references coupon(id)
                    on delete set null on update cascade
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
                    alter table order_item add constraint fk_order_item_sku
                    foreign key (sku_id) references product_sku(id)
                    on delete set null on update cascade
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
            addForeignKeyIfMissing(statement, """
                    alter table after_sale add constraint fk_after_sale_order
                    foreign key (order_id) references order_info(id)
                    on delete cascade on update cascade
                    """);
            addForeignKeyIfMissing(statement, """
                    alter table after_sale add constraint fk_after_sale_user
                    foreign key (user_id) references `user`(id)
                    on delete cascade on update cascade
                    """);
        }

        private static void seed(Statement statement) throws SQLException {
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags) values (1, 'zhangsan', '123456', '张三', '13800000001', '北京市朝阳区示例路 18 号', '数码,家居')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags) values (2, 'lisi', '123456', '李四', '13800000002', '上海市浦东新区示例大道 66 号', '食品,运动')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags) values (5, 'wangwu', '123456', '王五', '13800000005', '江苏省南京市鼓楼区示例街 28 号', '办公,美妆,礼品')");
            statement.executeUpdate("insert ignore into `user`(id, username, password, nickname, phone, address, tags) values (6, 'zhaoliu', '123456', '赵六', '13800000006', '四川省成都市高新区示例路 88 号', '运动,数码,食品')");
            statement.executeUpdate("update `user` set nickname = coalesce(nickname, username)");
            statement.executeUpdate("update `user` set status = 0 where username in ('admin', 'superadmin')");
            statement.executeUpdate("""
                    insert into `admin`(id, username, password, nickname, phone, admin_level, department, remark, status) values
                    (1, 'admin', '123456', '运营管理员', '13800000003', 'admin', '商品运营部', '负责商品、订单、评价和物流日常维护', 1),
                    (2, 'superadmin', '123456', '超级管理员', '13800000000', 'super_admin', '平台管理部', '负责用户权限和平台配置', 1)
                    on duplicate key update
                    username = values(username),
                    nickname = values(nickname),
                    phone = values(phone),
                    admin_level = values(admin_level),
                    department = values(department),
                    remark = values(remark),
                    status = values(status)
                    """);
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
                    insert into product_sku(id, product_id, spec_name, spec_value, price, stock, image, sort_order) values
                    (1, 1, '颜色', '雅黑', 139.00, 60, null, 1),
                    (2, 1, '颜色', '珍珠白', 149.00, 45, null, 2),
                    (3, 2, '轴体', '茶轴', 219.00, 35, null, 1),
                    (4, 2, '轴体', '红轴', 229.00, 28, null, 2),
                    (5, 7, '内存', '16G+512G', 4999.00, 20, null, 1),
                    (6, 7, '内存', '32G+1T', 5899.00, 12, null, 2),
                    (7, 10, '尺码', '39', 269.00, 18, null, 1),
                    (8, 10, '尺码', '40', 269.00, 20, null, 2),
                    (9, 10, '尺码', '41', 269.00, 22, null, 3),
                    (10, 15, '规格', '120g', 59.90, 90, null, 1),
                    (11, 15, '规格', '120g*2', 99.00, 55, null, 2)
                    on duplicate key update
                    price = values(price),
                    stock = greatest(stock, values(stock)),
                    sort_order = values(sort_order)
                    """);
            statement.executeUpdate("""
                    insert into coupon(id, name, code, discount_amount, threshold_amount, stock, status, start_time, end_time) values
                    (1, '新人满 99 减 10', 'NEW10', 10.00, 99.00, 500, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59'),
                    (2, '家电满 499 减 50', 'HOME50', 50.00, 499.00, 300, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59'),
                    (3, '全场满 199 减 20', 'ALL20', 20.00, 199.00, 800, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59')
                    on duplicate key update
                    name = values(name),
                    discount_amount = values(discount_amount),
                    threshold_amount = values(threshold_amount),
                    stock = greatest(stock, values(stock)),
                    status = values(status)
                    """);
            statement.executeUpdate("""
                    insert ignore into user_coupon(id, user_id, coupon_id, status) values
                    (1, 1, 1, 0),
                    (2, 1, 3, 0),
                    (3, 2, 1, 0),
                    (4, 5, 3, 0),
                    (5, 6, 2, 0)
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
                    insert into logistics(id, order_id, logistics_no, courier_company, delivery_method, status, update_time) values
                    (1, 1, 'WL202606020001', '顺丰速运', '标准配送', 'signed', '2026-06-02 18:00:00'),
                    (2, 2, 'WL202606020002', '京东快递', '次日达', 'delivering', '2026-06-02 18:10:00'),
                    (3, 5, 'WL202606020005', '中通快递', '标准配送', 'signed', '2026-06-02 18:20:00'),
                    (4, 6, 'WL202606020006', '圆通速递', '普通配送', 'signed', '2026-06-02 18:30:00')
                    on duplicate key update
                    courier_company = values(courier_company),
                    delivery_method = values(delivery_method),
                    status = values(status)
                    """);
            statement.executeUpdate("""
                    insert ignore into after_sale(id, order_id, user_id, type, reason, status, reply, create_time) values
                    (1, 1, 1, 'return', '包装完好，想申请退货测试售后流程', 'approved', '已通过，请保持商品完好等待上门取件。', '2026-06-02 19:00:00'),
                    (2, 2, 2, 'repair', '收到后需要咨询使用问题', 'processing', '客服已接入，正在核对订单信息。', '2026-06-02 19:30:00')
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

        private static void dropIndexIfExists(Statement statement, String sql) throws SQLException {
            try {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                if (e.getErrorCode() != 1091) {
                    throw e;
                }
            }
        }

        private static void dropForeignKeyIfExists(Statement statement, String sql) throws SQLException {
            try {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                if (e.getErrorCode() != 1091) {
                    throw e;
                }
            }
        }

        private static void ignoreError(Statement statement, String sql, int errorCode) throws SQLException {
            try {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                if (e.getErrorCode() != errorCode) {
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

        private static void dropForeignKeysForColumnIfExists(Connection connection, String table, String column) throws SQLException {
            List<String> names = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select constraint_name
                    from information_schema.key_column_usage
                    where constraint_schema = database()
                      and table_name = ?
                      and column_name = ?
                      and referenced_table_name is not null
                    """)) {
                statement.setString(1, table);
                statement.setString(2, column);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        names.add(resultSet.getString("constraint_name"));
                    }
                }
            }
            for (String name : names) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("alter table " + identifier(table) + " drop foreign key " + identifier(name));
                }
            }
        }
    }
}
