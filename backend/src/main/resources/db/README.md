# 数据库脚本说明

本目录包含 ChemPrice 平台的**表结构**与**脱敏样例数据**，用于本地开发与部署。

## 文件

| 文件 | 内容 | 说明 |
|---|---|---|
| `schema.sql` | **43 张表**的建表语句 | 仅表结构，**不含任何数据** |
| `sample_data.sql` | 600 行样例数据 | ⛔ **全部虚构**，仅供演示 |

## 怎么用

### 1. 建库

```sql
CREATE DATABASE chemprice_local DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

### 2. 建表

```bash
mysql -u <user> -p chemprice_local < schema.sql
```

> ⚠️ `schema.sql` 里的 `DROP TABLE IF EXISTS` 已统一注释掉，避免误执行删表。
> 需要在空库重跑时，取消注释即可。

### 3. 导入样例数据（可选）

```bash
mysql -u <user> -p chemprice_local < sample_data.sql
```

导入后三张价格表各有 200 行数据，页面能正常显示。

---

## ⛔ 关于样例数据

`sample_data.sql` 中的**所有业务维度均为虚构占位符**：

| 字段 | 取值示例 | 说明 |
|---|---|---|
| 品种名 | `示例基础料A` | 虚构，非真实品种 |
| 市场名 | `示例市场一` | 虚构，非真实市场 |
| 地区名 | `示例东部区` | 虚构，非真实地区 |
| 企业/品牌名 | `示范企业甲` | 虚构，非真实企业 |
| 价格 | 800 ~ 12000 随机 | 虚构，不反映任何真实行情 |

生成方式：以真实表的**字段结构**为骨架，业务维度与数值全部合成，仅用于让项目跑通。

**⛔ 这些数据不得用于任何决策。**

---

## 与生产库的关系

- 生产库结构以线上为准，`schema.sql` 是**某一时点的快照**，可能滞后
- 修改表结构后，请同步更新本文件：

```bash
mysqldump -u <user> -p --no-data --skip-comments --set-gtid-purged=OFF \
  <dbname> > schema.sql
# 然后：注释掉 DROP TABLE 行、清理 AUTO_INCREMENT= 数字
```
