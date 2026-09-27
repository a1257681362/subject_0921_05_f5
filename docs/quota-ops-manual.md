# 门店额度三段式 · 验光师操作手册

> 适用功能：门店额度**预占-确认-释放**三段式管理。
> 规则一句话：开单先**预占**额度（**40 分钟内有效**，超时系统自动归还）；取镜结算凭预占单**实扣**；客户不要了手工**释放**；每天打烊前**日结对平**。
> 接口均需认证（`-u bootstrap:bootstrap`），写接口带头 `X-Operator-Id`（操作人工号）、`X-Request-No`（请求号，每换一次操作换一个，勿重复）、`X-Biz-Time`（可选，默认系统当前时间）。

## 〇、名词对照

| 名词 | 含义 |
| --- | --- |
| 预占（RESERVE） | 开单前先把额度"按住"，40 分钟内有效 |
| 实扣（CONFIRM） | 取镜结算，把预占的额度真正扣掉（必须有有效预占单） |
| 释放（RELEASE） | 客户不要了，手工把按住额度还回去 |
| 超时还（EXPIRE） | 超过 40 分钟没结算，系统自动归还，不用人管 |
| 日结对平 | 每日核对：当天预占 = 实扣+释放+超时还+还没结的，账户余额与流水一致 |

## 一、建户与调额（IT/门店负责人操作，验光师跳过）

```bash
# 建户：门店 S01 核定总额度 5000
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/accounts \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9001' -H 'X-Request-No: QUOTA-INIT-S01' \
  -d '{"storeCode":"S01","totalQuota":5000.00}'
# 预期：code=OK；重复建户返回 QUOTA_ACCOUNT_DUPLICATED

# 调额（新额度不得低于当前预占+已实扣，否则 QUOTA_TOTAL_TOO_LOW）
curl -s -u bootstrap:bootstrap -X PUT http://localhost:8080/api/quota/accounts/S01 \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9001' -H 'X-Request-No: QUOTA-ADJ-S01-001' \
  -d '{"totalQuota":6000.00}'
```

## 二、验光师日常四步

### 第 1 步：开单前预占

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/reserve \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: RSV-S01-0001' \
  -d '{"storeCode":"S01","amount":399.00,"bizRef":"O-20260927-0001"}'
```

- 记下返回里的 **`reservationNo`**（预占单号）和 **`expireTime`**（有效期至，40 分钟）。
- 额度不够返回 `QUOTA_NOT_ENOUGH`：找负责人调额或先释放不用的预占。
- 重复点了两下也不怕：同一 `X-Request-No` 只算一次（返回 `REQUEST_DUPLICATED`）。

### 第 2 步：查余额（可选）

```bash
curl -s -u bootstrap:bootstrap http://localhost:8080/api/quota/accounts/S01
# 看 availableQuota（可用额度）= 总额 - 预占中 - 已实扣
```

### 第 3 步：取镜结算 → 实扣

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/confirm \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: CFM-S01-0001' \
  -d '{"reservationNo":"<第1步的reservationNo>"}'
```

- 返回 `rsvStatus=CONFIRMED` 即成。
- 返回 `QUOTA_RESERVATION_EXPIRED`：超过 40 分钟预占已失效（系统会自动还），**回到第 1 步重新预占**再实扣。
- 返回 `QUOTA_RESERVATION_STATUS_ILLEGAL`：这单已经实扣/释放过了，别重复操作。

### 第 4 步：客户不要了 → 手工释放

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/release \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: REL-S01-0001' \
  -d '{"reservationNo":"<预占单号>","remark":"客户取消"}'
```

- 忘记释放也没关系：**过了 40 分钟系统自动还**（每分钟扫描一次），额度不会一直占着。

## 三、每日打烊：日结对平

```bash
# 1) 看当天流水（逐笔：预占/实扣/释放/超时还，含每笔后账户快照）
curl -s -u bootstrap:bootstrap \
  "http://localhost:8080/api/quota/ledger?storeCode=S01&bizDate=$(date +%F)"

# 2) 对平（不传 bizDate 默认今天）
curl -s -u bootstrap:bootstrap \
  "http://localhost:8080/api/quota/reconcile?storeCode=S01&bizDate=$(date +%F)"
```

看返回：

- `"balanced": true` —— 平账，收工。
- `"balanced": false` —— 看 `mismatches` 里写的不平项，连同当天流水（上一条命令）一起截图发给 IT。

对平口径（与系统一致，心里有数即可）：

```
当天预占合计 = 当天实扣 + 当天释放 + 当天超时还 + 期末还没结的
账户预占余额 = 全部未结预占单合计；账户实扣累计 = 全部实扣流水合计
```

## 四、常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `QUOTA_NOT_ENOUGH` | 可用额度不足：查是否有该释放的预占没释放，或找负责人调额 |
| `QUOTA_RESERVATION_EXPIRED` | 预占超 40 分钟已失效，重新预占再操作 |
| `QUOTA_RESERVATION_NOT_FOUND` | 预占单号抄错，回第 1 步返回里核对 |
| `REQUEST_DUPLICATED` | 请求号重复了：换一个新的 `X-Request-No` 再发；若是重复点击，前一次其实已成功 |
| 系统重启过 | 不用处理：停机期间过期的预占，启动时会自动补还 |

## 五、演练（变更窗口验证用，IT 配合）

不等 40 分钟也能验证"超时自动还"：预占时把业务时间传到 41 分钟前，该单立即过期，下一分钟扫描即自动归还：

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/reserve \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: RSV-DRILL-001' \
  -H "X-Biz-Time: $(date -d '41 minutes ago' +%Y-%m-%dT%H:%M:%S)" \
  -d '{"storeCode":"S01","amount":1.00}'
# 等约 1 分钟后查预占单，rsvStatus 应为 EXPIRED：
curl -s -u bootstrap:bootstrap http://localhost:8080/api/quota/reservations/<reservationNo>
```
