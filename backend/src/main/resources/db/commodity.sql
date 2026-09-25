-- 商品类目表
CREATE TABLE IF NOT EXISTS commodity (
  id            INT          NOT NULL AUTO_INCREMENT,
  varieties_id  INT          NOT NULL COMMENT '商品编号',
  name          VARCHAR(64)  NOT NULL COMMENT '商品名称',
  category      VARCHAR(64)  DEFAULT NULL COMMENT '大类',
  unit          VARCHAR(32)  DEFAULT NULL COMMENT '默认单位',
  has_market    TINYINT      NOT NULL DEFAULT 0 COMMENT '有市场价格',
  has_enterprise TINYINT     NOT NULL DEFAULT 0 COMMENT '有企业价格',
  has_intl      TINYINT      NOT NULL DEFAULT 0 COMMENT '有国际价格',
  market_count  INT          NOT NULL DEFAULT 0 COMMENT '市场记录数',
  enterprise_count INT       NOT NULL DEFAULT 0 COMMENT '企业记录数',
  intl_count    INT          NOT NULL DEFAULT 0 COMMENT '国际记录数',
  earliest_date DATE         DEFAULT NULL COMMENT '最早数据日期',
  latest_date   DATE         DEFAULT NULL COMMENT '最新数据日期',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_varieties_id (varieties_id),
  KEY idx_name (name),
  KEY idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品类目主表';
