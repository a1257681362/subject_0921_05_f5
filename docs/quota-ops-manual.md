# 门店额度三段式 · 验光师操作手册

> 适用功能：门店额度 **预占-确认-释放** 三段式管控。
> 一句话：**开单先占额度（40 分钟有效），成交确认实扣，反悔手工释放，忘了也没事——超时系统自动还。**
> 接口均需认证（`-u bootstrap:bootstrap`），写接口带头 `X-Operator-Id`（操作人工号）、`X-Request-No`（请求号，每次换一个、勿重复）、`X-Biz-Time`（可选）。
> 本手册命令以门店 **S01** 为例，照抄即可跑通；`<holdNo>` 等处替换为实际返回的值。

## 〇、准备（每次开店第一次用查一下）

- [ ] 应用已启动：`curl -s http://localhost:8080/api/health` 返回正常
- [ ] 知道自己门店编码（如 `S01`）和自己的操作人工号（如 `1001`）

## 一、开户与调额（每门店只开户一次，一般由 IT/店长操作）

- [ ] **开户**（总额度示例 10000 元）：

  ```bash
  curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/accounts \
    -H 'Content-Type: application/json' \
    -H 'X-Operator-Id: 9001' -H 'X-Request-No: QUOTA-OPEN-S01' \
    -d '{"storeCode":"S01","totalQuota":10000.00,"remark":"S01 门店额度"}'
  ```

  返回 `code=OK`，记下 `data.id`（账户号，调额要用）。重复开户会返回 `QUOTA_ACCOUNT_DUPLICATED`，说明已开过户，直接查账户即可。
- [ ] **查账户**（看总额度、占用中、已实扣、可用额度）：

  ```bash
  curl -s -u bootstrap:bootstrap 'http://localhost:8080/api/quota/accounts?storeCode=S01'
  ```

  关注字段：`totalQuota`（总额度）、`reservedAmount`（占用中）、`usedAmount`（已实扣）、`availableAmount`（可用）。
- [ ] **调额**（旺季上调/淡季下调；不能调到低于"占用中+已实扣"，否则报 `QUOTA_ADJUST_BELOW_OCCUPIED`）：

  ```bash
  curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/accounts/<账户id>/adjust \
    -H 'Content-Type: application/json' \
    -H 'X-Operator-Id: 9001' -H 'X-Request-No: QUOTA-ADJ-S01-001' \
    -d '{"totalQuota":15000.00,"remark":"国庆旺季上调"}'
  ```

## 二、日常三步走（验光师每单操作）

### 第 1 步：开单前预占额度

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/holds \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: QUOTA-HOLD-0001' \
  -d '{"storeCode":"S01","amount":399.00,"bizType":"PROCESS_ORDER","bizNo":"O-20260927-0001","remark":"配镜预占"}'
```

- 返回 `code=OK`：记下 `data.holdNo`（预占单号，后面确认/释放都靠它）和 `data.expireTime`（占用截止时间）。
- `bizType`/`bizNo` 可空；填了加工单号后，这笔记账的日志能和加工单日志按单号串起来查。
- 返回 `QUOTA_INSUFFICIENT`：**可用额度不足**——先查账户（第一步），有挂了太久没用的预占就释放掉，或找店长调额。

### 第 2 步（成交）：确认实扣

顾客付款成交后，**按预占单号等额实扣**：

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/holds/<holdNo>/confirm \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: QUOTA-CONF-0001' \
  -d '{"remark":"收银确认"}'
```

- 返回 `code=OK`、`holdStatus=CONFIRMED`：占用转为实扣，这笔额度正式记到门店头上。
- **实扣必须有预占**：没有预占单号直接确认，返回 `QUOTA_HOLD_NOT_FOUND`；拿已实扣/已释放/已过期的单子确认，返回 `QUOTA_HOLD_STATUS_ILLEGAL`。

### 第 2 步（反悔）：手工释放

顾客不要了、或预占金额录错了，**手工释放**，额度立刻还回可用：

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/holds/<holdNo>/release \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: QUOTA-REL-0001' \
  -d '{"remark":"客户改主意"}'
```

返回 `code=OK`、`holdStatus=RELEASED` 即可。

### 忘了释放怎么办：超时自动还

预占 **40 分钟**内不确认也不释放，系统每分钟扫描一次，自动把到期预占置为 `EXPIRED` 并把额度还回可用——**不需要任何人工操作**。想确认某笔预占的状态：

```bash
curl -s -u bootstrap:bootstrap http://localhost:8080/api/quota/holds/<holdNo>
```

`holdStatus` 为 `EXPIRED` 就是系统已自动归还；`releaseReason` 会写"超时自动归还"。

## 三、查流水对账（每笔变动都可查）

门店当天所有额度变动（预占/实扣/释放/超时归还/调额）都在流水里，只增不改：

```bash
# 按门店查
curl -s -u bootstrap:bootstrap 'http://localhost:8080/api/quota/flows?storeCode=S01'
# 按预占单号查（看某一笔的完整生命周期）
curl -s -u bootstrap:bootstrap 'http://localhost:8080/api/quota/flows?holdNo=<holdNo>'
# 只看实扣
curl -s -u bootstrap:bootstrap 'http://localhost:8080/api/quota/flows?storeCode=S01&flowType=CONFIRM'
```

每条流水含：`flowType`（RESERVE 预占 / CONFIRM 实扣 / RELEASE 手工释放 / EXPIRE 超时归还 / ADJUST 调额）、`amount`（金额）、`totalAfter`/`reservedAfter`/`usedAfter`（变动后三项余额）、操作人、请求号、业务时间。

## 四、每日对平（系统自动 + 可手工）

- **自动**：每天凌晨 03:47 系统逐门店核对——占用中的钱=未结预占单合计、已实扣=实扣流水合计、每笔实扣都有预占来源、没有超时未还的预占。结果落库，**不用人工操作**。
- **看结果**：

  ```bash
  curl -s -u bootstrap:bootstrap 'http://localhost:8080/api/quota/recon?storeCode=S01'
  ```

  `reconResult=BALANCED` 即对平；`MISMATCH` 时把 `detailJson`（差异明细）截图报 IT。
- **手工触发**（应急/验收，随时可跑，当天重跑会覆盖当天报告）：

  ```bash
  curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/quota/recon/run \
    -H 'Content-Type: application/json' \
    -H 'X-Operator-Id: 9001' -H 'X-Request-No: QUOTA-RECON-001' \
    -d '{"storeCode":"S01"}'
  ```

## 五、异常码对照表（看到 code 这样处理）

| code | 含义 | 怎么办 |
| --- | --- | --- |
| `QUOTA_ACCOUNT_NOT_FOUND` | 门店未开户 | 找 IT/店长先开户（第一步） |
| `QUOTA_ACCOUNT_DUPLICATED` | 门店已开户 | 不用重复开户，直接查账户 |
| `QUOTA_INSUFFICIENT` | 可用额度不足 | 查账户看占用；释放不用的预占，或找店长调额 |
| `QUOTA_ADJUST_BELOW_OCCUPIED` | 调额低于"占用+实扣" | 把总额度调到 ≥ 占用中+已实扣 |
| `QUOTA_HOLD_NOT_FOUND` | 预占单号不存在 | 检查单号是否抄错；实扣必须先有预占 |
| `QUOTA_HOLD_STATUS_ILLEGAL` | 预占状态不允许该操作 | 查该预占状态：已实扣/已释放/已超时都不能再操作，需要就重新预占一笔 |
| `REQUEST_DUPLICATED` | 请求号重复 | `X-Request-No` 每次请求换一个；若是网络重试，说明上一次已成功，查一下再决定 |
| `VALIDATION_FAILED` | 参数不对 | 按返回 message 改（金额必须为正、门店编码必填等） |

## 六、验收走查清单（交付后照此跑一遍）

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 开户 S01 总额度 10000 | `OK`，`availableAmount=10000` |
| 2 | 预占 399（挂加工单号） | `OK`，得 `holdNo`，可用变 9601 |
| 3 | 确认实扣该预占 | `OK`，`usedAmount=399`，占用归零 |
| 4 | 再预占 200 后手工释放 | `OK`，可用回到 9801 |
| 5 | 无单号直接 confirm | `QUOTA_HOLD_NOT_FOUND` |
| 6 | 对已释放单子再 confirm | `QUOTA_HOLD_STATUS_ILLEGAL` |
| 7 | 预占超过可用额度 | `QUOTA_INSUFFICIENT` |
| 8 | 预占一笔不操作，等 40 分钟（或验收时用启动参数 `--evops.quota.hold-ttl-minutes=1` 缩短） | 自动变 `EXPIRED`，额度还回 |
| 9 | 查流水 `GET /api/quota/flows?storeCode=S01` | 每笔变动一行，余额快照连续 |
| 10 | 手工对平 `POST /api/quota/recon/run` | 全部 `BALANCED` |

当班验光师签字：____________　门店负责人签字：____________　IT 签字：____________
