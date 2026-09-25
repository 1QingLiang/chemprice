-- ============================================================
-- 数据看板系统业务表（longzhong 库）
-- 用户 / 数据权限 / 交易预留表
-- 价格数据: market_price / enterprise_price / international_price
-- ============================================================

-- 1. 系统用户表
CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username    VARCHAR(64)  NOT NULL COMMENT '登录名',
  password    VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码',
  nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  role        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色: ADMIN/USER',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

-- 2. 用户数据权限表（管理员按商品开权限）
CREATE TABLE IF NOT EXISTS data_permission (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT       NOT NULL COMMENT '用户ID',
  varieties_id   INT          NOT NULL COMMENT '商品编号(varietiesId)',
  varieties_name VARCHAR(64)  DEFAULT NULL COMMENT '商品名(冗余)',
  granted_by     BIGINT       DEFAULT NULL COMMENT '授予人用户ID',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  UNIQUE KEY uk_user_variety (user_id, varieties_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户数据权限(按商品)';

-- 3. 数据产品表（交易模块预留，一期仅建表）
CREATE TABLE IF NOT EXISTS data_product (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  product_code VARCHAR(64)   NOT NULL COMMENT '产品编码',
  name         VARCHAR(128)  NOT NULL COMMENT '产品名称',
  description  VARCHAR(512)  DEFAULT NULL COMMENT '产品描述',
  price        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '售价(元)',
  duration_days INT          NOT NULL DEFAULT 30 COMMENT '授权有效期(天)',
  status       TINYINT       NOT NULL DEFAULT 1 COMMENT '状态: 1上架 0下架',
  created_at   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_product_code (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据产品(交易预留)';

-- 4. 交易订单表（交易模块预留，一期仅建表）
CREATE TABLE IF NOT EXISTS trade_order (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  order_no   VARCHAR(32)    NOT NULL COMMENT '订单号',
  user_id    BIGINT         NOT NULL COMMENT '用户ID',
  product_id BIGINT         NOT NULL COMMENT '产品ID',
  amount     DECIMAL(10,2)  NOT NULL COMMENT '订单金额',
  status     TINYINT        NOT NULL DEFAULT 0 COMMENT '状态: 0待支付 1已支付 2已取消 3退款',
  paid_at    DATETIME       DEFAULT NULL COMMENT '支付时间',
  expire_at  DATETIME       DEFAULT NULL COMMENT '授权到期时间',
  created_at DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易订单(预留)';
