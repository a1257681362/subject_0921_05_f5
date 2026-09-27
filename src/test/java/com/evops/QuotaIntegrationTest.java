package com.evops;

import com.evops.quota.maintenance.QuotaMaintenance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店额度三段式集成测试：
 * 建户/调额、预占-实扣-释放、40分钟超时自动还、实扣以预占为前提、
 * 重复提交兜底、明细流水快照、日结对平（平/不平）。
 * 各用例按门店编码隔离，互不影响。
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
    private MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private QuotaMaintenance quotaMaintenance;

    // 同一测试类共享 Spring 上下文（内存库），请求号必须跨用例唯一，避免撞 uk_quota_rsv_request
    private static final AtomicLong REQ_SEQ = new AtomicLong(9000);

    private String nextReq() {
        return "Q-REQ-" + REQ_SEQ.incrementAndGet();
    }

    // ---------------- 建户与调额 ----------------

    @Test
    void account_create_duplicate_and_adjust_guard() throws Exception {
        createAccount("QS01", "1000.00");
        // 重复建户
        mockMvc.perform(post("/api/quota/accounts")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"QS01\",\"totalQuota\":500}"))
                .andExpect(jsonPath("$.code").value("QUOTA_ACCOUNT_DUPLICATED"));

        // 预占 400 后，总额度不可调到 400 以下
        reserve("QS01", "400.00", null);
        mockMvc.perform(put("/api/quota/accounts/QS01")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalQuota\":399.99}"))
                .andExpect(jsonPath("$.code").value("QUOTA_TOTAL_TOO_LOW"));
        mockMvc.perform(put("/api/quota/accounts/QS01")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalQuota\":400.00}"))
                .andExpect(jsonPath("$.code").value("OK"));

        // 未建户门店
        mockMvc.perform(get("/api/quota/accounts/QS-NONE"))
                .andExpect(jsonPath("$.code").value("QUOTA_ACCOUNT_NOT_FOUND"));
    }

    // ---------------- 预占：余额、幂等 ----------------

    @Test
    void reserve_insufficient_and_duplicate_request_rejected() throws Exception {
        createAccount("QS02", "100.00");
        reserve("QS02", "60.00", null);

        // 可用 40，再占 50 被拒
        mockMvc.perform(post("/api/quota/reserve")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"QS02\",\"amount\":50.00}"))
                .andExpect(jsonPath("$.code").value("QUOTA_NOT_ENOUGH"));

        // 同一请求号重复提交：库层唯一约束兜底
        String dupReq = nextReq();
        mockMvc.perform(post("/api/quota/reserve")
                        .header("X-Operator-Id", "1001").header("X-Request-No", dupReq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"QS02\",\"amount\":10.00}"))
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/api/quota/reserve")
                        .header("X-Operator-Id", "1001").header("X-Request-No", dupReq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"QS02\",\"amount\":10.00}"))
                .andExpect(jsonPath("$.code").value("REQUEST_DUPLICATED"));

        // 账户预占余额只加了一次：60 + 10
        assertEquals(new BigDecimal("70.00"), accountField("QS02", "reserved_amount"));
    }

    // ---------------- 三段式：预占 → 实扣 ----------------

    @Test
    void confirm_requires_valid_reservation() throws Exception {
        createAccount("QS03", "1000.00");

        // 无预占直接实扣
        mockMvc.perform(post("/api/quota/confirm")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"RSV-NOT-EXIST\"}"))
                .andExpect(jsonPath("$.code").value("QUOTA_RESERVATION_NOT_FOUND"));

        String rsvNo = reserve("QS03", "200.00", "O-QS03-1");
        mockMvc.perform(post("/api/quota/confirm")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + rsvNo + "\"}"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.rsvStatus").value("CONFIRMED"));

        // 重复实扣
        mockMvc.perform(post("/api/quota/confirm")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + rsvNo + "\"}"))
                .andExpect(jsonPath("$.code").value("QUOTA_RESERVATION_STATUS_ILLEGAL"));

        assertEquals(new BigDecimal("0.00"), accountField("QS03", "reserved_amount"));
        assertEquals(new BigDecimal("200.00"), accountField("QS03", "used_amount"));

        // 流水：RESERVE + CONFIRM 各一行，快照连续
        JsonNode ledger = ledger("QS03", null);
        assertEquals(2, ledger.size());
        assertEquals("RESERVE", ledger.get(0).get("entryType").asText());
        assertEquals(0, new BigDecimal("200.00").compareTo(
                new BigDecimal(ledger.get(0).get("reservedAfter").asText())));
        assertEquals("CONFIRM", ledger.get(1).get("entryType").asText());
        assertEquals(0, new BigDecimal("0.00").compareTo(
                new BigDecimal(ledger.get(1).get("reservedAfter").asText())));
        assertEquals(0, new BigDecimal("200.00").compareTo(
                new BigDecimal(ledger.get(1).get("usedAfter").asText())));
    }

    // ---------------- 三段式：预占 → 手工释放 ----------------

    @Test
    void release_manual_and_repeat_rejected() throws Exception {
        createAccount("QS04", "500.00");
        String rsvNo = reserve("QS04", "120.00", null);

        mockMvc.perform(post("/api/quota/release")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + rsvNo + "\",\"remark\":\"客户取消\"}"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.rsvStatus").value("RELEASED"));

        // 重复释放
        mockMvc.perform(post("/api/quota/release")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + rsvNo + "\"}"))
                .andExpect(jsonPath("$.code").value("QUOTA_RESERVATION_STATUS_ILLEGAL"));

        assertEquals(new BigDecimal("0.00"), accountField("QS04", "reserved_amount"));
        assertEquals(new BigDecimal("0.00"), accountField("QS04", "used_amount"));
    }

    // ---------------- 40 分钟超时：自动还 + 过期不可实扣 ----------------

    @Test
    void expired_reservation_auto_returned_and_not_confirmable() throws Exception {
        createAccount("QS05", "800.00");
        // 业务时间传 41 分钟前：预占即刻过期
        String past = LocalDateTime.now().minusMinutes(41).toString();
        String rsvNo = reserveAt("QS05", "300.00", past);
        assertEquals(new BigDecimal("300.00"), accountField("QS05", "reserved_amount"));

        // 过期预占实扣被拒
        mockMvc.perform(post("/api/quota/confirm")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + rsvNo + "\"}"))
                .andExpect(jsonPath("$.code").value("QUOTA_RESERVATION_EXPIRED"));

        // 定时任务扫描（与生产同一路径）：超时自动还
        int expired = quotaMaintenance.sweepExpired();
        assertTrue(expired >= 1, "过期预占应被自动归还");
        assertEquals(new BigDecimal("0.00"), accountField("QS05", "reserved_amount"));
        assertEquals("EXPIRED", jdbcTemplate.queryForObject(
                "SELECT rsv_status FROM t_quota_reservation WHERE reservation_no=?",
                String.class, rsvNo));

        // 已超时还的单再释放/实扣均明确拒绝
        mockMvc.perform(post("/api/quota/release")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + rsvNo + "\"}"))
                .andExpect(jsonPath("$.code").value("QUOTA_RESERVATION_EXPIRED"));

        // 流水：RESERVE + EXPIRE
        JsonNode ledger = ledger("QS05", null);
        assertEquals(2, ledger.size());
        assertEquals("EXPIRE", ledger.get(1).get("entryType").asText());
    }

    // ---------------- 日结对平 ----------------

    @Test
    void reconcile_daily_balanced_and_detects_imbalance() throws Exception {
        createAccount("QS06", "2000.00");
        // 实扣 500、释放 200、过期 300、未结 400：当日预占 1400
        String rConfirm = reserve("QS06", "500.00", null);
        confirm(rConfirm);
        String rRelease = reserve("QS06", "200.00", null);
        release(rRelease);
        reserveAt("QS06", "300.00", LocalDateTime.now().minusMinutes(41).toString());
        quotaMaintenance.sweepExpired();
        reserve("QS06", "400.00", null); // 仍未结

        JsonNode report = reconcile("QS06", LocalDate.now().toString());
        assertTrue(report.get("balanced").asBoolean(), "对账应平：" + report);
        assertEquals(0, new BigDecimal("1400.00").compareTo(new BigDecimal(report.get("reserveSum").asText())));
        assertEquals(0, new BigDecimal("500.00").compareTo(new BigDecimal(report.get("confirmSum").asText())));
        assertEquals(0, new BigDecimal("200.00").compareTo(new BigDecimal(report.get("releaseSum").asText())));
        assertEquals(0, new BigDecimal("300.00").compareTo(new BigDecimal(report.get("expireSum").asText())));
        assertEquals(0, new BigDecimal("400.00").compareTo(new BigDecimal(report.get("openSum").asText())));

        // 制造不平：账户余额被绕过流水直改（如历史直插/人工修数）
        jdbcTemplate.update("UPDATE t_quota_account SET reserved_amount = reserved_amount + 1 WHERE store_code='QS06'");
        JsonNode imbalanced = reconcile("QS06", LocalDate.now().toString());
        assertTrue(!imbalanced.get("balanced").asBoolean(), "账户与流水不平应被检出");
        assertTrue(imbalanced.get("mismatches").size() > 0);
    }

    // ---------------- 工具 ----------------

    private void createAccount(String storeCode, String totalQuota) throws Exception {
        mockMvc.perform(post("/api/quota/accounts")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"" + storeCode + "\",\"totalQuota\":" + totalQuota + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
    }

    private String reserve(String storeCode, String amount, String bizRef) throws Exception {
        String body = "{\"storeCode\":\"" + storeCode + "\",\"amount\":" + amount
                + (bizRef == null ? "" : ",\"bizRef\":\"" + bizRef + "\"") + "}";
        MvcResult result = mockMvc.perform(post("/api/quota/reserve")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value("OK")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("reservationNo").asText();
    }

    /** 以指定业务时间预占（传过去时刻可立即制造过期单，与生产同一有效期口径） */
    private String reserveAt(String storeCode, String amount, String bizTime) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/quota/reserve")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .header("X-Biz-Time", bizTime)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"" + storeCode + "\",\"amount\":" + amount + "}"))
                .andExpect(jsonPath("$.code").value("OK")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("reservationNo").asText();
    }

    private void confirm(String reservationNo) throws Exception {
        mockMvc.perform(post("/api/quota/confirm")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + reservationNo + "\"}"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    private void release(String reservationNo) throws Exception {
        mockMvc.perform(post("/api/quota/release")
                        .header("X-Operator-Id", "1001").header("X-Request-No", nextReq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationNo\":\"" + reservationNo + "\"}"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    private JsonNode ledger(String storeCode, String bizDate) throws Exception {
        String url = "/api/quota/ledger?storeCode=" + storeCode
                + (bizDate == null ? "" : "&bizDate=" + bizDate);
        MvcResult result = mockMvc.perform(get(url))
                .andExpect(jsonPath("$.code").value("OK")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode reconcile(String storeCode, String bizDate) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/quota/reconcile?storeCode=" + storeCode
                        + "&bizDate=" + bizDate))
                .andExpect(jsonPath("$.code").value("OK")).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private BigDecimal accountField(String storeCode, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT " + field + " FROM t_quota_account WHERE store_code=?",
                BigDecimal.class, storeCode);
    }
}
