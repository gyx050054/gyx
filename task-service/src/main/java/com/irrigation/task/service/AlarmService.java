package com.irrigation.task.service;

import com.irrigation.task.entity.AlarmRecord;
import com.irrigation.task.entity.AlarmRule;
import com.irrigation.task.repository.AlarmRecordRepository;
import com.irrigation.task.repository.AlarmRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警引擎业务服务（自研，第四版）
 *
 * 职责（对应内部需求「自定义告警」）：
 *  - 规则 CRUD：增删改查 + 启用开关，按租户隔离
 *  - 定�时扫描（每 30 秒，由 {@link AlarmScanScheduler} 触发）：
 *      读启用规则 → 拉设备最新遥测 → 比较阈值 → 命中建/刷新告警记录，恢复自动结束
 *  - 告警确认（App 红点点击确认）
 *  - 未确认告警计数（App 顶栏红点）
 *
 * 状态机（AlarmRecord.Status）：
 *  ACTIVE(触发) → ACKNOWLEDGED(已确认) → RESOLVED(条件恢复自动结束)
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    private final AlarmRuleRepository ruleRepository;
    private final AlarmRecordRepository recordRepository;
    private final ThingsBoardClient tbClient;

    public AlarmService(AlarmRuleRepository ruleRepository,
                        AlarmRecordRepository recordRepository,
                        ThingsBoardClient tbClient) {
        this.ruleRepository = ruleRepository;
        this.recordRepository = recordRepository;
        this.tbClient = tbClient;
    }

    // ---------- 规则 CRUD ----------

    @Transactional
    public AlarmRule createRule(AlarmRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (rule.getMetric() == null || rule.getMetric().isBlank()) {
            throw new IllegalArgumentException("监控指标不能为空");
        }
        return ruleRepository.save(rule);
    }

    @Transactional
    public Optional<AlarmRule> updateRule(Long id, AlarmRule patch) {
        return ruleRepository.findById(id).map(rule -> {
            if (patch.getName() != null) rule.setName(patch.getName());
            if (patch.getDeviceType() != null) rule.setDeviceType(patch.getDeviceType());
            if (patch.getMetric() != null) rule.setMetric(patch.getMetric());
            if (patch.getOperator() != null) rule.setOperator(patch.getOperator());
            if (patch.getThreshold() != null) rule.setThreshold(patch.getThreshold());
            if (patch.getSeverity() != null) rule.setSeverity(patch.getSeverity());
            if (patch.getMessage() != null) rule.setMessage(patch.getMessage());
            if (patch.getEnabled() != null) rule.setEnabled(patch.getEnabled());
            return ruleRepository.save(rule);
        });
    }

    @Transactional
    public boolean deleteRule(Long id) {
        if (ruleRepository.existsById(id)) {
            ruleRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /** 切换启用状态 */
    @Transactional
    public Optional<AlarmRule> toggleRule(Long id, boolean enabled) {
        return ruleRepository.findById(id).map(rule -> {
            rule.setEnabled(enabled);
            return ruleRepository.save(rule);
        });
    }

    /** 按租户查规则（App 规则管理页） */
    public List<AlarmRule> listRules(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ruleRepository.findByEnabledTrue(); // 演示/无租户：仅返回启用规则供扫描
        }
        return ruleRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    // ---------- 告警记录 ----------

    /**
     * 按租户查告警记录，可选按状态过滤（App 告警列表）。
     * 严格租户隔离：tenantId 为空时返回空列表（杜绝"空租户告警互相暴露"的权限 bug）。
     */
    public List<AlarmRecord> listRecords(String tenantId, AlarmRecord.Status status) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        if (status != null) {
            return recordRepository.findByTenantIdAndStatusOrderByFirstAtDesc(tenantId, status);
        }
        return recordRepository.findByTenantIdOrderByFirstAtDesc(tenantId);
    }

    /** 确认告警（App 红点消失）：ACTIVE → ACKNOWLEDGED */
    @Transactional
    public boolean ack(Long id) {
        return recordRepository.findById(id).map(record -> {
            if (record.getStatus() == AlarmRecord.Status.ACTIVE) {
                record.setStatus(AlarmRecord.Status.ACKNOWLEDGED);
                recordRepository.save(record);
            }
            return true;
        }).orElse(false);
    }

    /** 未确认告警计数（App 顶栏红点：ACTIVE 未确认数）；tenantId 为空返回 0（隔离） */
    public long unreadCount(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return 0L;
        }
        return recordRepository.countByTenantIdAndStatus(tenantId, AlarmRecord.Status.ACTIVE);
    }

    // ---------- 扫描评估（每 30 秒） ----------

    /**
     * 扫描全部启用规则，评估每台目标设备的实时遥测（真多租户隔离，第五版）。
     *
     * 关键改动：不再用全局单一账号扫描所有规则（会导致租户间告警串数据）。
     * 改为「按规则所属租户」分组遍历：每个租户用其自己的 TB 账号查设备、比对遥测，
     * 并按该租户生成/恢复告警记录 —— 实现真正的租户隔离。
     */
    @Transactional
    public void scanAll() {
        List<AlarmRule> rules = ruleRepository.findByEnabledTrue();
        // 规则按 tenantId 分组：null 组（旧数据/未分租户）用全局默认账号
        Map<String, List<AlarmRule>> byTenant = new LinkedHashMap<>();
        for (AlarmRule r : rules) {
            String tid = r.getTenantId() == null ? "" : r.getTenantId();
            byTenant.computeIfAbsent(tid, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<AlarmRule>> e : byTenant.entrySet()) {
            String tenantId = e.getKey().isEmpty() ? null : e.getKey();
            for (AlarmRule rule : e.getValue()) {
                try {
                    scanRule(rule, tenantId);
                } catch (Exception ex) {
                    log.warn("扫描规则 {} 失败（租户 {}）: {}", rule.getId(), tenantId, ex.getMessage());
                }
            }
        }
    }

    /**
     * 评估单个规则（指定租户）。
     * 用该租户的 token 查询其设备、比对遥测，生成告警时写对 tenantId。
     */
    private void scanRule(AlarmRule rule, String tenantId) {
        List<ThingsBoardClient.DeviceBrief> devices = tbClient.listDevicesByType(
                rule.getDeviceType() == null || rule.getDeviceType().isBlank() || "ALL".equalsIgnoreCase(rule.getDeviceType())
                        ? null : rule.getDeviceType(),
                tenantId);
        for (ThingsBoardClient.DeviceBrief dev : devices) {
            boolean hit = evalRule(rule, dev.id(), tenantId);
            if (hit) {
                activate(rule, dev, tenantId);
            } else {
                resolve(rule, dev.id(), tenantId);
            }
        }
    }

    /**
     * 评估单设备是否命中规则。
     * offline 指标：设备无最新遥测即视为断连命中（简化：latestTelemetry 返回 null）；
     * 普通遥测键：解析最新值与阈值比较。
     */
    private boolean evalRule(AlarmRule rule, String deviceId, String tenantId) {
        String metric = rule.getMetric();
        boolean isOffline = "offline".equalsIgnoreCase(metric);
        String raw = tbClient.latestTelemetry(deviceId, isOffline ? "temperature" : metric, tenantId);
        if (raw == null) {
            // 无遥测：对普通指标视为不命中（等设备上报）；对 offline 指标视为命中（断连）
            return isOffline;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        double threshold = rule.getThreshold() == null ? 0.0 : rule.getThreshold();
        String op = rule.getOperator() == null ? "lt" : rule.getOperator();
        switch (op) {
            case "gt": return value > threshold;
            case "eq": return Math.abs(value - threshold) < 1e-6;
            case "ne": return Math.abs(value - threshold) >= 1e-6;
            default:   return value < threshold;
        }
    }

    /** 命中：建/刷新告警记录（tenantId 用规则所属租户，确保隔离） */
    private void activate(AlarmRule rule, ThingsBoardClient.DeviceBrief dev, String tenantId) {
        AlarmRecord target = null;
        Optional<AlarmRecord> exist = recordRepository.findFirstByRuleIdAndDeviceIdAndStatusIn(
                rule.getId(), dev.id(),
                List.of(AlarmRecord.Status.ACTIVE, AlarmRecord.Status.ACKNOWLEDGED));
        if (exist.isPresent()) {
            target = exist.get();
            target.setLastAt(Instant.now().toEpochMilli());
        } else {
            target = new AlarmRecord();
            target.setTenantId(tenantId);
            target.setDeviceId(dev.id());
            target.setDeviceName(dev.name());
            target.setRuleId(rule.getId());
            target.setSeverity(rule.getSeverity());
            String msg = rule.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = rule.getName() + " 触发（" + dev.name() + "）";
            }
            msg = msg.replace("{deviceName}", dev.name()).replace("{deviceId}", dev.id());
            target.setMessage(msg);
            target.setStatus(AlarmRecord.Status.ACTIVE);
        }
        recordRepository.save(target);
    }

    /** 未命中：未恢复记录置 RESOLVED */
    private void resolve(AlarmRule rule, String deviceId, String tenantId) {
        Optional<AlarmRecord> exist = recordRepository.findFirstByRuleIdAndDeviceIdAndStatusIn(
                rule.getId(), deviceId,
                List.of(AlarmRecord.Status.ACTIVE, AlarmRecord.Status.ACKNOWLEDGED));
        exist.ifPresent(record -> {
            record.setStatus(AlarmRecord.Status.RESOLVED);
            record.setResolvedAt(Instant.now().toEpochMilli());
            recordRepository.save(record);
        });
    }
}
