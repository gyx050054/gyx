/**
 * 【文件职责】
 * 告警扫描调度器（自研告警引擎）。
 *  - 每 30 秒扫描一次启用规则，评估设备实时遥测并生成/恢复告警记录；
 *  - 只负责「定时触发 + 事务包裹」，评估逻辑全部委托 AlarmService.scanAll()。
 *
 * 【数据流】
 *  - 触发：@Scheduled 按固定延迟触发（alarm.scan-interval-ms，默认 30000）。
 *  - 执行：scan() → 事务包裹 alarmService.scanAll() → 逐租户评估告警规则；
 *    异常被捕获并记日志，不影响下一次定时触发。
 */
package com.irrigation.task.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警扫描调度器（自研告警引擎）
 *
 * 每 30 秒扫描一次启用规则，评估设备实时遥测并生成/恢复告警记录。
 * 间隔可通过配置 alarm.scan-interval-ms 调整（默认 30000）。
 *
 * 高内聚低耦合：本类只负责「定时触发 + 事务包裹」，评估逻辑全部委托 {@link AlarmService#scanAll()}。
 */
@Component
public class AlarmScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlarmScanScheduler.class);

    private final AlarmService alarmService;

    public AlarmScanScheduler(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /**
     * 定时扫描（默认每 30 秒一次）。
     * fixedDelayString 用 SpEL 读取配置，无需改代码即可调整频率。
     */
    @Scheduled(fixedDelayString = "${alarm.scan-interval-ms:30000}")
    @Transactional
    public void scan() {
        try {
            alarmService.scanAll(); // 委托告警引擎扫描全部启用规则，评估设备实时遥测并生成/恢复告警记录
        } catch (Exception e) {
            log.warn("告警扫描失败：{}", e.getMessage()); // 单次扫描异常记日志，不影响下一次定时触发
        }
    }
}
