package com.evops.quota.controller;

import com.evops.common.ApiResponse;
import com.evops.quota.dto.QuotaAccountAdjustRequest;
import com.evops.quota.dto.QuotaAccountCreateRequest;
import com.evops.quota.dto.QuotaActionRequest;
import com.evops.quota.dto.QuotaReserveRequest;
import com.evops.quota.dto.ReconcileReport;
import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaLedger;
import com.evops.quota.entity.QuotaReservation;
import com.evops.quota.service.QuotaService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店额度接口。写接口请求头携带 X-Operator-Id / X-Request-No / X-Biz-Time。
 * 三段式：reserve 预占（40 分钟有效）→ confirm 实扣 / release 手工释放 / 超时自动还。
 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 建户：门店额度账户（storeCode 唯一） */
    @PostMapping("/accounts")
    public ApiResponse<QuotaAccount> createAccount(@Valid @RequestBody QuotaAccountCreateRequest request) {
        return ApiResponse.ok(quotaService.createAccount(request));
    }

    /** 调整核定总额度（不得低于当前预占加已实扣） */
    @PutMapping("/accounts/{storeCode}")
    public ApiResponse<QuotaAccount> adjustTotal(@PathVariable String storeCode,
                                                 @Valid @RequestBody QuotaAccountAdjustRequest request) {
        return ApiResponse.ok(quotaService.adjustTotal(storeCode, request));
    }

    /** 查账户：含可用额度（总额-预占-实扣） */
    @GetMapping("/accounts/{storeCode}")
    public ApiResponse<Map<String, Object>> getAccount(@PathVariable String storeCode) {
        QuotaAccount account = quotaService.getAccount(storeCode);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("account", account);
        view.put("availableQuota",
                account.getTotalQuota().subtract(account.getReservedAmount()).subtract(account.getUsedAmount()));
        return ApiResponse.ok(view);
    }

    /** 预占：占用 40 分钟内有效，超时自动归还 */
    @PostMapping("/reserve")
    public ApiResponse<QuotaReservation> reserve(@Valid @RequestBody QuotaReserveRequest request) {
        return ApiResponse.ok(quotaService.reserve(request));
    }

    /** 实扣：以有效预占为前提 */
    @PostMapping("/confirm")
    public ApiResponse<QuotaReservation> confirm(@Valid @RequestBody QuotaActionRequest request) {
        return ApiResponse.ok(quotaService.confirm(request));
    }

    /** 手工释放：有效期内按释放结算；已过有效期按超时还结算 */
    @PostMapping("/release")
    public ApiResponse<QuotaReservation> release(@Valid @RequestBody QuotaActionRequest request) {
        return ApiResponse.ok(quotaService.release(request));
    }

    /** 查预占单 */
    @GetMapping("/reservations/{reservationNo}")
    public ApiResponse<QuotaReservation> getReservation(@PathVariable String reservationNo) {
        return ApiResponse.ok(quotaService.getReservation(reservationNo));
    }

    /** 查明细流水（只追加，可对账）：门店必填，业务日可空 */
    @GetMapping("/ledger")
    public ApiResponse<List<QuotaLedger>> listLedger(
            @RequestParam String storeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(quotaService.listLedger(storeCode, bizDate));
    }

    /** 日结对平：当日预占 = 实扣+释放+过期+期末未结；账户与全量流水对平 */
    @GetMapping("/reconcile")
    public ApiResponse<ReconcileReport> reconcile(
            @RequestParam String storeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(quotaService.reconcile(storeCode, bizDate));
    }
}
