# 架构决策记录 001：Java 数据访问策略

> 状态：已接受
> 决策日期：2026-07-17
> 适用范围：Spring Boot Core API

## 1. 背景

Core API 同时存在聚合根 CRUD、复杂筛选、全文/向量检索、批处理、Transactional Outbox 和 `FOR UPDATE SKIP LOCKED` 等需求。全部交给单一 ORM 会弱化 SQL 可控性。M1 最初采用 JPA + JDBC；产品负责人于 2026-07-17 确认将 MyBatis 作为正式 SQL 映射框架，因此本记录修订并替代原决定。

## 2. 决策

采用“Spring Data JPA + MyBatis + 受限 Spring JDBC”分层策略：

- Spring Data JPA：聚合根、关系清晰的事务写入、简单查询和常规后台 CRUD；
- MyBatis：复杂列表、动态筛选、管理端查询、PostgreSQL FTS、pgvector、报表聚合、读模型和需要显式 SQL 的业务路径；
- `NamedParameterJdbcTemplate`：仅用于 Outbox/Inbox 领取、驱动级批处理、底层锁等基础设施原语，不再作为通用业务 Repository 的替代方案；
- Flyway 是唯一 Schema 变更入口；Hibernate 只做 `validate`，不得自动建表或改表；
- 复杂 SQL 必须有索引依据、参数绑定和集成测试，不在 Repository 中拼接用户输入；
- JPA、MyBatis 与 JDBC 共享 Spring 管理的 DataSource、连接池和事务边界；
- 单个 Repository 必须明确采用一种主要访问方式，不得为了方便在同一类中无边界混用三套 API。

## 3. 未选择方案

- 全量 JPA：难以清晰表达队列领取、部分索引、FTS 和向量查询；
- 全量 MyBatis：会增加映射样板，降低简单聚合开发效率；
- JPA + 通用 JDBC：复杂业务 SQL 缺少统一映射、复用和可读性约束；
- 全量三套自由混用：边界不清会增加事务、测试和维护成本，因此只允许按本记录规定分层使用。

## 4. 结果与约束

- NQ-002 关闭；
- M1 的 `OutboxStore` 保留 `NamedParameterJdbcTemplate`，归属基础设施原语；
- M2 起新增 Repository 必须先判断是“JPA 聚合访问”还是“MyBatis SQL 密集路径”；
- MyBatis Mapper 接口、XML 和集成测试按领域模块归档，禁止建立跨领域的万能 Mapper；
- MyBatis、JPA 和 JDBC 的事务集成必须由自动化测试覆盖。
