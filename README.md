# ChemPrice 化工价格平台

数据来源：ChemPrice 化工价格平台

## 目录结构

```
.
├── backend/          Spring Boot 3.5.6 (Java 21) 后端，端口 9000
├── frontend/         Vue 3.4 + Vite + Pinia + Element Plus 前端
├── docs/             开发文档（需求 / 提示词 / 接口清单 / 设计令牌）
└── README-deploy.md  部署说明
```

## 本地运行

⚠️ **凭据不在仓库内**。运行前需通过环境变量提供（见 `docs/` 说明）：

| 变量 | 用途 |
|---|---|
| `DB_PASSWORD` | MySQL 连接密码 |
| `MAIL_PASSWORD` | SMTP 邮件发送密码 |
| `JWT_SECRET` | JWT 签名密钥 |

**后端**：

```bash
cd backend
./mvnw package -DskipTests
java -jar target/data-market-0.0.1-SNAPSHOT.jar --server.port=9000
```

**前端**：

```bash
cd frontend
npm install
npm run build
```

## 约定

- ⛔ **凭据一律不落盘**：密码 / 密钥 / Token 只通过环境变量注入，绝不写入任何文件
- ⛔ **对外不出现数据供应商名称**，统一「数据来源：ChemPrice 化工价格平台」
- 每次新增/修改功能，同步更新 `docs/` 下的文档并发版
