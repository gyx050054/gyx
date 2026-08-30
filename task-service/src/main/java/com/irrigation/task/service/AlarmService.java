/**
 * 【文件职责】
 * 告警引擎业务服务（自研）。
 *  - 规则 CRUD：增删改查 + 启用开关 + 按租户隔离；
 *  - 定时扫描（每30秒，由 AlarmScanScheduler 触发）：读启用规则 → 按租户分组 → 拉设备/最新遥测
 *    → 比较阈值 → 命中建/刷新告警记录，未命中自动恢复（RESOLVED）；
 *  - 告警确认（App 红点点击确认）与未确认告警计数（App 顶栏红点）。
 *
 * 【数据流】
 *  - 下游：AlarmRuleRepository（规则）、AlarmRecordRepository（记录）、ThingsBoardClient
 *    （listDevicesByType 按类型拉设备、latestTelemetry 拉最新遥测）。
 *  - 状态机：ACTIVE(触发) → ACKNOWLEDGED(已确认) → RESOLVED(条件恢复自动结束)。
 *  - 扫描流程（scanAll）：按 tenantId 分组（null 组用全局账号）→ scanRule 逐规则 →
 *    evalRule 判命中 → 命中 activate（写/刷新记录，tenantId 用规则所属租户）/ 未命中 resolve（置 RESOLVED）。
 *  - 租户隔离：listRecords/unreadCount 在 tenantId 为空时返回空列表/0；建告警记录时写入规则所属租户。
 */
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
        this.ruleRepository = ruleRepository; // 注入告警规则仓库，用于规则 CRUD 与扫描读取启用规则
        this.recordRepository = recordRepository; // 注入告警记录仓库，用于生成/刷新/确认/恢复告警记录
        this.tbClient = tbClient; // 注入 TB 客户端，用于按类型拉设备与拉最新遥测
    }

    // ---------- 规则 CRUD ----------

    @Transactional
    public AlarmRule createRule(AlarmRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) { // 规则名称缺失/为空 → 非法
            throw new IllegalArgumentException("规则名称不能为空"); // 抛参数非法异常（Controller 转 400）
        }
        if (rule.getMetric() == null || rule.getMetric().isBlank()) { // 监控指标缺失/为空 → 非法
            throw new IllegalArgumentException("监控指标不能为空"); // 抛参数非法异常（Controller 转 400）
        }
        return ruleRepository.save(rule); // 落库新规则并返回带主键的实体
    }

    @Transactional
    public Optional<AlarmRule> updateRule(Long id, AlarmRule patch) {
        return ruleRepository.findById(id).map(rule -> { // 按 id 查规则；存在则映射更新，不存在则返回空 Optional
            if (patch.getName() != null) rule.setName(patch.getName()); // 仅当补丁非空才覆盖名称
            if (patch.getDeviceType() != null) rule.setDeviceType(patch.getDeviceType()); // 覆盖设备类型
            if (patch.getMetric() != null) rule.setMetric(patch.getMetric()); // 覆盖监控指标
            if (patch.getOperator() != null) rule.setOperator(patch.getOperator()); // 覆盖比较符
            if (patch.getThreshold() != null) rule.setThreshold(patch.getThreshold()); // 覆盖阈值
            if (patch.getSeverity() != null) rule.setSeverity(patch.getSeverity()); // 覆盖告警级别
            if (patch.getMessage() != null) rule.setMessage(patch.getMessage()); // 覆盖告警消息模板
            if (patch.getEnabled() != null) rule.setEnabled(patch.getEnabled()); // 覆盖启用开关
            return ruleRepository.save(rule); // 落库更新后的规则并返回
        });
    }

    @Transactional
    public boolean deleteRule(Long id) {
        if (ruleRepository.existsById(id)) { // 规则存在才删除
            ruleRepository.deleteById(id); // 按 id 删除规则
            return true; // 删除成功
        }
        return false; // 规则不存在 → 返回 false
    }

    /** 切换启用状态 */
    @Transactional
    public Optional<AlarmRule> toggleRule(Long id, boolean enabled) {
        return ruleRepository.findById(id).map(rule -> { // 按 id 查规则；存在则切换，不存在返回空
            rule.setEnabled(enabled); // 设置目标启用状态
            return ruleRepository.save(rule); // 落库并返回
        });
    }

    /** 按租户查规则（App 规则管理页） */
    public List<AlarmRule> listRules(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) { // 无租户语义（演示/扫描）
            return ruleRepository.findByEnabledTrue(); // 演示/无租户：仅返回启用规则供扫描
        }
        return ruleRepository.findByTenantIdOrderByCreatedAtDesc(tenantId); // 按租户查全部规则，创建时间倒序
    }

    // ---------- 告警记录 ----------

    /**
     * 按租户查告警记录，可选按状态过滤（App 告警列表）。
     * 严格租户隔离：tenantId 为空时返回空列表（杜绝"空租户告警互相暴露"的权限 bug）。
     */
    public List<AlarmRecord> listRecords(String tenantId, AlarmRecord.Status status) {
        if (tenantId == null || tenantId.isBlank()) { // 无租户 → 返回空，杜绝跨租户暴露
            return List.of(); // 返回空列表
        }
        if (status != null) { // 指定了状态才按状态过滤
            return recordRepository.findByTenantIdAndStatusOrderByFirstAtDesc(tenantId, status); // 按租户+状态查记录，首次触发时间倒序
        }
        return recordRepository.findByTenantIdOrderByFirstAtDesc(tenantId); // 按租户查全部记录，首次触发时间倒序
    }

    /** 确认告警（App 红点消失）：ACTIVE → ACKNOWLEDGED */
    @Transactional
    public boolean ack(Long id) {
        return recordRepository.findById(id).map(record -> { // 按 id 查记录；存在则确认，不存在返回 false
            if (record.getStatus() == AlarmRecord.Status.ACTIVE) { // 仅当处于 ACTIVE（触发中）才确认
                record.setStatus(AlarmRecord.Status.ACKNOWLEDGED); // 状态机推进：ACTIVE → ACKNOWLEDGED
                recordRepository.save(record); // 落库
            }
            return true; // 找到记录即视为确认成功
        }).orElse(false); // 记录不存在 → 返回 false
    }

    /** 未确认告警计数（App 顶栏红点：ACTIVE 未确认数）；tenantId 为空返回 0（隔离） */
    public long unreadCount(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) { // 无租户 → 隔离返回 0
            return 0L;
        }
        return recordRepository.countByTenantIdAndStatus(tenantId, AlarmRecord.Status.ACTIVE); // 统计该租户 ACTIVE（未确认）告警数
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
        List<AlarmRule> rules = ruleRepository.findByEnabledTrue(); // 读取全部启用规则（作为本轮扫描范围）
        // 规则按 tenantId 分组：null 组（旧数据/未分租户）用全局默认账号
        Map<String, List<AlarmRule>> byTenant = new LinkedHashMap<>(); // 分组容器：tenantId → 该租户的规则列表（保持插入顺序）
        for (AlarmRule r : rules) { // 遍历启用规则
            String tid = r.getTenantId() == null ? "" : r.getTenantId(); // 取规则所属租户，null 归一为 ""（空租户组）
            byTenant.computeIfAbsent(tid, k -> new ArrayList<>()).add(r); // 按租户分组，首次遇到新建列表
        }
        for (Map.Entry<String, List<AlarmRule>> e : byTenant.entrySet()) { // 逐租户扫描
            String tenantId = e.getKey().isEmpty() ? null : e.getKey(); // 空字符串组还原为 null（用全局账号）
            for (AlarmRule rule : e.getValue()) { // 遍历该租户的每条规则
                try {
                    scanRule(rule, tenantId); // 评估该规则下所有设备
                } catch (Exception ex) {
                    log.warn("扫描规则 {} 失败（租户 {}）: {}", rule.getId(), tenantId, ex.getMessage()); // 单条规则异常不影响其它规则
                }
            }
        }
    }

    /**
     * 评估单个规则（指定租户）。
     * 用该租户的 token 查询其设备、比对遥测，生成告警时写对 tenantId。
     */
    private void scanRule(AlarmRule rule, String tenantId) {
        List<ThingsBoardClient.DeviceBrief> devices = tbClient.listDevicesByType( // 按规则设备类型拉设备列表（用该租户 token）
                rule.getDeviceType() == null || rule.getDeviceType().isBlank() || "ALL".equalsIgnoreCase(rule.getDeviceType())
                        ? null : rule.getDeviceType(), // "ALL"/空 → 传 null 表示全部类型
                tenantId);
        for (ThingsBoardClient.DeviceBrief dev : devices) { // 遍历每台设备
            boolean hit = evalRule(rule, dev.id(), tenantId); // 评估该设备是否命中规则
            if (hit) { // 命中 → 建/刷新告警记录
                activate(rule, dev, tenantId);
            } else { // 未命中 → 若存在未恢复记录，置 RESOLVED
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
        String metric = rule.getMetric(); // 取监控指标名（如 temperature / offline）
        boolean isOffline = "offline".equalsIgnoreCase(metric); // 判断是否为「断连」指标
        String raw = tbClient.latestTelemetry(deviceId, isOffline ? "temperature" : metric, tenantId); // 拉最新遥测：offline 指标回退取 temperature，其余取 metric
        if (raw == null) { // 无遥测数据
            // 无遥测：对普通指标视为不命中（等设备上报）；对 offline 指标视为命中（断连）
            return isOffline;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim()); // 把遥测字符串解析为 double（trim 去掉首尾空格）
        } catch (NumberFormatException e) { // 解析失败（非数值数据）
            return false; // 视为不命中
        }
        double threshold = rule.getThreshold() == null ? 0.0 : rule.getThreshold(); // 阈值；未设默认 0
        String op = rule.getOperator() == null ? "lt" : rule.getOperator(); // 比较符；未设默认 lt（小于）
        switch (op) { // 按比较符判定是否命中
            case "gt": return value > threshold;             // 大于
            case "eq": return Math.abs(value - threshold) < 1e-6; // 等于（浮点容差）
            case "ne": return Math.abs(value - threshold) >= 1e-6; // 不等于（浮点容差）
            default:   return value < threshold;             // 小于（默认）
        }
    }

    /** 命中：建/刷新告警记录（tenantId 用规则所属租户，确保隔离） */
    private void activate(AlarmRule rule, ThingsBoardClient.DeviceBrief dev, String tenantId) {
        AlarmRecord target = null; // 目标告警记录（存在则复用，不存在则新建）
        Optional<AlarmRecord> exist = recordRepository.findFirstByRuleIdAndDeviceIdAndStatusIn( // 查该规则+设备最早的未恢复记录（ACTIVE/ACKNOWLEDGED）
                rule.getId(), dev.id(),
                List.of(AlarmRecord.Status.ACTIVE, AlarmRecord.Status.ACKNOWLEDGED));
        if (exist.isPresent()) { // 已有活跃/待确认记录 → 仅刷新最近触发时刻
            target = exist.get(); // 复用旧记录
            target.setLastAt(Instant.now().toEpochMilli()); // 更新最近触发时间
        } else { // 无活跃记录 → 新建一条
            target = new AlarmRecord(); // 新建空记录
            target.setTenantId(tenantId); // 写租户（规则所属租户，确保隔离）
            target.setDeviceId(dev.id()); // 写设备 ID
            target.setDeviceName(dev.name()); // 写设备名称
            target.setRuleId(rule.getId()); // 写规则 ID
            target.setSeverity(rule.getSeverity()); // 写告警级别
            String msg = rule.getMessage(); // 取规则消息模板
            if (msg == null || msg.isBlank()) { // 模板为空 → 用默认文案
                msg = rule.getName() + " 触发（" + dev.name() + "）"; // 默认：规则名+设备名
            }
            msg = msg.replace("{deviceName}", dev.name()).replace("{deviceId}", dev.id()); // 替换占位符 {deviceName}/{deviceId}
            target.setMessage(msg); // 写最终消息
            target.setStatus(AlarmRecord.Status.ACTIVE); // 初始状态 ACTIVE（触发）
        }
        recordRepository.save(target); // 落库（新建或刷新）
    }

    /** 未命中：未恢复记录置 RESOLVED */
    private void resolve(AlarmRule rule, String deviceId, String tenantId) {
        Optional<AlarmRecord> exist = recordRepository.findFirstByRuleIdAndDeviceIdAndStatusIn( // 查该规则+设备最早的未恢复记录
                rule.getId(), deviceId,
                List.of(AlarmRecord.Status.ACTIVE, AlarmRecord.Status.ACKNOWLEDGED));
        exist.ifPresent(record -> { // 存在未恢复记录 → 自动结束
            record.setStatus(AlarmRecord.Status.RESOLVED); // 状态机推进：→ RESOLVED
            record.setResolvedAt(Instant.now().toEpochMilli()); // 记录恢复时刻
            recordRepository.save(record); // 落库
        });
    }
}
