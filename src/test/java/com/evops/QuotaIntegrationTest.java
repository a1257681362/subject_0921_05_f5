package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.quota.entity.QuotaRecon;
import com.evops.quota.maintenance.QuotaMaintenance;
import com.evops.quota.service.QuotaReconService;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店额度三段式集成测试：
 * 开户/调额、预占-确认-释放、超时自动归还、实扣以预占为前提、
 * 重复提交、只追加流水、日结对平（BALANCED/MISMATCH）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(QuotaIntegrationTest.TestShiroBypass.class)
class QuotaIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    @Resource
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private QuotaMaintenance quotaMaintenance;
    @Resource
    private QuotaReconService quotaReconService;

    private long operatorSeq = 7000;
    private long reqSeq = 0;

    // ---------------- 开户与调额 ----------------

    @Test
    void account_create_duplicate_and_adjust_guard() throws Exception {
        long accountId = createAccount("SQ-A1", "1000.00");

        // 重复开户
        mockMvc.perform(post("/api/quota/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7001").header("X-Request-No", nextReq())
                        .content("{\"storeCode\":\"SQ-A1\",\"totalQuota\":500.00}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUOTA_ACCOUNT_DUPLICATED"));

        // 预占 600 后调额到 500（低于占用）被拒；调到 1600 放行
        createHold("SQ-A1", "600.00");
        mockMvc.perform(post("/api/quota/accounts/" + accountId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7001").header("X-Request-No", nextReq())
                        .content("{\"totalQuota\":500.00}"))
                .andExpect(jsonPath("$.code").value("QUOTA_ADJUST_BELOW_OCCUPIED"));
        mockMvc.perform(post("/api/quota/accounts/" + accountId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7001").header("X-Request-No", nextReq())
                        .content("{\"totalQuota\":1600.00,\"remark\":\"旺季上调\"}"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.totalQuota").value(1600.00))
                .andExpect(jsonPath("$.data.availableAmount").value(1000.00));

        // 开户 + 调额各一笔 ADJUST 流水
        Integer adjusts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_quota_flow WHERE account_id=? AND flow_type='ADJUST'",
                Integer.class, accountId);
        assertEquals(2, adjusts);
    }

    // ---------------- 预占 ----------------

    @Test
    void reserve_insufficient_and_available_deducted() throws Exception {
        long accountId = createAccount("SQ-B1", "100.00");
        createHold("SQ-B1", "60.00");

        // 可用 40，再占 50 被拒
        mockMvc.perform(post("/api/quota/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7002").header("X-Request-No", nextReq())
                        .content("{\"storeCode\":\"SQ-B1\",\"amount\":50.00}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUOTA_INSUFFICIENT"));

        mockMvc.perform(get("/api/quota/accounts/" + accountId))
                .andExpect(jsonPath("$.data.reservedAmount").value(60.00))
                .andExpect(jsonPath("$.data.availableAmount").value(40.00));
    }

    @Test
    void reserve_duplicate_request_blocked() throws Exception {
        createAccount("SQ-B2", "100.00");
        String reqNo = nextReq();
        String body = "{\"storeCode\":\"SQ-B2\",\"amount\":10.00}";
        mockMvc.perform(post("/api/quota/holds").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7003").header("X-Request-No", reqNo).content(body))
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/api/quota/holds").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7003").header("X-Request-No", reqNo).content(body))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REQUEST_DUPLICATED"));
    }

    @Test
    void reserve_requires_account() throws Exception {
        mockMvc.perform(post("/api/quota/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7004").header("X-Request-No", nextReq())
                        .content("{\"storeCode\":\"SQ-NONE\",\"amount\":10.00}"))
                .andExpect(jsonPath("$.code").value("QUOTA_ACCOUNT_NOT_FOUND"));
    }

    // ---------------- 确认实扣（以预占为前提，等额） ----------------

    @Test
    void confirm_happy_path_moves_reserved_to_used() throws Exception {
        long accountId = createAccount("SQ-C1", "1000.00");
        String holdNo = createHold("SQ-C1", "200.00");

        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/confirm")
                        .header("X-Operator-Id", "7005").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.holdStatus").value("CONFIRMED"));

        mockMvc.perform(get("/api/quota/accounts/" + accountId))
                .andExpect(jsonPath("$.data.reservedAmount").value(0.00))
                .andExpect(jsonPath("$.data.usedAmount").value(200.00))
                .andExpect(jsonPath("$.data.availableAmount").value(800.00));

        // 流水：RESERVE + CONFIRM 各一笔，变动后余额快照正确
        List<Map<String, Object>> flows = jdbcTemplate.queryForList(
                "SELECT flow_type, reserved_after, used_after FROM t_quota_flow "
                        + "WHERE hold_no=? AND flow_type IN ('RESERVE','CONFIRM') ORDER BY id", holdNo);
        assertEquals(2, flows.size());
        assertEquals("RESERVE", flows.get(0).get("FLOW_TYPE"));
        assertEquals("CONFIRM", flows.get(1).get("FLOW_TYPE"));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) flows.get(0).get("RESERVED_AFTER")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) flows.get(1).get("RESERVED_AFTER")));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) flows.get(1).get("USED_AFTER")));
    }

    @Test
    void confirm_without_hold_rejected() throws Exception {
        createAccount("SQ-C2", "100.00");
        // 实扣以预占为前提：无预占单直接实扣一律拒绝
        mockMvc.perform(post("/api/quota/holds/HOLD-NOPE-000000/confirm")
                        .header("X-Operator-Id", "7006").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUOTA_HOLD_NOT_FOUND"));
    }

    @Test
    void double_confirm_rejected() throws Exception {
        createAccount("SQ-C3", "100.00");
        String holdNo = createHold("SQ-C3", "30.00");
        confirm(holdNo, "OK");
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/confirm")
                        .header("X-Operator-Id", "7007").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value("QUOTA_HOLD_STATUS_ILLEGAL"));
    }

    // ---------------- 手工释放 ----------------

    @Test
    void release_returns_quota_and_blocks_confirm() throws Exception {
        long accountId = createAccount("SQ-D1", "100.00");
        String holdNo = createHold("SQ-D1", "40.00");

        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7008").header("X-Request-No", nextReq())
                        .content("{\"remark\":\"客户改主意\"}"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.holdStatus").value("RELEASED"));

        mockMvc.perform(get("/api/quota/accounts/" + accountId))
                .andExpect(jsonPath("$.data.reservedAmount").value(0.00))
                .andExpect(jsonPath("$.data.usedAmount").value(0.00))
                .andExpect(jsonPath("$.data.availableAmount").value(100.00));

        // 释放后再确认 / 再释放均被拒
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/confirm")
                        .header("X-Operator-Id", "7008").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value("QUOTA_HOLD_STATUS_ILLEGAL"));
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/release")
                        .header("X-Operator-Id", "7008").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value("QUOTA_HOLD_STATUS_ILLEGAL"));
    }

    // ---------------- 超时自动归还 ----------------

    @Test
    void expired_hold_auto_returned_and_confirm_rejected() throws Exception {
        long accountId = createAccount("SQ-E1", "100.00");
        String holdNo = createHold("SQ-E1", "50.00");

        // 把到期时间改到过去，触发超时扫描
        jdbcTemplate.update("UPDATE t_quota_hold SET expire_time=? WHERE hold_no=?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(1)), holdNo);
        int returned = quotaMaintenance.expireOverdue();
        assertEquals(1, returned);

        mockMvc.perform(get("/api/quota/holds/" + holdNo))
                .andExpect(jsonPath("$.data.holdStatus").value("EXPIRED"));
        mockMvc.perform(get("/api/quota/accounts/" + accountId))
                .andExpect(jsonPath("$.data.reservedAmount").value(0.00))
                .andExpect(jsonPath("$.data.availableAmount").value(100.00));

        // 超时归还后不可实扣；EXPIRE 流水已写
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/confirm")
                        .header("X-Operator-Id", "7009").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value("QUOTA_HOLD_STATUS_ILLEGAL"));
        Integer expires = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_quota_flow WHERE hold_no=? AND flow_type='EXPIRE'",
                Integer.class, holdNo);
        assertEquals(1, expires);
    }

    @Test
    void confirm_after_expiry_settles_inline() throws Exception {
        long accountId = createAccount("SQ-E2", "100.00");
        String holdNo = createHold("SQ-E2", "50.00");
        jdbcTemplate.update("UPDATE t_quota_hold SET expire_time=? WHERE hold_no=?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(1)), holdNo);

        // 扫描未及时，确认撞到已超时预占：内联结算归还并拒绝实扣
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/confirm")
                        .header("X-Operator-Id", "7010").header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value("QUOTA_HOLD_STATUS_ILLEGAL"));
        mockMvc.perform(get("/api/quota/holds/" + holdNo))
                .andExpect(jsonPath("$.data.holdStatus").value("EXPIRED"));
        mockMvc.perform(get("/api/quota/accounts/" + accountId))
                .andExpect(jsonPath("$.data.reservedAmount").value(0.00));
    }

    // ---------------- 日结对平 ----------------

    @Test
    void recon_balanced_after_full_lifecycle() throws Exception {
        createAccount("SQ-F1", "1000.00");
        confirm(createHold("SQ-F1", "100.00"), "OK");   // 实扣 100
        release(createHold("SQ-F1", "50.00"), "OK");    // 释放 50
        createHold("SQ-F1", "30.00");                    // 仍在占用 30

        List<QuotaRecon> results = quotaReconService.reconcileToday("SQ-F1");
        assertEquals(1, results.size());
        QuotaRecon recon = results.get(0);
        assertEquals("BALANCED", recon.getReconResult());
        assertEquals(0, new BigDecimal("30.00").compareTo(recon.getReservedActual()));
        assertEquals(0, new BigDecimal("100.00").compareTo(recon.getUsedActual()));
        assertEquals(LocalDate.now(), recon.getReconDate());
    }

    @Test
    void recon_flags_dangling_and_drift() throws Exception {
        long accountId = createAccount("SQ-F2", "1000.00");
        String holdNo = createHold("SQ-F2", "80.00");

        // 超时未还（超过容忍窗口）→ dangling
        jdbcTemplate.update("UPDATE t_quota_hold SET expire_time=? WHERE hold_no=?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(10)), holdNo);
        List<QuotaRecon> dangling = quotaReconService.reconcileToday("SQ-F2");
        assertEquals("MISMATCH", dangling.get(0).getReconResult());
        assertEquals(1, dangling.get(0).getDanglingHoldCount().intValue());
        assertTrue(dangling.get(0).getDetailJson().contains(holdNo));

        // 归还后制造账户与流水漂移 → MISMATCH
        quotaMaintenance.expireOverdue();
        jdbcTemplate.update("UPDATE t_quota_account SET reserved_amount = reserved_amount + 5 WHERE id=?",
                accountId);
        List<QuotaRecon> drift = quotaReconService.reconcileToday("SQ-F2");
        assertEquals("MISMATCH", drift.get(0).getReconResult());
        assertEquals(0, drift.get(0).getDanglingHoldCount().intValue());
    }

    // ---------------- 流水只追加 ----------------

    @Test
    void flows_are_append_only_and_queryable() throws Exception {
        createAccount("SQ-G1", "500.00");
        String holdNo = createHold("SQ-G1", "120.00");
        confirm(holdNo, "OK");

        mockMvc.perform(get("/api/quota/flows").param("holdNo", holdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].flowType").value("CONFIRM"))
                .andExpect(jsonPath("$.data.records[1].flowType").value("RESERVE"));
    }

    // ---------------- 辅助方法 ----------------

    private long createAccount(String storeCode, String totalQuota) throws Exception {
        JsonNode node = performJson(post("/api/quota/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", nextReq())
                .content("{\"storeCode\":\"" + storeCode + "\",\"totalQuota\":" + totalQuota + "}"));
        return node.get("data").get("id").asLong();
    }

    private String createHold(String storeCode, String amount) throws Exception {
        JsonNode node = performJson(post("/api/quota/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", nextReq())
                .content("{\"storeCode\":\"" + storeCode + "\",\"amount\":" + amount + "}"));
        return node.get("data").get("holdNo").asText();
    }

    private void confirm(String holdNo, String expectedCode) throws Exception {
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/confirm")
                        .header("X-Operator-Id", String.valueOf(++operatorSeq))
                        .header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private void release(String holdNo, String expectedCode) throws Exception {
        mockMvc.perform(post("/api/quota/holds/" + holdNo + "/release")
                        .header("X-Operator-Id", String.valueOf(++operatorSeq))
                        .header("X-Request-No", nextReq()))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private String nextReq() {
        return "QTEST-" + (++reqSeq) + "-" + System.nanoTime();
    }

    private JsonNode performJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }
}
