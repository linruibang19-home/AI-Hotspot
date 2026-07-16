# 架构决策记录 001：Java 数据访问策略

> 状态：已接受
> 决策日期：2026-07-17
> 适用范围：Spring Boot Core API

## 1. 背景

Core API 同时存在聚合根 CRUD、复杂筛选、全文/向量检索、批处理、Transactional Outbox 和 `FOR UPDATE SKIP LOCKED` 等需求。全部交给单一 ORM 会弱化 SQL 可控性；同时引入 JPA 与 MyBatis 两套完整映射体系会增加 M1 的维护成本。

## 2. 决策

采用“Spring Data JPA + Spring JDBC”双层策略，不在 MVP 基线引入 MyBatis：

- Spring Data JPA：聚合根、关系清晰的事务写入、简单查询和常规后台 CRUD；
- `NamedParameterJdbcTemplate`：Outbox/Inbox、批量写入、队列领取、PostgreSQL FTS、pgvector、报表聚合和其他需要显式 SQL 的路径；
- Flyway 是唯一 Schema 变更入口；Hibernate 只做 `validate`，不得自动建表或改表；
- 复杂 SQL 必须有索引依据、参数绑定和集成测试，不在 Repository 中拼接用户输入；
- 同一业务事务可同时使用 JPA 与 JDBC，但必须共享 Spring 管理的 DataSource 和事务边界。

## 3. 未选择方案

- 全量 JPA：难以清晰表达队列领取、部分索引、FTS 和向量查询；
- 全量 MyBatis：会增加映射样板，降低简单聚合开发效率；
- JPA + MyBatis + JDBC 三套并存：MVP 阶段没有足够收益支撑复杂度。

## 4. 结果与约束

- NQ-002 关闭；
- M1 的 `OutboxStore` 使用 `NamedParameterJdbcTemplate` 验证该边界；
- M2 起新增 Repository 时必须先判断是“聚合访问”还是“SQL 密集路径”；
- 若后续确有 MyBatis 必要，需新增 ADR，说明无法由 JPA/JDBC 合理解决的证据。
