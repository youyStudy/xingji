-- ============================================================
-- 行迹 · 智能旅游行程规划与管理系统  数据库脚本
-- MySQL 8 / InnoDB / utf8mb4
-- 主键外键统一 INT(对应 Java Integer)；11 表(含 user_auth)
-- user / itinerary 含 deleted 软删除；全表含 create/update_time
-- 在 Workbench 查询窗口选中 xingji 库后整份执行
-- ============================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS user_auth, share_link, travel_media, route_segment,
  itinerary_node, place, itinerary_day, itinerary, chat_message,
  chat_session, user;
SET FOREIGN_KEY_CHECKS = 1;

-- 1. 用户
CREATE TABLE `user` (
  id          INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
  phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号(登录用)',
  email       VARCHAR(64)  DEFAULT NULL COMMENT '邮箱(登录用)',
  password    VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt哈希,第三方用户可空',
  nickname    VARCHAR(32)  DEFAULT NULL COMMENT '昵称',
  avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  avatar_src  VARCHAR(255) DEFAULT NULL COMMENT '头像取景原图URL(重取时回到它,避免越裁越小)',
  gender      TINYINT      NOT NULL DEFAULT 0 COMMENT '性别:0-保密 1-男 2-女',
  role        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-普通用户 1-管理员',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除:0-正常 1-已删除',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone),
  UNIQUE KEY uk_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(UC01)';

-- 3. 会话
CREATE TABLE chat_session (
  id          INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     INT         NOT NULL COMMENT '所属用户',
  title       VARCHAR(64) DEFAULT NULL COMMENT '会话标题',
  context     TEXT        COMMENT '需求上下文(JSON)',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_user (user_id),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES `user`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表(UC02)';

-- 4. 消息
CREATE TABLE chat_message (
  id          INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  session_id  INT         NOT NULL COMMENT '所属会话',
  role        VARCHAR(16) NOT NULL COMMENT 'user / assistant',
  content     TEXT        COMMENT '消息正文',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_session (session_id),
  CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES chat_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表(UC02)';

-- 5. 行程
CREATE TABLE itinerary (
  id          INT           NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     INT           NOT NULL COMMENT '所属用户(REQ-008 数据隔离)',
  session_id  INT           DEFAULT NULL COMMENT '来源会话',
  title       VARCHAR(64)   DEFAULT NULL COMMENT '行程标题',
  city        VARCHAR(32)   DEFAULT NULL COMMENT '目的地城市(校验时限定搜索范围)',
  start_date  DATE          DEFAULT NULL COMMENT '出发日期',
  days        TINYINT       DEFAULT NULL COMMENT '总天数',
  budget      DECIMAL(10,2) DEFAULT NULL COMMENT '预算',
  advice      TEXT          DEFAULT NULL COMMENT 'AI 方案说明(JSON):budgetPlan 预算拆分/reminders 预约与风险提醒/backups 备选方案',
  pinned      TINYINT       NOT NULL DEFAULT 0 COMMENT '置顶:0-否 1-是',
  status      TINYINT       NOT NULL DEFAULT 0 COMMENT '0-草稿 1-已生成 2-已校验',
  deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '软删除:0-正常 1-已删除',
  create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_user (user_id),
  KEY idx_session (session_id),
  CONSTRAINT fk_itin_user FOREIGN KEY (user_id) REFERENCES `user`(id),
  CONSTRAINT fk_itin_session FOREIGN KEY (session_id) REFERENCES chat_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程主表(UC03/04)';

-- 6. 行程日
CREATE TABLE itinerary_day (
  id           INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  itinerary_id INT         NOT NULL COMMENT '所属行程',
  day_index    TINYINT     NOT NULL COMMENT '第几天',
  theme        VARCHAR(64) DEFAULT NULL COMMENT '当天主题',
  date         DATE        DEFAULT NULL COMMENT '具体日期',
  create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_itin (itinerary_id),
  CONSTRAINT fk_day_itin FOREIGN KEY (itinerary_id) REFERENCES itinerary(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程日表(UC03/04)';

-- 7. 地点(高德POI缓存)
CREATE TABLE place (
  id          INT           NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(64)   DEFAULT NULL COMMENT '地点名称',
  amap_poi_id VARCHAR(64)   DEFAULT NULL COMMENT '高德POI唯一ID',
  longitude   DECIMAL(10,6) DEFAULT NULL COMMENT '经度(GCJ-02)',
  latitude    DECIMAL(10,6) DEFAULT NULL COMMENT '纬度(GCJ-02)',
  city        VARCHAR(32)   DEFAULT NULL COMMENT '城市',
  category    VARCHAR(128)  DEFAULT NULL COMMENT '类别',
  address     VARCHAR(128)  DEFAULT NULL COMMENT '详细地址',
  create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_amap (amap_poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地点表(REQ-013 高德POI缓存)';

-- 8. 行程节点
CREATE TABLE itinerary_node (
  id          INT           NOT NULL AUTO_INCREMENT COMMENT '主键',
  day_id      INT           NOT NULL COMMENT '所属行程日',
  place_id    INT           DEFAULT NULL COMMENT '关联地点',
  place_name  VARCHAR(128)  DEFAULT NULL COMMENT '地点名称(生成阶段先存文字,校验后才回填place_id)',
  node_type   VARCHAR(16)   DEFAULT NULL COMMENT '景点/餐饮/交通/住宿',
  seq         INT           DEFAULT NULL COMMENT '当天顺序',
  start_time  TIME          DEFAULT NULL COMMENT '开始时间',
  end_time    TIME          DEFAULT NULL COMMENT '结束时间',
  cost        DECIMAL(10,2) DEFAULT NULL COMMENT '花费',
  is_abnormal TINYINT       NOT NULL DEFAULT 0 COMMENT '1-高德未匹配异常(REQ-013)',
  create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_day (day_id),
  KEY idx_place (place_id),
  CONSTRAINT fk_node_day FOREIGN KEY (day_id) REFERENCES itinerary_day(id),
  CONSTRAINT fk_node_place FOREIGN KEY (place_id) REFERENCES place(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程节点表(UC03/04)';

-- 9. 路线段
CREATE TABLE route_segment (
  id           INT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  itinerary_id INT      NOT NULL COMMENT '所属行程',
  from_node_id INT      NOT NULL COMMENT '起点节点',
  to_node_id   INT      NOT NULL COMMENT '终点节点',
  distance     INT      DEFAULT NULL COMMENT '距离(米)',
  duration     INT      DEFAULT NULL COMMENT '耗时(秒)',
  polyline     TEXT     COMMENT '折线坐标串',
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_itin (itinerary_id),
  KEY idx_from (from_node_id),
  KEY idx_to (to_node_id),
  CONSTRAINT fk_seg_itin FOREIGN KEY (itinerary_id) REFERENCES itinerary(id),
  CONSTRAINT fk_seg_from FOREIGN KEY (from_node_id) REFERENCES itinerary_node(id),
  CONSTRAINT fk_seg_to FOREIGN KEY (to_node_id) REFERENCES itinerary_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路线段表(REQ-012 相邻节点真实路线)';

-- 10. 旅行记录/素材
CREATE TABLE travel_media (
  id           INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id      INT          NOT NULL COMMENT '所属用户',
  itinerary_id INT          DEFAULT NULL COMMENT '关联行程',
  place_id     INT          DEFAULT NULL COMMENT '关联地点',
  region       VARCHAR(64)  DEFAULT NULL COMMENT '地区分类',
  title        VARCHAR(128) DEFAULT NULL COMMENT '照片标题',
  media_type   VARCHAR(8)   DEFAULT NULL COMMENT 'image / video',
  file_path    VARCHAR(255) DEFAULT NULL COMMENT '文件落盘路径',
  file_size    INT          DEFAULT NULL COMMENT '文件大小(字节)',
  upload_time  DATETIME     DEFAULT NULL COMMENT '素材实际上传时间',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除:0-正常 1-已删除(进回收站)',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_user (user_id),
  KEY idx_itin (itinerary_id),
  KEY idx_place (place_id),
  CONSTRAINT fk_media_user FOREIGN KEY (user_id) REFERENCES `user`(id),
  CONSTRAINT fk_media_itin FOREIGN KEY (itinerary_id) REFERENCES itinerary(id),
  CONSTRAINT fk_media_place FOREIGN KEY (place_id) REFERENCES place(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅行记录表(UC06)';

-- 11. 分享链接
CREATE TABLE share_link (
  id           INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  itinerary_id INT         NOT NULL COMMENT '被分享行程',
  token        VARCHAR(64) NOT NULL COMMENT '分享令牌(公开访问)',
  enabled      TINYINT     NOT NULL DEFAULT 1 COMMENT '1-有效 0-已撤销',
  create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_token (token),
  KEY idx_itin (itinerary_id),
  CONSTRAINT fk_share_itin FOREIGN KEY (itinerary_id) REFERENCES itinerary(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享链接表(UC07)';

-- 12. 第三方登录绑定(QQ/微信)
CREATE TABLE user_auth (
  id          INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     INT         NOT NULL COMMENT '关联用户',
  provider    VARCHAR(16) NOT NULL COMMENT '平台:qq / wechat',
  open_id     VARCHAR(64) NOT NULL COMMENT '该平台内用户唯一标识',
  union_id    VARCHAR(64) DEFAULT NULL COMMENT '跨应用统一标识(微信unionid)',
  nickname    VARCHAR(64) DEFAULT NULL COMMENT '授权昵称快照',
  avatar      VARCHAR(255) DEFAULT NULL COMMENT '授权头像快照',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_provider_openid (provider, open_id),
  KEY idx_union (union_id),
  KEY idx_user (user_id),
  CONSTRAINT fk_auth_user FOREIGN KEY (user_id) REFERENCES `user`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='第三方登录绑定表';

