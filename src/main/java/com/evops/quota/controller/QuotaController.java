package com.evops.quota.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.ApiResponse;
import com.evops.quota.dto.QuotaAccountSaveRequest;
import com.evops.quota.dto.QuotaAdjustRequest;
import com.evops.quota.dto.QuotaHoldActionRequest;
import com.evops.quota.dto.QuotaHoldCreateRequest;
import com.evops.quota.dto.QuotaReconRunRequest;
import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaFlow;
import com.evops.quota.entity.QuotaHold;
import com.evops.quota.entity.QuotaRecon;
import com.evops.quota.service.QuotaAccountService;
import com.evops.quota.service.QuotaHoldService;
import com.evops.quota.service.QuotaReconService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 门店额度接口：账户开户/调额、预占-确认-释放三段式、流水对账、日结对平。
 * 写接口请求头携带 X-Operator-Id / X-Request-No / X-Biz-Time。
 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaAccountService accountService;
    private final QuotaHoldService holdService;
    private final QuotaReconService reconService;

    public QuotaController(QuotaAccountService accountService, QuotaHoldService holdService,
                           QuotaReconService reconService) {
        this.accountService = accountService;
        this.holdService = holdService;
        this.reconService = reconService;
    }

    // ---------------- 额度账户 ----------------

    /** 开户：每门店一户 */
    @PostMapping("/accounts")
    public ApiResponse<QuotaAccount> createAccount(@Valid @RequestBody QuotaAccountSaveRequest request) {
        return ApiResponse.ok(accountService.create(request));
    }

    /** 调整总额度（不得低于当前占用加已实扣） */
    @PostMapping("/accounts/{id}/adjust")
    public ApiResponse<QuotaAccount> adjust(@PathVariable Long id,
                                            @Valid @RequestBody QuotaAdjustRequest request) {
        return ApiResponse.ok(accountService.adjust(id, request));
    }

    @GetMapping("/accounts/{id}")
    public ApiResponse<QuotaAccount> getAccount(@PathVariable Long id) {
        return ApiResponse.ok(accountService.get(id));
    }

    @GetMapping("/accounts")
    public ApiResponse<List<QuotaAccount>> listAccounts(@RequestParam(required = false) String storeCode) {
        return ApiResponse.ok(accountService.list(storeCode));
    }

    // ---------------- 预占-确认-释放 ----------------

    /** 预占：40 分钟内有效，超时自动归还；可传 bizType/bizNo 挂业务单号 */
    @PostMapping("/holds")
    public ApiResponse<QuotaHold> reserve(@Valid @RequestBody QuotaHoldCreateRequest request) {
        return ApiResponse.ok(holdService.reserve(request));
    }

    /** 确认实扣：以有效预占为前提，与预占等额 */
    @PostMapping("/holds/{holdNo}/confirm")
    public ApiResponse<QuotaHold> confirm(@PathVariable String holdNo,
                                          @RequestBody(required = false) QuotaHoldActionRequest request) {
        return ApiResponse.ok(holdService.confirm(holdNo, request));
    }

    /** 手工释放：仅占用中的预占可释放；超时预占由系统扫描自动归还 */
    @PostMapping("/holds/{holdNo}/release")
    public ApiResponse<QuotaHold> release(@PathVariable String holdNo,
                                          @RequestBody(required = false) QuotaHoldActionRequest request) {
        return ApiResponse.ok(holdService.release(holdNo, request));
    }

    @GetMapping("/holds/{holdNo}")
    public ApiResponse<QuotaHold> getHold(@PathVariable String holdNo) {
        return ApiResponse.ok(holdService.getByHoldNo(holdNo));
    }

    @GetMapping("/holds")
    public ApiResponse<List<QuotaHold>> listHolds(@RequestParam(required = false) String storeCode,
                                                  @RequestParam(required = false) String status) {
        return ApiResponse.ok(holdService.list(storeCode, status));
    }

    // ---------------- 流水对账与日结对平 ----------------

    /** 额度流水（只追加）分页检索：对账查询入口 */
    @GetMapping("/flows")
    public ApiResponse<Page<QuotaFlow>> pageFlows(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) String storeCode,
                                                  @RequestParam(required = false) String holdNo,
                                                  @RequestParam(required = false) String flowType) {
        return ApiResponse.ok(accountService.pageFlows(current, size, storeCode, holdNo, flowType));
    }

    /** 日结对平报告查询 */
    @GetMapping("/recon")
    public ApiResponse<List<QuotaRecon>> listRecon(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reconDate) {
        return ApiResponse.ok(reconService.list(storeCode, reconDate));
    }

    /** 手工触发当日日结对平（应急/验收；缺省全部门店） */
    @PostMapping("/recon/run")
    public ApiResponse<List<QuotaRecon>> runRecon(@RequestBody(required = false) QuotaReconRunRequest request) {
        return ApiResponse.ok(reconService.reconcileToday(request == null ? null : request.getStoreCode()));
    }
}
