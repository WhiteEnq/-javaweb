set names utf8mb4;
set foreign_key_checks = 0;

create database if not exists `YZMM_Mall` default character set utf8mb4 collate utf8mb4_unicode_ci;

drop table if exists `YZMM_Mall`.`logistics`;
drop table if exists `YZMM_Mall`.`comment`;
drop table if exists `YZMM_Mall`.`order_item`;
drop table if exists `YZMM_Mall`.`order_info`;
drop table if exists `YZMM_Mall`.`favorite`;
drop table if exists `YZMM_Mall`.`cart`;
drop table if exists `YZMM_Mall`.`product`;
drop table if exists `YZMM_Mall`.`user`;
drop table if exists `YZMM_Mall`.`admin`;

create table `YZMM_Mall`.`admin` (
    `id` int primary key auto_increment,
    `username` varchar(50) not null unique,
    `password` varchar(50) not null
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`user` (
    `id` int primary key auto_increment,
    `username` varchar(50) not null unique,
    `password` varchar(50) not null,
    `nickname` varchar(50),
    `phone` varchar(11),
    `address` varchar(200),
    `tags` varchar(200),
    `role` varchar(20) not null default 'user'
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`product` (
    `id` int primary key auto_increment,
    `name` varchar(100) not null,
    `category` varchar(50) not null default '综合',
    `subtitle` varchar(200),
    `description` text,
    `price` decimal(10, 2) not null default 0.00,
    `stock` int not null default 0,
    `sale_count` int not null default 0,
    `image` mediumtext
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`cart` (
    `id` int primary key auto_increment,
    `user_id` int not null,
    `product_id` int not null,
    `count` int not null default 1,
    unique key `uk_cart_user_product` (`user_id`, `product_id`)
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`favorite` (
    `id` int primary key auto_increment,
    `user_id` int not null,
    `product_id` int not null,
    unique key `uk_favorite_user_product` (`user_id`, `product_id`)
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`order_info` (
    `id` int primary key auto_increment,
    `order_no` varchar(50) not null unique,
    `user_id` int not null,
    `total_price` decimal(10, 2) not null,
    `status` tinyint not null default 0 comment '0待支付 1已支付 2已发货 3已完成',
    `receiver_name` varchar(50),
    `receiver_phone` varchar(20),
    `shipping_address` varchar(200),
    `create_time` timestamp not null default current_timestamp
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`order_item` (
    `id` int primary key auto_increment,
    `order_id` int not null,
    `product_id` int not null,
    `count` int not null,
    `price` decimal(10, 2) not null
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`comment` (
    `id` int primary key auto_increment,
    `user_id` int not null,
    `product_id` int not null,
    `content` varchar(500) not null,
    `score` tinyint not null,
    `create_time` timestamp not null default current_timestamp
) engine=InnoDB default charset=utf8mb4;

create table `YZMM_Mall`.`logistics` (
    `id` int primary key auto_increment,
    `order_id` int not null unique,
    `logistics_no` varchar(50) not null,
    `status` varchar(20) not null default 'pending',
    `update_time` timestamp not null default current_timestamp on update current_timestamp
) engine=InnoDB default charset=utf8mb4;

insert into `YZMM_Mall`.`admin`(`username`, `password`) values ('admin', '123456');

insert into `YZMM_Mall`.`user`(`username`, `password`, `nickname`, `phone`, `address`, `tags`, `role`) values
('zhangsan', '123456', '张三', '13800000001', '北京市朝阳区示例路 18 号', '数码,家居', 'user'),
('lisi', '123456', '李四', '13800000002', '上海市浦东新区示例大道 66 号', '食品,运动', 'user'),
('admin', '123456', '运营管理员', '13800000003', '广东省深圳市南山区示例中心', '运营,商品', 'admin'),
('superadmin', '123456', '超级管理员', '13800000000', '浙江省杭州市西湖区示例园区', '管理,数据', 'super_admin');

insert into `YZMM_Mall`.`product`(`name`, `category`, `subtitle`, `description`, `price`, `stock`, `sale_count`, `image`) values
('无线蓝牙耳机', '手机数码', '低延迟蓝牙连接，通勤运动都能稳稳佩戴', '高清通话、长续航充电仓、轻量化入耳设计。适合通勤、运动、视频会议等日常场景。', 139.00, 120, 2380, 'https://images.unsplash.com/photo-1606220588913-b3aacb4d2f46?auto=format&fit=crop&w=800&q=80'),
('机械键盘', '电脑办公', '全键热插拔轴体，办公和游戏都顺手', '紧凑配列、双色键帽、稳定大键调校，支持多设备桌面使用。', 219.00, 80, 1689, 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=800&q=80'),
('通勤双肩包', '服饰箱包', '大容量分区收纳，电脑文件都能装', '防泼水面料、独立电脑仓、舒适肩带，适合上班、短途出行和校园使用。', 129.00, 100, 956, 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=800&q=80'),
('保湿面膜套装', '美妆个护', '密集补水，换季护理常备', '温和亲肤膜布，适合日常补水和妆前护理。建议根据肤质进行试用。', 99.00, 110, 1842, 'https://images.unsplash.com/photo-1556228578-8c89e6adf883?auto=format&fit=crop&w=800&q=80'),
('多功能空气炸锅', '家用电器', '少油烹饪，一台搞定多种家常菜', '可视化烹饪窗口，多档温控，适合薯条、鸡翅、烤蔬菜等家庭菜品。', 299.00, 65, 764, 'https://images.unsplash.com/photo-1626201850124-70977949c655?auto=format&fit=crop&w=800&q=80'),
('不锈钢保温杯', '家居日用', '长效保温保冷，随身携带不漏水', '食品接触级不锈钢内胆，杯盖密封设计，适合办公室、通勤和户外。', 69.90, 200, 3120, 'https://images.unsplash.com/photo-1602143407151-7111542de6e8?auto=format&fit=crop&w=800&q=80'),
('轻薄笔记本电脑', '电脑办公', '轻薄机身，高效处理学习办公任务', '高清屏幕、长续航电池、丰富接口，满足文档、课程、会议和轻度创作。', 4999.00, 35, 421, 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=800&q=80'),
('每日坚果礼盒', '食品饮料', '多种坚果科学配比，办公室常备零食', '独立小袋包装，方便携带。含多种坚果与果干，适合早餐和下午茶。', 79.90, 150, 2156, 'https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&w=800&q=80'),
('家用智能投影仪', '家用电器', '卧室客厅都能用，大屏观影更沉浸', '自动对焦、梯形校正、内置音响，适合追剧、游戏和家庭观影。', 1599.00, 42, 538, 'https://images.unsplash.com/photo-1626379953822-baec19c3accd?auto=format&fit=crop&w=800&q=80'),
('缓震跑步鞋', '运动户外', '轻量缓震，日常慢跑和通勤都舒适', '透气鞋面、耐磨外底、稳定支撑，适合日常跑步、健身和步行。', 269.00, 95, 1286, 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=800&q=80');

insert into `YZMM_Mall`.`comment`(`user_id`, `product_id`, `content`, `score`) values
(1, 1, '耳机连接很快，通勤路上降噪够用，续航也不错。', 5),
(2, 1, '佩戴比较轻，打电话声音清楚。', 4),
(1, 2, '键盘手感扎实，办公打字很舒服。', 5),
(2, 5, '空气炸锅容量合适，做鸡翅和薯条很方便。', 5),
(1, 6, '保温效果不错，杯盖密封性也好。', 5),
(2, 8, '坚果分量足，独立包装很适合办公室。', 4),
(1, 9, '投影亮度比预期好，晚上看电影很舒服。', 5),
(2, 10, '鞋底缓震可以，日常慢跑够用。', 4);

set foreign_key_checks = 1;

select table_name
from information_schema.tables
where table_schema = 'YZMM_Mall'
order by table_name;
