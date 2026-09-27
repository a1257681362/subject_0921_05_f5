package com.evops.common;

/**
 * 日志敏感字段打码：客户姓名、电话等 PII 一律先过这里再进日志。
 * 另提供单行化——备注等自由文本可能含换行，直接进日志会把一条记录
 * 折成多行，破坏按行 grep 的聚合检索。
 */
public final class LogMasks {

    private LogMasks() {
    }

    /** 电话：长度 ≥7 保留前 3 后 4，中间打码（139****5678）；过短全打码 */
    public static String phone(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        String s = raw.trim();
        if (s.length() < 7) {
            return "***";
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }

    /** 姓名：仅保留首字符，其余打码（王**）；单字名整体打码 */
    public static String name(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        String s = raw.trim();
        if (s.length() == 1) {
            return "*";
        }
        StringBuilder sb = new StringBuilder(s.length());
        sb.append(s.charAt(0));
        for (int i = 1; i < s.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    /** 单行化：换行/回车/制表符替换为空格，保证一条日志一行 */
    public static String singleLine(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }
}
