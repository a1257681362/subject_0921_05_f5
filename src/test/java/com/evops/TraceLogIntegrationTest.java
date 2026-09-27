package com.evops;

import com.evops.batch.maintenance.BatchJobMaintenance;
import com.evops.sensitive.SensitiveWordManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 日志链路追踪集成测试（总部质控要求）：
 * 响应头回带/生成/非法拒绝 traceId；最绕样例数据（混淆敏感词姓名、emoji+换行备注、
 * 带点号与中文的单号）全流程跑通；日志统一带 [traceId] [加工单号] 两列；
 * 异步批量任务继承提交请求 traceId；定时任务自动生成 traceId；
 * 敏感字段（客户姓名/电话）入日志前打码；按单号一键聚合脚本冒烟。
 * 日志文件：application-test.yml 指向 target/test-logs/evops.log。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TraceLogIntegrationTest.TestShiroBypass.class)
class TraceLogIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    private static final Path LOG_FILE = Paths.get("target/test-logs/evops.log");
    private static final Path DICT = Paths.get("./target/test-dict/sensitive-words.txt");
    private static final String DICT_CONTENT =
            "# 链路追踪测试词库\n"
                    + "月报拦截词|BLOCK\n"
                    + "月报打码词|MASK\n";

    @Resource
    private MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private BatchJobMaintenance maintenance;
    @Resource
    private SensitiveWordManager wordManager;

    // 与其他测试类共用内存库：序列段错开，避免审计请求号撞唯一约束
    private static final AtomicLong OPERATOR_SEQ = new AtomicLong(7000);
    private static final AtomicLong REQ_SEQ = new AtomicLong(9000);

    @BeforeAll
    static void writeDict() throws IOException {
        Files.createDirectories(DICT.getParent());
        Files.write(DICT, DICT_CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void reloadDict() {
        // 词库文件可能被其他测试类改写：每个用例重写并从磁盘重载
        try {
            Files.write(DICT, DICT_CONTENT.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        wordManager.reload();
    }

    // ---------------- 响应头回带 / 生成 / 非法拒绝 ----------------

    @Test
    void trace_id_echoed_generated_and_illegal_rejected() throws Exception {
        // 传入合法 traceId：响应头原样回带（串联上游/调用方）
        mockMvc.perform(get("/api/health").header("X-Trace-Id", "TRACE-HDR-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "TRACE-HDR-0001"));

        // 不传：服务端生成 32 位 hex 并回带
        MvcResult generated = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk()).andReturn();
        String generatedId = generated.getResponse().getHeader("X-Trace-Id");
        assertTrue(generatedId != null && generatedId.matches("[0-9a-f]{32}"),
                "缺省应生成 32 位 hex traceId，实际=" + generatedId);

        // 传入非法（含空格与特殊字符）：不沿用，防 CRLF 注入响应头与伪造日志列
        MvcResult illegal = mockMvc.perform(get("/api/health")
                        .header("X-Trace-Id", "bad trace id!"))
                .andExpect(status().isOk()).andReturn();
        String illegalHandled = illegal.getResponse().getHeader("X-Trace-Id");
        assertTrue(illegalHandled != null && illegalHandled.matches("[0-9a-f]{32}"),
                "非法 traceId 应重新生成，实际=" + illegalHandled);
    }

    // ---------------- 最绕样例数据：全流程跑通 + 日志格式与打码 ----------------

    @Test
    void trickiest_order_flow_aggregatable_by_order_no_and_masked() throws Exception {
        long lensId = createLens("L-TRACE-A");
        // 最绕的一单：单号带点号与中文（聚合必须按固定字符串匹配，不能当正则）；
        // 姓名夹带"全角空格+分隔符"混淆的打码级敏感词；备注含 emoji、全角字符与换行
        String orderNo = "O-TRACE.2026.绕-0001";
        String customerName = "王　月-报-打-码-词　女士";
        String phone = "13912345678";
        String remark = "加急😀全角ＡＢＣ\n第二行备注";

        // 三次请求各用不同 traceId：一张单跨多次请求，按单号仍能聚成一串
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .header("X-Trace-Id", "TRACE-ORDER-0001")
                        .content(orderJson(orderNo, lensId, customerName, phone, remark)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(header().string("X-Trace-Id", "TRACE-ORDER-0001"));

        // 守门打码已生效：落库姓名不含敏感词原文
        String storedName = jdbcTemplate.queryForObject(
                "SELECT customer_name FROM t_process_order WHERE order_no='" + orderNo + "'",
                String.class);
        assertTrue(storedName != null && storedName.contains("*") && !storedName.contains("月报打码词"),
                "姓名中的打码词应被替换为 *，实际=" + storedName);

        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_process_order WHERE order_no='" + orderNo + "'", Long.class);
        transit(orderId, "start", "TRACE-ORDER-0002");
        transit(orderId, "finish", "TRACE-ORDER-0003");

        String logs = readLog();
        List<String> orderLines = linesContaining(logs, orderNo);
        assertTrue(orderLines.size() >= 3,
                "建档+开工+完工应至少 3 行带单号日志，实际=" + orderLines.size());

        // 统一格式：每行都有 [traceId] [加工单号] 两列；三次请求三个 traceId 都能按单号聚到
        for (String line : orderLines) {
            assertTrue(line.contains("[" + orderNo + "]"), "MDC 加工单号列缺失：" + line);
        }
        assertTrue(orderLines.stream().anyMatch(l -> l.contains("[TRACE-ORDER-0001]")));
        assertTrue(orderLines.stream().anyMatch(l -> l.contains("[TRACE-ORDER-0002]")));
        assertTrue(orderLines.stream().anyMatch(l -> l.contains("[TRACE-ORDER-0003]")));

        // 敏感字段打码：日志里只有打码后的电话/姓名，不出现原文
        assertFalse(logs.contains(phone), "日志不得出现电话原文");
        assertTrue(logs.contains("139****5678"), "日志应出现打码电话");
        assertFalse(logs.contains("月-报-打"), "日志不得出现姓名原文（混淆形态）");
        assertFalse(logs.contains("月报打码词"), "日志不得出现姓名原文（归一形态）");
        assertTrue(logs.contains("客户=王*"), "日志应出现打码姓名");

        // 备注含换行：日志仍一行一条，聚合不被折行打断
        for (String line : orderLines) {
            assertFalse(line.trim().isEmpty());
        }
    }

    // ---------------- BLOCK 拦截路径：拒绝也留带单号的日志 ----------------

    @Test
    void blocked_order_rejection_logged_with_order_no() throws Exception {
        long lensId = createLens("L-TRACE-B");
        String orderNo = "O-TRACE-BLOCK-0001";
        // 全角空格混淆的拦截级敏感词：归一化后照样命中，整单拒绝
        String remark = "加急月　报　拦　截　词谢谢";

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .content(orderJson(orderNo, lensId, "拦截客户", null, remark)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SENSITIVE_WORD_BLOCKED"));

        List<String> orderLines = linesContaining(readLog(), orderNo);
        assertTrue(orderLines.stream().anyMatch(
                l -> l.contains("[" + orderNo + "]") && l.contains("SENSITIVE_WORD_BLOCKED")),
                "拦截拒绝应留带单号的日志：" + orderLines);
    }

    // ---------------- 异步批量任务：继承提交请求的 traceId，逐单带单号 ----------------

    @Test
    void batch_job_inherits_request_trace_id_and_tags_each_order() throws Exception {
        long lensId = createLens("L-TRACE-C");
        String orderNo = "O-TRACE-ASYNC-0001";
        createOrder(orderNo, lensId, "批量溯源客户", "13800001111");

        String resp = mockMvc.perform(post("/api/batch/jobs").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .header("X-Trace-Id", "TRACEASYNC0001")
                        .content("{\"jobType\":\"RECHECK\",\"storeCode\":\"S-TRACE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(header().string("X-Trace-Id", "TRACEASYNC0001"))
                .andReturn().getResponse().getContentAsString();
        String jobNo = objectMapper.readTree(resp).get("data").get("jobNo").asText();
        waitForStatus(jobNo, "SUCCEEDED", 15_000);

        String logs = readLog();
        assertTrue(containsLine(logs, l -> l.contains("batch-job-")
                        && l.contains("[TRACEASYNC0001]") && l.contains("批量任务开始")),
                "后台线程应继承提交请求的 traceId");
        assertTrue(containsLine(logs, l -> l.contains("batch-job-")
                        && l.contains("[TRACEASYNC0001]") && l.contains("[" + orderNo + "]")
                        && l.contains("批量核对完成")),
                "后台逐单日志应同时带 traceId 与加工单号");
        assertTrue(containsLine(logs, l -> l.contains("batch-job-")
                        && l.contains("[TRACEASYNC0001]") && l.contains("批量任务结束")),
                "任务结束日志应带同一 traceId");
    }

    // ---------------- 定时任务：无上游请求，自动生成 traceId ----------------

    @Test
    void scheduled_purge_runs_with_generated_trace_id() throws Exception {
        // 造一个已过期的终态任务，触发定时清理打日志
        jdbcTemplate.update("INSERT INTO t_batch_job (job_no, job_type, job_status, expire_time, request_no)"
                + " VALUES ('JOB-TRACE-PURGE-1', 'RECHECK', 'SUCCEEDED',"
                + " TIMESTAMPADD('DAY', -1, CURRENT_TIMESTAMP), 'TRACE-PURGE-REQ-1')");

        maintenance.scheduledPurge();

        String logs = readLog();
        assertTrue(containsLine(logs,
                l -> l.contains("批量任务记录清理") && l.matches(".*\\[[0-9a-f]{32}\\].*")),
                "定时任务应自动生成 32 位 hex traceId");
    }

    // ---------------- 一键聚合脚本：按单号抓全量日志 ----------------

    @Test
    void order_log_script_aggregates_every_line_of_the_order() throws Exception {
        long lensId = createLens("L-TRACE-D");
        // 纯 ASCII 单号：经 shell 传参不依赖环境编码（中文单号由上面用例覆盖）
        String orderNo = "O-TRACE-SCRIPT-0001";
        long orderId = createOrder(orderNo, lensId, "脚本聚合客户", "13700002222");
        transit(orderId, "start", "TRACE-SCRIPT-0002");

        Process process = new ProcessBuilder("sh", "scripts/order-log.sh", orderNo, "target/test-logs")
                .redirectErrorStream(true).start();
        String output = readFully(process.getInputStream());
        int exit = process.waitFor();
        assertEquals(0, exit, "聚合脚本应执行成功：" + output);

        List<String> lines = new ArrayList<>();
        for (String line : output.split("\r?\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        assertTrue(lines.size() >= 2, "建档+开工至少聚到 2 行，实际=" + lines);
        for (String line : lines) {
            assertTrue(line.contains(orderNo), "聚合结果每行都应含单号：" + line);
        }
    }

    // ---------------- 辅助 ----------------

    private String orderJson(String orderNo, long lensId, String customerName,
                             String phone, String remark) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("orderNo", orderNo);
        root.put("lensId", lensId);
        if (customerName != null) {
            root.put("customerName", customerName);
        }
        if (phone != null) {
            root.put("customerPhone", phone);
        }
        root.put("storeCode", "S-TRACE");
        if (remark != null) {
            root.put("remark", remark);
        }
        com.fasterxml.jackson.databind.node.ArrayNode prescriptions = root.putArray("prescriptions");
        ObjectNode left = prescriptions.addObject();
        left.put("eye", "L").put("sphere", -1.00);
        ObjectNode right = prescriptions.addObject();
        right.put("eye", "R").put("sphere", -2.25).put("cylinder", -0.75).put("axis", 90);
        return objectMapper.writeValueAsString(root);
    }

    private long createLens(String code) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"镜片" + code + "\","
                + "\"lensType\":\"单光\",\"price\":399.00}";
        String resp = mockMvc.perform(post("/api/lens").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long lensId, String customerName,
                             String phone) throws Exception {
        String resp = mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .content(orderJson(orderNo, lensId, customerName, phone, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private void transit(long orderId, String action, String traceId) throws Exception {
        mockMvc.perform(post("/api/orders/" + orderId + "/" + action)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .header("X-Trace-Id", traceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
    }

    private JsonNode waitForStatus(String jobNo, String status, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String resp = mockMvc.perform(get("/api/batch/jobs/" + jobNo))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(resp).get("data");
            if (status.equals(data.get("jobStatus").asText())) {
                return data;
            }
            Thread.sleep(30);
        }
        fail("等待任务[" + jobNo + "]状态[" + status + "]超时");
        return null;
    }

    private String readLog() throws IOException {
        if (!Files.exists(LOG_FILE)) {
            return "";
        }
        return new String(Files.readAllBytes(LOG_FILE), StandardCharsets.UTF_8);
    }

    private List<String> linesContaining(String logs, String keyword) {
        List<String> lines = new ArrayList<>();
        for (String line : logs.split("\r?\n")) {
            if (line.contains(keyword)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private boolean containsLine(String logs, java.util.function.Predicate<String> predicate) {
        for (String line : logs.split("\r?\n")) {
            if (predicate.test(line)) {
                return true;
            }
        }
        return false;
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
