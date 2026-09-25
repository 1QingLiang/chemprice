# ChemPrice 数据看板系统 - 部署与运维文档

> 服务器: 82.156.8.214 (Ubuntu) | 更新时间: 2026-09-08

## 一、目录结构

```
~/chemprice/
├── app.jar                     # 后端可执行 jar（当前运行版本）
├── app.log                     # 后端运行日志
├── app.jar.bak-*               # 历史版本备份
└── source/                     # 完整源码（本目录）
    ├── backend/                # 后端源码 (Spring Boot 3.5 + Java 21)
    │   ├── pom.xml
    │   ├── mvnw / mvnw.cmd     # Maven Wrapper
    │   └── src/main/
    │       ├── java/com/datamarket/   # Java 源码
    │       └── resources/             # 配置 + MyBatis XML
    └── frontend/               # 前端源码 (Vue 3 + Vite)
        ├── package.json
        ├── vite.config.js
        └── src/                # Vue 组件/页面/路由/状态

生产部署位置（非源码）:
- 前端静态文件: /var/www/chemprice/  (nginx 托管)
- 后端服务: ~/chemprice/app.jar   (端口 9000)
```

## 二、技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.5.x + Spring Security(JWT) + MyBatis-Plus + EasyExcel |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + Vue Router + ECharts + GSAP |
| 数据库 | MySQL 82.156.8.214:3306 / longzhong 库 |

## 三、数据库业务表（longzhong 库）

- `sys_user` 用户表（role: ADMIN/USER，status 逻辑删除）
- `data_permission` 用户-商品授权（expire_date 到期日）
- `user_product_apply` 用户产品申请表（status: pending/approved/rejected/cancelled, valid_from/valid_until 生效日期）
- `site_message` 站内消息
- `audit_log` 操作日志
- `data_product` / `trade_order` 交易模块（预留）
- `commodity` + `market_price`/`enterprise_price`/`international_price` 商品与价格数据（约154万行）

## 四、后端本地构建

```bash
cd ~/chemprice/source/backend

# 需要 JDK 21 + Maven（或使用 mvnw）
./mvnw package -DskipTests
# 产物: target/data-market-0.0.1-SNAPSHOT.jar
```

## 五、后端部署与启动

```bash
# 1. 停止旧进程
pkill -9 -f app.jar

# 2. 覆盖 jar（构建出的 jar → ~/chemprice/app.jar）
cp target/data-market-0.0.1-SNAPSHOT.jar ~/chemprice/app.jar

# 3. 启动（端口 9000，日志到 app.log）
cd ~/chemprice
nohup java -Xmx512m -Xms256m -jar app.jar --server.port=9000 > app.log 2>&1 &

# 4. 验证
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:9000/api/commodities
# 预期 401/403（需认证）即后端存活；或 tail app.log 看启动日志
```

## 六、前端构建与部署

```bash
cd ~/chemprice/source/frontend
npm install        # 首次
npm run build      # 产物 dist/

# 部署到 nginx 托管目录
sudo rm -rf /var/www/chemprice/assets
sudo mkdir -p /var/www/chemprice/assets
sudo chmod 777 /var/www/chemprice /var/www/chemprice/assets
sudo cp -r dist/* /var/www/chemprice/
sudo chown -R www-data:www-data /var/www/chemprice
sudo chmod -R 755 /var/www/chemprice
```

访问: `http://82.156.8.214/`（nginx 80 端口反代前端，/api 转发 9000）

## 七、管理员账号

- 初始 admin / admin123（首次启动自动创建，**生产已修改密码**）

## 八、核心功能

1. **看板/数据查询**：ADMIN 看全部；USER 只看 `data_permission` 授权的商品
2. **产品申请流程**（v2）：
   - 用户端「申请产品」页：选商品 → 提交（待审核商品变灰不可选）
   - 管理员端「审核申请」页：按用户分组 → 勾选 → 设生效日期(valid_from/valid_until) → 通过/拒绝
   - 通过后自动写入 `data_permission` + 发送 `site_message` 站内消息
3. **操作日志**：audit_log 记录关键操作（登录/授权/审核/导出）
4. **移动端**：≤768px 汉堡抽屉侧栏 + 卡片化布局

## 九、常见问题排查

| 现象 | 排查 |
|------|------|
| 页面 500 / 白屏 | `tail -50 ~/chemprice/app.log` 看报错 |
| 用户看不了数据 | 查 data_permission 是否有记录且未过期 |
| 申请表查不到 | 确认 user_product_apply 表含 valid_from/valid_until 字段 |
| MyBatis 逻辑删除 | 全局配置了 status 逻辑删除；表如有自己的 status 业务字段需 excludeProperty |

## 十、数据库连接

```
host: 127.0.0.1 (服务器内网)
user: cardplaza
db:   longzhong
```
> 连接串/密码见 `backend/src/main/resources/application.yml`（勿外传）
