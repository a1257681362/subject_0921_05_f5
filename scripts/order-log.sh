#!/bin/sh
# 按加工单号一键聚合检索应用日志：当前日志 + 滚动历史（含 .gz 压缩）一把抓，
# 按行首时间戳排序输出——验光师月报核对、质控追溯直接看这一份。
#
# 用法: sh scripts/order-log.sh <加工单号> [日志目录，默认 logs]
# 示例: sh scripts/order-log.sh O-20260927-0001
#       sh scripts/order-log.sh O-20260927-0001 /var/log/evops
#
# 日志每行格式：时间 级别 [线程] [traceId] [加工单号] 类名 - 消息
# 单号按固定字符串匹配（grep -F），单号含 . * 等字符也不会被当成正则。

if [ -z "$1" ]; then
    echo "用法: sh scripts/order-log.sh <加工单号> [日志目录，默认 logs]" >&2
    exit 1
fi

ORDER_NO="$1"
LOG_DIR="${2:-logs}"

if [ ! -d "$LOG_DIR" ]; then
    echo "日志目录不存在: $LOG_DIR（可用第二参数指定日志目录）" >&2
    exit 1
fi

(
    # 当前日志与已滚动（未压缩）的历史
    find "$LOG_DIR" -maxdepth 1 -type f -name '*.log' \
        -exec grep -h -F -- "$ORDER_NO" {} +
    find "$LOG_DIR" -maxdepth 1 -type f -name '*.log.*' ! -name '*.gz' \
        -exec grep -h -F -- "$ORDER_NO" {} +
    # 压缩历史
    find "$LOG_DIR" -maxdepth 1 -type f -name '*.log.*.gz' \
        -exec zgrep -h -F -- "$ORDER_NO" {} +
) 2>/dev/null | sort
