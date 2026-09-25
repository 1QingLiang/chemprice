-- ============================================================
-- ChemPrice 化工价格平台 —— 数据库建表语句（表结构参考）
-- ============================================================
-- 来源：生产库 longzhong（MySQL 8.0）
-- 内容：43 张表的 CREATE TABLE 语句，**不含任何数据**
-- 导出：mysqldump --no-data
--
-- ⚠️ 使用说明
--   1. 本文件仅作**表结构参考**，用于开发对照、代码评审、新人上手。
--   2. ⛔ 请勿在生产库直接执行本文件（含 DROP 语句，已统一注释掉）。
--   3. 需要在本地建库时，取消 DROP 行的注释即可（它们是
--      `DROP TABLE IF EXISTS`，对空库无害）。
--   4. 生产库的真实结构以线上为准；本文件为快照，可能滞后。
--
-- ⛔ 本文件不得包含任何数据行（导出时已用 --no-data 保证）
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- DROP TABLE IF EXISTS `ai_chat_log`;
CREATE TABLE `ai_chat_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '提问用户 ID',
  `username` varchar(64) DEFAULT NULL COMMENT '提问用户账号（审计日志里 user_id 常为 NULL，按账号关联更可靠）',
  `nickname` varchar(64) DEFAULT NULL COMMENT '提问用户昵称（冗余快照）',
  `question` varchar(600) NOT NULL COMMENT '用户原始提问',
  `answer` mediumtext COMMENT 'AI 回复正文',
  `tool` varchar(32) DEFAULT NULL COMMENT '命中的意图工具名，如 latest_price / trend / export_data / publish_demand',
  `kind` varchar(16) DEFAULT NULL COMMENT '回复类型：answer 正常 | clarify 需澄清 | error 出错',
  `model` varchar(64) DEFAULT NULL COMMENT '本次调用的模型名',
  `prompt_tokens` int NOT NULL DEFAULT '0' COMMENT '输入 token 数',
  `completion_tokens` int NOT NULL DEFAULT '0' COMMENT '输出 token 数',
  `total_tokens` int NOT NULL DEFAULT '0' COMMENT '总 token 数',
  `cost_ms` int NOT NULL DEFAULT '0' COMMENT '本次问答耗时（毫秒）',
  `src` varchar(16) DEFAULT 'web' COMMENT '来源渠道：web 网站悬浮窗 | mp 公众号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at`),
  KEY `idx_user_time` (`username`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 问答日志（问题/回答/意图/模型/token/耗时）';
-- DROP TABLE IF EXISTS `announcement`;
CREATE TABLE `announcement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `content` varchar(500) NOT NULL COMMENT '公告内容（≤500 字）',
  `level` varchar(16) NOT NULL DEFAULT 'normal' COMMENT 'normal|important|maintenance',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否生效：1 生效 | 0 停用（同一时间最多一条生效）',
  `start_at` datetime DEFAULT NULL COMMENT '定时生效',
  `end_at` datetime DEFAULT NULL COMMENT '自动失效',
  `created_by` varchar(64) DEFAULT 'admin' COMMENT '发布人账号',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全站公告（顶部走马灯；含级别与定时上下线）';
-- DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '操作用户 ID（⚠️ 历史数据多为 NULL，关联用户请用 username）',
  `username` varchar(100) DEFAULT NULL COMMENT '操作用户账号（关联用户的唯一可靠字段）',
  `action` varchar(50) DEFAULT NULL COMMENT '操作描述',
  `operation_type` varchar(32) DEFAULT NULL COMMENT '操作类型：LOGIN / QUERY / EXPORT / PERM_CHANGE 等',
  `target_type` varchar(32) DEFAULT NULL COMMENT '操作对象类型（USER / PRICE / DEMAND 等）',
  `target_id` varchar(64) DEFAULT NULL COMMENT '操作对象标识',
  `before_value` text COMMENT '变更前值（JSON）',
  `after_value` text COMMENT '变更后值（JSON）',
  `result` tinyint DEFAULT '1' COMMENT '结果：1 成功 | 0 失败',
  `level` varchar(8) DEFAULT 'INFO' COMMENT '日志级别：INFO / WARN / ERROR',
  `request_uri` varchar(128) DEFAULT NULL COMMENT '请求路径',
  `detail` text COMMENT '详细信息 / 失败原因',
  `ip` varchar(50) DEFAULT NULL COMMENT '客户端 IP',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_type_time` (`operation_type`,`created_at`),
  KEY `idx_user_time` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志（登录、查价、导出、权限变更等）';
-- DROP TABLE IF EXISTS `chem_profile`;
CREATE TABLE `chem_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `commodity_id` int DEFAULT NULL COMMENT '关联 commodity.varieties_id（可空：非平台品种）',
  `zh_name` varchar(120) NOT NULL COMMENT '品种中文名（查询键）',
  `en_name` varchar(200) DEFAULT NULL COMMENT 'PubChem 英文名',
  `cas` varchar(40) DEFAULT NULL COMMENT 'CAS 号',
  `cid` int DEFAULT NULL COMMENT 'PubChem CID',
  `formula` varchar(80) DEFAULT NULL COMMENT '分子式',
  `is_polymer` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0=化合物 1=聚合物/以单体代表',
  `payload` longtext COMMENT '完整物性 JSON（含 zh 译文，结构同 /chem/lookup 的 data）',
  `payload_hash` char(32) DEFAULT NULL COMMENT 'payload 的 MD5，用于判断是否需要重抓',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1=有效 0=失效',
  `fetch_state` varchar(16) NOT NULL DEFAULT 'fresh' COMMENT 'fresh=最新 stale=过期 need_refetch=待重抓 fail=抓取失败',
  `fetched_at` datetime DEFAULT NULL COMMENT '最近一次成功抓取时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_zh_name` (`zh_name`),
  KEY `idx_commodity` (`commodity_id`),
  KEY `idx_state` (`fetch_state`,`fetched_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物性数据持久化缓存（PubChem 代理 + 中文翻译层结果）';
-- DROP TABLE IF EXISTS `commodity`;
CREATE TABLE `commodity` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `varieties_id` int NOT NULL COMMENT '商品编号',
  `name` varchar(64) NOT NULL COMMENT '商品名称',
  `category` varchar(64) DEFAULT NULL COMMENT '大类',
  `unit` varchar(32) DEFAULT NULL COMMENT '默认单位',
  `has_market` tinyint NOT NULL DEFAULT '0' COMMENT '是否有市场价格数据：1 有 | 0 无',
  `has_enterprise` tinyint NOT NULL DEFAULT '0' COMMENT '是否有企业价格数据：1 有 | 0 无',
  `has_intl` tinyint NOT NULL DEFAULT '0' COMMENT '是否有国际价格数据：1 有 | 0 无',
  `market_count` int NOT NULL DEFAULT '0' COMMENT '市场价格记录数（冗余统计）',
  `enterprise_count` int NOT NULL DEFAULT '0' COMMENT '企业价格记录数（冗余统计）',
  `intl_count` int NOT NULL DEFAULT '0' COMMENT '国际价格记录数（冗余统计）',
  `earliest_date` date DEFAULT NULL COMMENT '最早有报价的日期',
  `latest_date` date DEFAULT NULL COMMENT '最新有报价的日期',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1 启用 | 0 停用',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_varieties_id` (`varieties_id`),
  KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品类目主表';
-- DROP TABLE IF EXISTS `commodity_bak_4611_20260923`;
CREATE TABLE `commodity_bak_4611_20260923` (
  `id` int NOT NULL DEFAULT '0' COMMENT '自增主键',
  `varieties_id` int NOT NULL COMMENT '商品编号',
  `name` varchar(64) NOT NULL COMMENT '商品名称',
  `category` varchar(64) DEFAULT NULL COMMENT '大类',
  `unit` varchar(32) DEFAULT NULL COMMENT '默认单位',
  `has_market` tinyint NOT NULL DEFAULT '0' COMMENT '是否有市场价格数据：1 有 | 0 无',
  `has_enterprise` tinyint NOT NULL DEFAULT '0' COMMENT '是否有企业价格数据：1 有 | 0 无',
  `has_intl` tinyint NOT NULL DEFAULT '0' COMMENT '是否有国际价格数据：1 有 | 0 无',
  `market_count` int NOT NULL DEFAULT '0' COMMENT '市场价格记录数（冗余统计）',
  `enterprise_count` int NOT NULL DEFAULT '0' COMMENT '企业价格记录数（冗余统计）',
  `intl_count` int NOT NULL DEFAULT '0' COMMENT '国际价格记录数（冗余统计）',
  `earliest_date` date DEFAULT NULL COMMENT '最早有报价的日期',
  `latest_date` date DEFAULT NULL COMMENT '最新有报价的日期',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1 启用 | 0 停用',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `contact_view_log`;
CREATE TABLE `contact_view_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `viewer_id` bigint NOT NULL COMMENT '查看者用户 ID',
  `post_id` bigint NOT NULL COMMENT '被查看的供需信息 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '查看时间（每日限频按 CURDATE() 统计）',
  PRIMARY KEY (`id`),
  KEY `idx_viewer_time` (`viewer_id`,`created_at`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='联系方式查看留痕（用于每日查看条数限频）';
-- DROP TABLE IF EXISTS `data_permission`;
CREATE TABLE `data_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `varieties_id` int NOT NULL COMMENT '商品编号(varietiesId)',
  `varieties_name` varchar(64) DEFAULT NULL COMMENT '商品名(冗余)',
  `granted_by` bigint DEFAULT NULL COMMENT '授予人用户ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  `expire_date` date DEFAULT NULL COMMENT '权限到期日；为 NULL 表示永久有效（查询时已过滤过期）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_variety` (`user_id`,`varieties_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户数据权限(按商品)';
-- DROP TABLE IF EXISTS `data_permission_bak_20260914`;
CREATE TABLE `data_permission_bak_20260914` (
  `id` bigint unsigned NOT NULL DEFAULT '0' COMMENT '自增主键（备份快照）',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `varieties_id` int NOT NULL COMMENT '商品编号(varietiesId)',
  `varieties_name` varchar(64) DEFAULT NULL COMMENT '商品名(冗余)',
  `granted_by` bigint DEFAULT NULL COMMENT '授予人用户ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  `expire_date` date DEFAULT NULL COMMENT '权限到期日；为 NULL 表示永久有效（查询时已过滤过期）（备份快照）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史备份表：data_permission 在 2026-09-14 改动前的快照（仅留档，勿用于业务）';
-- DROP TABLE IF EXISTS `data_product`;
CREATE TABLE `data_product` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `product_code` varchar(64) NOT NULL COMMENT '产品编码',
  `name` varchar(128) NOT NULL COMMENT '产品名称',
  `description` varchar(512) DEFAULT NULL COMMENT '产品描述',
  `price` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '售价(元)',
  `duration_days` int NOT NULL DEFAULT '30' COMMENT '授权有效期(天)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1上架 0下架',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_code` (`product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据产品(交易预留)';
-- DROP TABLE IF EXISTS `demand_post`;
CREATE TABLE `demand_post` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type` enum('demand','supply') NOT NULL COMMENT 'demand=求购 supply=供应',
  `user_id` bigint NOT NULL COMMENT '发布者用户 ID',
  `title` varchar(120) NOT NULL COMMENT '标题',
  `varieties_id` int DEFAULT NULL COMMENT '关联 commodity.varieties_id',
  `varieties_name` varchar(100) DEFAULT NULL COMMENT '品种名（多个用「、」拼接）',
  `items` text COMMENT '多行供货明细 JSON 数组',
  `quantity` decimal(14,2) DEFAULT NULL COMMENT '数量（供求货明细为空时用此字段）',
  `unit` varchar(16) NOT NULL DEFAULT '吨' COMMENT '数量单位',
  `spec` varchar(128) DEFAULT NULL COMMENT '规格/牌号',
  `expect_price` decimal(14,2) DEFAULT NULL COMMENT '期望价（可空）',
  `delivery_date` date DEFAULT NULL COMMENT '期望交期',
  `region` varchar(64) DEFAULT NULL COMMENT '地区',
  `remark` varchar(1000) DEFAULT NULL COMMENT '详细说明',
  `contact_name` varchar(64) DEFAULT NULL COMMENT '联系人（脱敏展示，完整值需权限）',
  `contact_phone` varchar(32) DEFAULT NULL COMMENT '联系电话（脱敏展示，完整值需权限）',
  `contact_email` varchar(128) DEFAULT NULL COMMENT '联系邮箱（脱敏展示，完整值需权限）',
  `company_name` varchar(128) DEFAULT NULL COMMENT '供应商填（需求方可空）',
  `status` enum('pending','online','offline','rejected','expired') NOT NULL DEFAULT 'online' COMMENT '状态：pending 待审核 | online 展示中 | offline 已下架 | rejected 已驳回 | expired 已过期',
  `expire_at` datetime DEFAULT NULL COMMENT '有效期截止时间（发布后 30 天）',
  `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览次数',
  `contact_view_count` int NOT NULL DEFAULT '0' COMMENT '联系方式被查看次数',
  `audit_remark` varchar(500) DEFAULT NULL COMMENT '管理员审核备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `notify_sent` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_status_type` (`status`,`type`,`created_at`),
  KEY `idx_user` (`user_id`),
  KEY `idx_varieties` (`varieties_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供需广场信息（求购需求 / 供应货源）';
-- DROP TABLE IF EXISTS `demand_post_contact_bak_20260916`;
CREATE TABLE `demand_post_contact_bak_20260916` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键（备份快照）',
  `user_id` bigint NOT NULL COMMENT '发布者用户 ID（备份快照）',
  `contact_name` varchar(64) DEFAULT NULL COMMENT '联系人（脱敏展示，完整值需权限）（备份快照）',
  `backed_at` datetime NOT NULL COMMENT '备份快照字段'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史备份表：demand_post 联系方式字段在 2026-09-16 改动前的快照（仅留档，勿用于业务）';
-- DROP TABLE IF EXISTS `enterprise_point`;
CREATE TABLE `enterprise_point` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `point_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '报价点简称，对应 enterprise_price.market_name',
  `province` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '省简称',
  `province_full` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '省全称',
  `city` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '市（展示层级=省 时为空）',
  `disp_level` varchar(8) COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示层级：市/省/不展示',
  `confidence` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '高/中/待核',
  `src` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '判定来源',
  `company` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '代表企业全称',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point` (`point_name`),
  KEY `idx_prov` (`province`),
  KEY `idx_prov_city` (`province`,`city`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='标点地图 报价点省市映射';
-- DROP TABLE IF EXISTS `enterprise_point_stat`;
CREATE TABLE `enterprise_point_stat` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `province` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '省简称',
  `city` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '市；空串=仅到省',
  `varieties_id` int NOT NULL COMMENT '品种ID',
  `point_cnt` int NOT NULL DEFAULT '0' COMMENT '报价点数（去重）',
  `record_cnt` int NOT NULL DEFAULT '0' COMMENT '报价行数',
  `last_date` date DEFAULT NULL COMMENT '该组合最新报价日',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pvc` (`province`,`city`,`varieties_id`),
  KEY `idx_vid` (`varieties_id`),
  KEY `idx_prov` (`province`),
  KEY `idx_prov_city` (`province`,`city`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='标点地图 省市×品种 预计算汇总（cron 刷新）';
-- DROP TABLE IF EXISTS `enterprise_price`;
CREATE TABLE `enterprise_price` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `business_id` int NOT NULL COMMENT '数据供应商记录ID',
  `varieties_id` int NOT NULL COMMENT '商品编号(varietiesId)',
  `varieties_name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品名称',
  `market_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '市场',
  `specifications_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '规格',
  `price_type_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '价格条件(FOB/CFR等)',
  `unit_valuation_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '单位',
  `region_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '区域',
  `brand_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌/企业简称(brandName)',
  `data_date` date NOT NULL COMMENT '数据日期',
  `low_price` decimal(18,4) DEFAULT NULL COMMENT '最低价',
  `high_price` decimal(18,4) DEFAULT NULL COMMENT '最高价',
  `middle_price` decimal(18,4) DEFAULT NULL COMMENT '主流价',
  `rmb_price` decimal(18,4) DEFAULT NULL COMMENT '人民币价',
  `data_rise_or_fall` decimal(18,4) DEFAULT NULL COMMENT '涨跌值',
  `data_rate` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '涨跌幅',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `business_type` int NOT NULL COMMENT '价格类型: 2企业 3市场 4国际',
  `business_type_name` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '价格类型名',
  `two_level_business_type` int DEFAULT '0' COMMENT '二级价格类型',
  `uid` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商记录 uid',
  `fetched_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '抓取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_date` (`business_id`,`data_date`),
  KEY `idx_varieties` (`varieties_id`),
  KEY `idx_date` (`data_date`),
  KEY `idx_date_mid` (`data_date`,`middle_price`),
  KEY `idx_mid_mar` (`middle_price`,`market_name`),
  KEY `idx_vid_date` (`varieties_id`,`data_date`),
  KEY `idx_mar_vid_date` (`market_name`,`varieties_id`,`data_date`),
  KEY `idx_vid_date_mid` (`varieties_id`,`data_date`,`middle_price`),
  KEY `idx_vid_brand` (`varieties_id`,`brand_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业价格(dataVendor businessType=2)';
-- DROP TABLE IF EXISTS `enterprise_province`;
CREATE TABLE `enterprise_province` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `brand_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '企业/品牌名称（对应 enterprise_price.brand_name）',
  `province` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '省份（不含“省/市/自治区”后缀，如 山东）',
  `province_full` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '省份全称，如 山东省',
  `region_name` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '报价区（报价市场大区，非注册地）',
  `method` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '判定方式',
  `confidence` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '置信度：高/中',
  `records` int DEFAULT '0' COMMENT '报价记录数',
  `varieties_cnt` int DEFAULT '0' COMMENT '品种数',
  `last_date` date DEFAULT NULL COMMENT '最晚报价日期',
  `verified_at` datetime DEFAULT NULL COMMENT '核实时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_brand` (`brand_name`),
  KEY `idx_prov` (`province`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业注册省份映射（分布图数据源）';
-- DROP TABLE IF EXISTS `enterprise_variety_stat`;
CREATE TABLE `enterprise_variety_stat` (
  `varieties_id` int NOT NULL,
  `varieties_name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `brands` int NOT NULL DEFAULT '0' COMMENT '有企业报价的品牌数',
  `provinces` int NOT NULL DEFAULT '0' COMMENT '覆盖省份数',
  `records` bigint NOT NULL DEFAULT '0' COMMENT '报价记录数',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`varieties_id`),
  KEY `idx_brands` (`brands` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='产品分布：品种维度汇总（预计算，避免每次全表 JOIN）';
-- DROP TABLE IF EXISTS `fx_rate`;
CREATE TABLE `fx_rate` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `currency` varchar(8) NOT NULL COMMENT '币种代码: USD/EUR/GBP/JPY/HKD',
  `rate_date` date NOT NULL COMMENT '汇率日期',
  `rate_to_cny` decimal(12,6) NOT NULL COMMENT '1外币 = X 人民币 (中间价)',
  `source` varchar(64) DEFAULT '中国银行' COMMENT '数据来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_currency_date` (`currency`,`rate_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人民币汇率中间价历史表';
-- DROP TABLE IF EXISTS `international_price`;
CREATE TABLE `international_price` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `business_id` int NOT NULL COMMENT '数据供应商记录ID',
  `varieties_id` int NOT NULL COMMENT '商品编号(varietiesId)',
  `varieties_name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品名称',
  `market_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '市场',
  `specifications_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '规格',
  `price_type_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '价格条件(FOB/CFR等)',
  `unit_valuation_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '单位',
  `region_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '区域',
  `brand_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌/企业简称(brandName)',
  `data_date` date NOT NULL COMMENT '数据日期',
  `low_price` decimal(18,4) DEFAULT NULL COMMENT '最低价',
  `high_price` decimal(18,4) DEFAULT NULL COMMENT '最高价',
  `middle_price` decimal(18,4) DEFAULT NULL COMMENT '主流价',
  `rmb_price` decimal(18,4) DEFAULT NULL COMMENT '人民币价',
  `calc_rmb_price` decimal(18,4) DEFAULT NULL COMMENT '换算人民币价(自算)',
  `fx_used_currency` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '换算时使用的币种(USD/EUR)',
  `data_rise_or_fall` decimal(18,4) DEFAULT NULL COMMENT '涨跌值',
  `data_rate` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '涨跌幅',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `business_type` int NOT NULL COMMENT '价格类型: 2企业 3市场 4国际',
  `business_type_name` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '价格类型名',
  `two_level_business_type` int DEFAULT '0' COMMENT '二级价格类型',
  `uid` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商记录 uid',
  `fetched_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '抓取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_date` (`business_id`,`data_date`),
  KEY `idx_varieties` (`varieties_id`),
  KEY `idx_date` (`data_date`),
  KEY `idx_date_mid` (`data_date`,`middle_price`),
  KEY `idx_mid_mar` (`middle_price`,`market_name`),
  KEY `idx_vid_date` (`varieties_id`,`data_date`),
  KEY `idx_mar_vid_date` (`market_name`,`varieties_id`,`data_date`),
  KEY `idx_vid_date_mid` (`varieties_id`,`data_date`,`middle_price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='国际价格(dataVendor businessType=4)';
-- DROP TABLE IF EXISTS `mail_campaign_log`;
CREATE TABLE `mail_campaign_log` (
  `campaign` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `email` varchar(190) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ok',
  `detail` varchar(200) NOT NULL DEFAULT '',
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`campaign`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `market_price`;
CREATE TABLE `market_price` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `business_id` int NOT NULL COMMENT '数据供应商记录ID',
  `varieties_id` int NOT NULL COMMENT '商品编号(varietiesId)',
  `varieties_name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品名称',
  `market_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '市场',
  `specifications_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '规格',
  `price_type_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '价格条件(FOB/CFR等)',
  `unit_valuation_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '单位',
  `region_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '区域',
  `brand_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌/企业简称(brandName)',
  `data_date` date NOT NULL COMMENT '数据日期',
  `low_price` decimal(18,4) DEFAULT NULL COMMENT '最低价',
  `high_price` decimal(18,4) DEFAULT NULL COMMENT '最高价',
  `middle_price` decimal(18,4) DEFAULT NULL COMMENT '主流价',
  `rmb_price` decimal(18,4) DEFAULT NULL COMMENT '人民币价',
  `data_rise_or_fall` decimal(18,4) DEFAULT NULL COMMENT '涨跌值',
  `data_rate` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '涨跌幅',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `business_type` int NOT NULL COMMENT '价格类型: 2企业 3市场 4国际',
  `business_type_name` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '价格类型名',
  `two_level_business_type` int DEFAULT '0' COMMENT '二级价格类型',
  `uid` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商记录 uid',
  `fetched_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '抓取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_business_date` (`business_id`,`data_date`),
  KEY `idx_varieties` (`varieties_id`),
  KEY `idx_date` (`data_date`),
  KEY `idx_date_mid` (`data_date`,`middle_price`),
  KEY `idx_mid_mar` (`middle_price`,`market_name`),
  KEY `idx_vid_date` (`varieties_id`,`data_date`),
  KEY `idx_mar_vid_date` (`market_name`,`varieties_id`,`data_date`),
  KEY `idx_vid_date_mid` (`varieties_id`,`data_date`,`middle_price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='市场价格(dataVendor businessType=3)';
-- DROP TABLE IF EXISTS `open_api_daily`;
CREATE TABLE `open_api_daily` (
  `stat_date` date NOT NULL COMMENT '统计日期',
  `user_id` int NOT NULL COMMENT '用户ID，0=管理员手建的无归属Key',
  `calls` int NOT NULL DEFAULT '0' COMMENT '当日调用次数',
  `credits` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '当日消耗积分',
  PRIMARY KEY (`stat_date`,`user_id`),
  KEY `idx_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放API按天用量统计';
-- DROP TABLE IF EXISTS `open_api_key`;
CREATE TABLE `open_api_key` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '发给谁的备注',
  `key_hash` char(64) NOT NULL COMMENT 'SHA-256(明文key)',
  `key_prefix` varchar(12) NOT NULL COMMENT '明文前8位，便于辨认',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `daily_limit` int NOT NULL DEFAULT '1000',
  `calls_today` int NOT NULL DEFAULT '0',
  `call_date` date DEFAULT NULL,
  `total_calls` bigint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_called_at` datetime DEFAULT NULL,
  `user_id` int DEFAULT NULL COMMENT '归属用户（站内生成的key）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hash` (`key_hash`),
  KEY `idx_uid` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放数据API的Key';
-- DROP TABLE IF EXISTS `push_config`;
CREATE TABLE `push_config` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否开启邮件推送 0关 1开',
  `push_time` varchar(5) NOT NULL DEFAULT '17:30' COMMENT '每日发送时间档位 HH:MM',
  `push_quota` int NOT NULL DEFAULT '1' COMMENT '可推送产品额度（默认1，管理员可调高）',
  `last_test_sent_at` datetime DEFAULT NULL COMMENT '最后一次测试发送时间（用于限制测试频率）',
  `updated_at` datetime DEFAULT NULL COMMENT '最近更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户邮件推送配置';
-- DROP TABLE IF EXISTS `push_task`;
CREATE TABLE `push_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT 'sys_user.id',
  `name` varchar(50) NOT NULL DEFAULT '每日推送' COMMENT '任务名',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '总开关 0/1',
  `send_time` varchar(5) NOT NULL DEFAULT '17:30' COMMENT '每日发送 HH:MM',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '用户任务列表内的排序号（越小越前）',
  `last_sent_date` date DEFAULT NULL COMMENT '上次实际发送的 data_date 游标',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_task_user_name` (`user_id`,`name`),
  KEY `idx_task_enabled_time` (`enabled`,`send_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推送任务';
-- DROP TABLE IF EXISTS `push_task_item`;
CREATE TABLE `push_task_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint NOT NULL COMMENT 'push_task.id',
  `varieties_id` int NOT NULL COMMENT '品种 ID',
  `varieties_name` varchar(100) DEFAULT NULL COMMENT '品种名（冗余快照）',
  `market_name` varchar(100) DEFAULT NULL COMMENT '报价点；为空表示该品种全部报价点',
  `specifications_name` varchar(128) DEFAULT NULL COMMENT '空=NULL',
  `table_type` varchar(20) NOT NULL DEFAULT 'market' COMMENT '价格类型：market 市场价 | enterprise 企业价 | international 国际价',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_task_item` (`task_id`,`varieties_id`,`market_name`,`specifications_name`,`table_type`),
  KEY `idx_item_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推送任务项';
-- DROP TABLE IF EXISTS `qq_bot_group`;
CREATE TABLE `qq_bot_group` (
  `group_id` bigint NOT NULL,
  `group_name` varchar(128) NOT NULL DEFAULT '',
  `member_count` int NOT NULL DEFAULT '0',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `qq_broadcast_log`;
CREATE TABLE `qq_broadcast_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` varchar(40) NOT NULL DEFAULT '',
  `group_id` bigint NOT NULL DEFAULT '0',
  `group_name` varchar(128) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT '',
  `detail` varchar(255) NOT NULL DEFAULT '',
  `text_len` int NOT NULL DEFAULT '0',
  `text_body` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `qq_join_pending`;
CREATE TABLE `qq_join_pending` (
  `code` varchar(8) NOT NULL,
  `req_type` varchar(8) NOT NULL,
  `flag` varchar(512) NOT NULL,
  `group_id` bigint DEFAULT NULL,
  `group_name` varchar(128) DEFAULT NULL,
  `applicant` varchar(128) DEFAULT NULL,
  `comment` varchar(255) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `acted_at` datetime DEFAULT NULL,
  `notified_uins` varchar(255) DEFAULT NULL,
  `hit_keyword` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `qq_msg_seen`;
CREATE TABLE `qq_msg_seen` (
  `msg_key` varchar(64) NOT NULL,
  `seen_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`msg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `qq_notify_group`;
CREATE TABLE `qq_notify_group` (
  `group_id` bigint NOT NULL,
  `enabled` tinyint NOT NULL DEFAULT '1',
  `note` varchar(64) NOT NULL DEFAULT '',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `qq_notify_log`;
CREATE TABLE `qq_notify_log` (
  `post_id` bigint NOT NULL,
  `group_id` bigint NOT NULL,
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`,`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- DROP TABLE IF EXISTS `realname_order`;
CREATE TABLE `realname_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '下单用户 ID',
  `out_trade_no` varchar(64) NOT NULL COMMENT '商户订单号（唯一）',
  `amount` decimal(10,2) NOT NULL COMMENT '订单金额（元）',
  `status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT '订单状态：pending 待支付 | paid 已支付 | success 已完成 | failed 失败',
  `mode` varchar(20) NOT NULL DEFAULT 'mock' COMMENT '支付方式：mock 模拟支付 | 其他为真实通道',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方交易流水号',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `paid_at` datetime DEFAULT NULL COMMENT '支付完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `out_trade_no` (`out_trade_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实名认证订单（支付/核验流程）';
-- DROP TABLE IF EXISTS `realname_verify_log`;
CREATE TABLE `realname_verify_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `name` varchar(64) NOT NULL COMMENT '待核验姓名',
  `idcard_masked` varchar(32) NOT NULL COMMENT '身份证号（脱敏存储）',
  `matched` tinyint DEFAULT '0' COMMENT '核验结果：1 一致 | 0 不一致',
  `http_status` int DEFAULT '0' COMMENT '核验通道返回的 HTTP 状态码',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '核验时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实名二要素核验日志（姓名与身份证一致性核验记录）';
-- DROP TABLE IF EXISTS `site_message`;
CREATE TABLE `site_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '接收用户ID',
  `title` varchar(200) NOT NULL COMMENT '消息标题',
  `content` text COMMENT '消息内容',
  `type` varchar(50) DEFAULT 'system' COMMENT '消息类型',
  `is_read` tinyint DEFAULT '0' COMMENT '是否已读(0否1是)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_is_read` (`is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内消息表';
-- DROP TABLE IF EXISTS `site_setting`;
CREATE TABLE `site_setting` (
  `setting_key` varchar(64) NOT NULL COMMENT '配置键（如 supply_enabled / demand_notice_content）',
  `setting_value` text COMMENT '配置值',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `updated_by` bigint DEFAULT NULL COMMENT '最后修改人用户 ID',
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站点配置项（键值对，如供需模块开关、风险提示文案）';
-- DROP TABLE IF EXISTS `supplier_verify`;
CREATE TABLE `supplier_verify` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '申请人',
  `company_name` varchar(128) NOT NULL COMMENT '公司全称',
  `credit_code` varchar(32) NOT NULL COMMENT '统一社会信用代码',
  `license_url` varchar(255) DEFAULT NULL COMMENT '营业执照图片路径',
  `contact_name` varchar(64) DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(32) DEFAULT NULL COMMENT '联系电话',
  `status` enum('pending','approved','rejected','reverify','revoked') NOT NULL DEFAULT 'pending' COMMENT '认证状态：pending 待审核 | approved 已通过 | rejected 已驳回 | reverify 需重新认证 | revoked 已撤销',
  `remark` varchar(500) DEFAULT NULL COMMENT '审核备注/驳回原因',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户 ID',
  `valid_until` date DEFAULT NULL COMMENT '认证有效期',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `reg_status` varchar(32) DEFAULT NULL COMMENT '工商登记状态快照',
  `reg_checked_at` datetime DEFAULT NULL COMMENT '工商状态查询时间',
  `reg_payload` text COMMENT '工商接口完整返回（备用不展示）',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status_time` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商企业认证申请（营业执照 + 工商核验）';
-- DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `username` varchar(64) NOT NULL COMMENT '登录名',
  `password` varchar(128) NOT NULL COMMENT 'BCrypt 密码',
  `nickname` varchar(64) DEFAULT NULL COMMENT '昵称',
  `email` varchar(255) DEFAULT NULL COMMENT '注册邮箱（找回密码、邮件推送使用）',
  `phone` varchar(32) DEFAULT NULL COMMENT '手机号（发布供需时绑定）',
  `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT '角色: ADMIN/USER',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1启用 0禁用',
  `export_permission` tinyint(1) NOT NULL DEFAULT '0' COMMENT '导出权限：1 允许导出 Excel | 0 不允许',
  `login_fail_count` int NOT NULL DEFAULT '0' COMMENT '连续登录失败次数（达阈值锁定）',
  `lock_until` datetime DEFAULT NULL COMMENT '账号锁定截止时间；NULL 表示未锁定',
  `last_login_at` datetime DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(64) DEFAULT NULL COMMENT '最后登录IP',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `supplier_status` tinyint NOT NULL DEFAULT '0' COMMENT '0未认证 1审核中 2已认证 3已驳回',
  `supplier_level` tinyint NOT NULL DEFAULT '0' COMMENT '0无 1企业认证 2优质供应商',
  `supplier_company` varchar(128) DEFAULT NULL COMMENT '认证公司名',
  `realname_status` tinyint NOT NULL DEFAULT '0' COMMENT '实名状态：0 未实名 | 1 已实名',
  `realname_at` datetime DEFAULT NULL COMMENT '实名通过时间',
  `realname_name` varchar(30) DEFAULT NULL COMMENT '实名姓名（仅平台留存，不对外展示）',
  `realname_idcard` varchar(20) DEFAULT NULL COMMENT '身份证号（仅平台留存，不对外展示）',
  `realname_idcard_full` varchar(32) DEFAULT NULL COMMENT '身份证号（完整明文，2026-09-17 起留存；仅管理员在用户详情可查看）',
  `default_contact_name` varchar(64) DEFAULT NULL COMMENT '默认联系人（发布供需时自动带入）',
  `default_contact_phone` varchar(32) DEFAULT NULL COMMENT '默认联系电话（发布供需时自动带入）',
  `default_contact_email` varchar(255) DEFAULT NULL COMMENT '默认联系邮箱（发布供需时自动带入）',
  `is_test` tinyint NOT NULL DEFAULT '0' COMMENT '测试账号：1 是（用户看板等统计默认排除）| 0 否',
  `demand_notify_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `demand_notify_email` varchar(255) DEFAULT NULL,
  `demand_notify_at` datetime DEFAULT NULL,
  `open_api_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '开放API权限 1已开通',
  `open_api_daily_limit` int NOT NULL DEFAULT '1000' COMMENT '开放API默认日限额',
  `open_api_trial_start` datetime DEFAULT NULL COMMENT '开放API试用计时起点（首次建Key写入，永不重置）',
  `open_api_credits` decimal(10,2) NOT NULL DEFAULT '200.00' COMMENT '开放API剩余积分',
  `enterprise_map_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '企业分布图访问权限（管理员可单独开启）',
  `enterprise_map_expire_date` date DEFAULT NULL COMMENT '标点地图权限到期日；NULL=永久；到期日当天仍有效',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户';
-- DROP TABLE IF EXISTS `sys_user_defaultcontact_bak_20260916`;
CREATE TABLE `sys_user_defaultcontact_bak_20260916` (
  `id` bigint unsigned NOT NULL DEFAULT '0' COMMENT '自增主键（备份快照）',
  `username` varchar(64) NOT NULL COMMENT '登录名',
  `default_contact_name` varchar(64) DEFAULT NULL COMMENT '默认联系人（发布供需时自动带入）（备份快照）',
  `backed_at` datetime NOT NULL COMMENT '备份快照字段'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史备份表：sys_user 默认联系方式字段在 2026-09-16 改动前的快照（仅留档，勿用于业务）';
-- DROP TABLE IF EXISTS `trade_order`;
CREATE TABLE `trade_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `order_no` varchar(32) NOT NULL COMMENT '订单号',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `product_id` bigint NOT NULL COMMENT '产品ID',
  `amount` decimal(10,2) NOT NULL COMMENT '订单金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态: 0待支付 1已支付 2已取消 3退款',
  `paid_at` datetime DEFAULT NULL COMMENT '支付时间',
  `expire_at` datetime DEFAULT NULL COMMENT '授权到期时间',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易订单(预留)';
-- DROP TABLE IF EXISTS `user_favorite`;
CREATE TABLE `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `varieties_id` int NOT NULL COMMENT '品种 ID',
  `market_name` varchar(100) DEFAULT '' COMMENT '报价点',
  `specifications_name` varchar(128) DEFAULT NULL COMMENT '规格；NULL 表示该报价点不区分规格（⚠️ 唯一索引里 NULL != NULL，判重需用 <=>）',
  `table_type` varchar(20) DEFAULT 'market' COMMENT '价格类型：market 市场价 | enterprise 企业价 | international 国际价',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  `push_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否推送 0否 1是',
  `last_pushed_date` date DEFAULT NULL COMMENT '该关注项上次已推送的数据日期',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_user_fav` (`user_id`,`varieties_id`,`market_name`,`specifications_name`,`table_type`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关注关系（品种 + 报价点 + 规格 + 价格类型）';
-- DROP TABLE IF EXISTS `user_favorite_price`;
CREATE TABLE `user_favorite_price` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `varieties_id` int NOT NULL COMMENT '品种 ID',
  `varieties_name` varchar(100) DEFAULT '' COMMENT '品种名（冗余快照）',
  `market_name` varchar(100) DEFAULT '' COMMENT '报价点',
  `specifications_name` varchar(128) DEFAULT NULL COMMENT '规格；NULL 表示不区分规格',
  `table_type` varchar(20) DEFAULT 'market' COMMENT '价格类型：market 市场价 | enterprise 企业价 | international 国际价',
  `middle_price` decimal(12,4) DEFAULT NULL COMMENT '主流价（报价区间中点）',
  `high_price` decimal(12,4) DEFAULT NULL COMMENT '最高价',
  `low_price` decimal(12,4) DEFAULT NULL COMMENT '最低价',
  `data_rate` varchar(50) DEFAULT '' COMMENT '涨跌幅文本（数据源原始值，优先展示）',
  `data_rise_or_fall` decimal(8,4) DEFAULT NULL COMMENT '涨跌幅度（数值，用于着色与排序）',
  `unit_valuation_name` varchar(50) DEFAULT '' COMMENT '计价单位，如 元/吨',
  `data_date` varchar(20) DEFAULT '' COMMENT '该条报价所属交易日',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '快照写入时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_fav_price` (`user_id`,`varieties_id`,`market_name`,`specifications_name`,`table_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关注的报价快照（定时刷新，用于首页/推送展示）';
-- DROP TABLE IF EXISTS `user_product_apply`;
CREATE TABLE `user_product_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '申请用户ID',
  `varieties_id` int NOT NULL COMMENT '商品ID',
  `varieties_name` varchar(100) DEFAULT '' COMMENT '商品名称',
  `status` enum('pending','approved','rejected','cancelled') DEFAULT 'pending' COMMENT '状态',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `reviewed_by` bigint DEFAULT NULL COMMENT '审核人ID',
  `remark` varchar(500) DEFAULT '' COMMENT '备注',
  `valid_from` date DEFAULT NULL COMMENT '授权生效日期',
  `valid_until` date DEFAULT NULL COMMENT '授权到期日期',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_varieties_id` (`varieties_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户产品申请表';




SET FOREIGN_KEY_CHECKS = 1;
-- ============================================================
-- 文件结束（43 张表）
-- ============================================================
