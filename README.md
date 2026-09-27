# EVOPS 基础工作区 —— 连锁眼镜店验光与加工底账

Spring Boot 骨架项目。技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

## 启动

1. `mvn -q -DskipTests compile` 验证构建。
2. `mvn spring-boot:run` 启动；Spring Boot 自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。
4. `mvn test` 运行集成测试（内存 H2）。

`docs/schema/evops.sql` 是交付副本，与 `src/main/resources/schema.sql` 保持一致。

## 统一约定

- 响应结构：`{ success, code, message, data }`；成功 `code=OK`，失败返回明确业务码（见 `BizCode`）。
- 全库**禁止物理删除**：镜片作废/恢复、加工单冲销/恢复均以状态位体现；处方编辑时旧行置 `VOID` 保留，另插 `ACTIVE` 新行；全部操作写只追加日志。
- 每次写入留存：操作人 `X-Operator-Id`、请求号 `X-Request-No`、业务时间 `X-Biz-Time`（请求头传入，自动落库）、版本号 `version_no`（乐观锁）。
- 重复提交由库层唯一约束兜底：`t_process_order.uk_order_request(request_no)` → `REQUEST_DUPLICATED`；单号/编码各有唯一约束。
- 关联表（加工单主表 + 左右眼处方）同一事务，要么全部生效，要么全部不见。

## 业务表

| 表 | 说明 |
| --- | --- |
| `t_lens` | 镜片基础信息（ACTIVE/DISCARDED） |
| `t_lens_oper_log` | 镜片 CREATE/UPDATE/DISCARD/RESTORE 记录 |
| `t_process_order` | 加工单主表 |
| `t_process_order_rx` | 左右眼处方（ACTIVE/VOID，每单每眼仅一条有效） |
| `t_process_order_status_log` | 取镜状态流转记录（只追加） |
| `t_sensitive_scan_log` | 加工单文本敏感词扫描留痕（只追加，PASS/MASK/BLOCK/RECHECK） |
| `t_batch_job` | 批量后台任务主表（RECHECK 等；进度/取消/重跑；运维数据，终态 21 天后物理清理） |
| `t_batch_job_item` | 批量任务明细（每张加工单一行，断点重跑依据；随任务 21 天清理） |
| `t_quota_account` | 门店额度账户（核定总额/预占中/已实扣，按门店唯一） |
| `t_quota_reservation` | 额度预占单（RESERVED/CONFIRMED/RELEASED/EXPIRED，40 分钟有效） |
| `t_quota_ledger` | 额度明细流水（只追加，每笔动作后账户快照，按 biz_date 对账） |

## 加工单状态

```
CREATED 已建档 ──start──▶ PROCESSING 加工中 ──finish──▶ READY 待取镜 ──pickup──▶ PICKED_UP 已取镜（终态）
     │                                                                                      
     └──────cancel──▶ CANCELLED 已冲销 ──restore──▶ CREATED
   （CREATED/PROCESSING/READY 均可 cancel；PICKED_UP 不可冲销）
```

处方度数（球镜必填，柱镜、下加光选填）按 0.25D（二十五度）一档校验，轴位 1~180，瞳距 20~90。

## 加工单文本敏感词管控

加工单的文本入口（`customerName`、`remark`）在建档/修改写路径上统一过 DFA 敏感词扫描：

- **先归一再匹配**：全角转半角、字母小写、分隔符（空白与常见干扰标点）剔除——"违-禁"、"违　禁"、"违*禁" 都能命中"违禁"；词库词条入库前同样归一。
- **分级处置**：词库词条按 `词语|级别` 配置，`BLOCK` 拦截（整单拒绝，`SENSITIVE_WORD_BLOCKED`）、`MASK` 打码（命中区间原文整段替换为 `*` 后落库）；缺省 MASK。
- **命中位置返回前端**：`/api/sensitive/scan` 返回原文坐标 `[start, end)`（end 开区间），前端提交前据此标记。
- **词库热更新**：`evops.sensitive.dict-path` 指向 `file:` 外部文件时，文件变更自动生效（扫描前懒检查 mtime）；任何路径都可调 `/api/sensitive/words/reload` 立即重载。词库版本为内容 MD5 短串，随每次扫描留痕。
- **扫描留痕**：每次扫描按加工单按字段写 `t_sensitive_scan_log`（含无命中的 PASS）；BLOCK 拦截尝试独立事务留痕，加工单回滚不丢。
- **反向核对**：`/api/sensitive/recheck` 以 `t_process_order` 为准，用当前词库重扫全量加工单，与最新写路径留痕比对，输出 `neverScanned`（无留痕，如历史直插数据）与 `changed`（处置加重：现词库需要打码/拦截但留痕时不需要——多为词库变严或文本绕开守门被改），并写 RECHECK 快照留痕。给别组的扫描结果数据以此接口校验为准。

## 加工单批量处理后台任务

全量反向核对等批量加工单处理走后台任务，**不占请求线程**（同类型任务同时只允许一个）：

- **提交即返回任务号**：`POST /api/batch/jobs`，body `{"jobType":"RECHECK","storeCode":"S01"}`（storeCode 可空=全量）；后台单线程串行执行，单据逐张独立事务（单张失败不影响其他单）。
- **凭任务号查进度**：`GET /api/batch/jobs/{jobNo}`——`progressPct` 恒为 **5 的整数倍（0~100）**，另有 total/processed/success/failed 计数；终态 `result` 为核对报告（结构同 recheck，另含 `failedOrders` 失败单及原因）。
- **可取消**：`POST /api/batch/jobs/{jobNo}/cancel` 为协作式取消（单据边界生效，当前单处理完即停，已处理结果保留）。
- **可断点重跑**：`POST /api/batch/jobs/{jobNo}/rerun` 仅 FAILED/CANCELLED 可重跑；每张加工单的结果落 `t_batch_job_item`（唯一约束兜底），已 SUCCESS 的单**跳过不重扫**（不重复写 RECHECK 留痕），只补未完成/失败的单。
- **重启恢复**：重启时 RUNNING 任务置 FAILED（明细保留可重跑），遗留 PENDING 自动重新派发。
- **保留 21 天**：终态任务 `expire_time = 完成时间 + 21 天`，每日 03:17 定时物理清理任务及明细（`evops.batch.retain-days` / `evops.batch.cleanup-cron` 可调）。这是全库"禁止物理删除"的唯一例外——任务记录是运维临时数据，业务留痕仍只追加。
- 同步接口 `POST /api/sensitive/recheck` 保留：应急/小批量及别组既有取数方式不受影响。
- 周末变更窗口实施前，向验光师发放检查单：`docs/batch-job-weekend-checklist.md`。

## 门店额度三段式（预占-确认-释放）

连锁额度按门店建账户，开单先预占、结算再实扣、取消可释放：

```
RESERVED 占用中 ──confirm──▶ CONFIRMED 已实扣（终态，账户预占→实扣）
      ├──release──▶ RELEASED 已释放（终态，手工触发，额度还回可用）
      └──expire───▶ EXPIRED 超时已还（终态，超过 40 分钟定时任务自动归还；启动补扫停机期间过期单）
```

- **40 分钟有效期**：`expire_time = 预占业务时间 + evops.quota.hold-minutes`（默认 40）；实扣必须在有效期内，过期单只能重新预占。
- **实扣以预占为前提**：无预占单/已结算/已过期一律拒绝（`QUOTA_RESERVATION_NOT_FOUND/STATUS_ILLEGAL/EXPIRED`）。
- **并发不超占**：预占扣减走带守卫条件的 UPDATE（`total - reserved - used >= 预占额`），不足返回 `QUOTA_NOT_ENOUGH`；账户、预占单、流水同一事务；重复提交由 `uk_quota_rsv_request(request_no)` 兜底。
- **明细流水可对账**：`t_quota_ledger` 只追加，每个动作一行（RESERVE/CONFIRM/RELEASE/EXPIRE），带业务日 `biz_date` 与动作后账户快照（`reserved_after/used_after`）。
- **日结对平**：`GET /api/quota/reconcile` 校验①当日预占合计 = 实扣+释放+过期+期末未结；②账户预占余额 = 全量未结预占单；③账户实扣累计 = 全量实扣流水；任一不等 `balanced=false` 并列出 `mismatches`。
- 验光师操作手册：`docs/quota-ops-manual.md`（建户、日常四步、日结、演练方法，每条命令可照抄）。

## 日志链路与聚合检索（总部质控要求）

全链路基于 SLF4J MDC，每行日志统一格式：

```
2026-09-27 10:00:00.123 INFO  [http-nio-8080-exec-1] [a1b2c3...32hex] [O-20260927-0001] c.e.o.s.ProcessOrderService - 加工单建档 ...
```

- **TraceId 贯穿**：请求头 `X-Trace-Id` 可传入串联上游（合法字符 `[0-9A-Za-z-]` 且 ≤64 位，否则重新生成，防 CRLF 注入与日志伪造）；缺省由服务端生成 32 位 hex。**响应头同名回带**，调用方据此定位本次请求的全部日志。
- **异步与定时任务同样传递**：批量后台线程池经 `TraceTaskDecorator` 继承提交请求的 traceId（与请求日志同一串）；`@Scheduled` 定时任务、启动恢复流程无上游请求，每次执行自动生成新 traceId。
- **统一带加工单号**：`[orderNo]` 列记录当前正在处理的加工单（无单号时占位 `-`）；批量任务逐单处理期间同样带单号，跨请求、跨线程都能按单号聚成一串。
- **一键聚合检索**：`sh scripts/order-log.sh <加工单号> [日志目录，默认 logs]`——当前日志与滚动历史（含 .gz）一把抓，按时间排序输出。单号按固定字符串匹配（`grep -F`），单号含 `.` `*` 等字符也不会被当成正则。
- **敏感字段打码**：客户姓名（仅留首字）、电话（前 3 后 4）等 PII 进日志前经 `LogMasks` 打码；自由文本先单行化（换行转空格），保证一条日志一行、聚合检索不被折行打断。底账原文仍在库内按权限可查。
- 日志文件默认 `logs/evops.log`（按天 + 50MB 滚动，保留 30 天），可用 `logging.file.name` 覆盖；格式定义在 `src/main/resources/logback-spring.xml`。

## 接口（写接口均带三个审计请求头）

镜片：

- `POST /api/lens` 建档
- `PUT  /api/lens` 修改（body 带 `versionNo` 乐观锁）
- `GET  /api/lens/{id}`、`GET /api/lens?status=&keyword=&current=&size=`
- `POST /api/lens/{id}/discard` 作废、`POST /api/lens/{id}/restore` 恢复

加工单：

- `POST /api/orders` 建档（主表 + 左右眼处方同事务）
- `PUT  /api/orders` 修改（仅 CREATED，处方整组原子替换）
- `GET  /api/orders/by-no/{orderNo}` 按加工单号检索（主单 + 有效处方 + 状态流转记录）
- `GET  /api/orders/{id}`、`GET /api/orders?status=&storeCode=`
- `POST /api/orders/{id}/start | /finish | /pickup | /cancel | /restore`

敏感词管控：

- `POST /api/sensitive/scan` 试扫：`{"text":"..."}` → 命中位置（原文坐标）、打码结果、是否拦截、词库版本
- `POST /api/sensitive/words/reload` 立即重载词库；`GET /api/sensitive/words/info` 当前词库状态
- `POST /api/sensitive/recheck` 反向核对（body 可传 `{"storeCode":"S01"}` 收窄范围）

批量后台任务（加工单批量处理）：

- `POST /api/batch/jobs` 提交任务（`{"jobType":"RECHECK","storeCode":?}`，立即返回任务号）
- `GET  /api/batch/jobs/{jobNo}` 查进度（5% 粒度）与终态报告；`GET /api/batch/jobs?status=&jobType=` 列表
- `POST /api/batch/jobs/{jobNo}/cancel` 取消；`POST /api/batch/jobs/{jobNo}/rerun` 断点重跑

门店额度（预占-确认-释放三段式，操作手册 `docs/quota-ops-manual.md`）：

- `POST /api/quota/accounts` 建户（`{"storeCode","totalQuota"}`）；`PUT /api/quota/accounts/{storeCode}` 调总额度（不得低于预占+实扣）
- `GET  /api/quota/accounts/{storeCode}` 账户与可用额度（总额-预占-实扣）
- `POST /api/quota/reserve` 预占（`{"storeCode","amount","bizRef"?}`，**40 分钟内有效**，返回 reservationNo/expireTime）
- `POST /api/quota/confirm` 实扣（**以有效预占为前提**：`{"reservationNo"}`，过期返回 QUOTA_RESERVATION_EXPIRED）
- `POST /api/quota/release` 手工释放（过期单按超时还结算）；超时预占每分钟由定时任务自动归还（启动补扫）
- `GET  /api/quota/reservations/{reservationNo}` 预占单
- `GET  /api/quota/ledger?storeCode=&bizDate=` 明细流水（只追加）；`GET /api/quota/reconcile?storeCode=&bizDate=` 日结对平（balanced）

示例（接口走 Shiro 认证，测试/本地可用 bootstrap/bootstrap）：

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/lens \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: LENS-0001' \
  -d '{"lensCode":"L-001","lensName":"1.60非球面","price":399.00}'
```
