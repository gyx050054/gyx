/**
 * 【文件职责】
 * 任务业务服务（由原 TaskSchedulerService 拆分出的「业务」部分）：
 *  - 创建任务：单个/批量（含冲突检测、时间参数校验、DAILY 每天任务参数规则）
 *  - 取消任务：软删除置 CANCELLED（PENDING 直接取消；RUNNING 先发 pauseValve 暂停设备再取消）
 *  - 查询：任务列表（多租户可隔离）/ 执行流水 task_runs
 *  - 每天任务参数规则：dailyHour(0-23) + durationMinutes(>0) 校验，并用 daily 窗口落库 initial 占位
 * 本类只做「任务数据 + 业务规则」，不关心「何时执行」；「执行」由 TaskScanScheduler（定时扫描）+ TaskExecutor（执行动作）负责。
 *
 * 【数据流】
 *  Controller(入口) → TaskService(创建/取消/查询) → TaskRepository(读写 task 表) / TaskRunRepository(查 task_runs)
 *                      ↘ ThingsBoardClient：取消运行中任务时发 pauseValve 暂停设备
 *  createTask/取消/批量 落库 → task 表（新建置 PENDING）
 *  task 表的状态流转（PENDING→RUNNING→COMPLETED）由 TaskScanScheduler 消费本服务产出的数据完成；
 *  本服务只在「创建/取消」时写状态，与执行调度解耦。
 */
package com.irrigation.task.service;

import com.irrigation.task.entity.Task;
import com.irrigation.task.entity.TaskRun;
import com.irrigation.task.repository.TaskRepository;
import com.irrigation.task.repository.TaskRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务业务服务（由原 TaskSchedulerService 拆分出的「业务」部分）
 *
 * 职责（对应需求文档「微服务端定时任务完整执行流程」①②③⑥⑦）：
 *  ① 创建任务（单个/批量，含冲突检测）
 *  ② 取消任务（软删除：置 CANCELLED；运行中先发 pauseValve 暂停）
 *  ③ 查询任务列表
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类只做「任务数据 + 业务规则」，不关心「何时执行」；
 *  - 「执行」由 {@link TaskScanScheduler}（定时扫描）+ {@link executor.TaskExecutor}（执行动作）负责；
 *  - 未来若引入消息队列/线程池，只需替换 TaskExecutor 实现，本类与 Controller 均无需改动。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 默认动作：on = 开启阀门（动作默认值只在本类收敛，避免散落多处） */
    public static final String DEFAULT_ACTION = "on";

    private final TaskRepository taskRepository;
    private final ThingsBoardClient tbClient;
    private final TaskRunRepository taskRunRepository;

    public TaskService(TaskRepository taskRepository, ThingsBoardClient tbClient,
                       TaskRunRepository taskRunRepository) {
        this.taskRepository = taskRepository;
        this.tbClient = tbClient;
        this.taskRunRepository = taskRunRepository;
    }

    /**
     * 校验单个设备在 [startTime, endTime) 时段内是否已有任务（冲突检测）
     * 冲突条件：该设备存在 PENDING/RUNNING 任务，且时间段有交集（newStart < oldEnd && newEnd > oldStart）
     *
     * @return true = 存在冲突（不能创建）
     */
    public boolean hasConflict(String deviceId, Long startTime, Long endTime) {
        // 读库：调仓储层冲突查询，按 deviceId 在 [startTime, endTime) 区间内查出所有 PENDING/RUNNING 任务 → 列表（数据流：task 表 → 内存）
        // 判断：列表非空说明存在时间交集的任务（newStart < oldEnd && newEnd > oldStart）→ 返回 true 表示有冲突；为空则无冲突返回 false
        return !taskRepository.findConflicts(deviceId, startTime, endTime).isEmpty();
    }

    /**
     * 创建单个任务
     *
     * @param deviceId   ThingsBoard 设备 ID（必填）
     * @param deviceName 设备名称（冗余字段，供 APP 展示；缺省用 deviceId）
     * @param startTime  开始时间戳（毫秒，必填）
     * @param endTime    结束时间戳（毫秒，必填，须大于 startTime）
     * @param action     on=开启 / off=关闭；null 时取默认 {@link #DEFAULT_ACTION}
     * @return 新建任务；若时间冲突则返回 null（调用方据此提示"冲突拒绝"）
     * @throws IllegalArgumentException 时间参数非法时抛出（由全局异常处理转 400）
     */
    @Transactional
    public Task createTask(String deviceId, String deviceName, Long startTime, Long endTime, String action,
                           String tenantId) {
        // 兼容原一次性调用（ONCE）
        // 委托给完整签名版本：把 repeatMode 固定为 ONCE，dailyHour/durationMinutes 传 null → 走 ONCE 分支，避免重复实现
        return createTask(deviceId, deviceName, startTime, endTime, action, tenantId,
                Task.RepeatMode.ONCE, null, null);
    }

    /**
     * 创建任务（支持一次性 ONCE / 每天 DAILY）
     *
     * DAILY 参数：repeatMode=DAILY + dailyHour(0-23) + durationMinutes(>0)；
     *  落库时用当天 dailyHour 的窗口作为 startTime/endTime 的 initial（设计文档 §2.2），真正的重复由 dailyHour 驱动。
     * ONCE 沿用 startTime/endTime。
     *
     * @return 新建任务；若冲突则返回 null（调用方据此提示"冲突拒绝"）
     */
    @Transactional
    public Task createTask(String deviceId, String deviceName, Long startTime, Long endTime, String action,
                           String tenantId, Task.RepeatMode repeatMode, Integer dailyHour, Integer durationMinutes) {
        // 归一化模式：入参 repeatMode 为 null 时默认按一次性 ONCE 处理，非 null 则原样保留（数据流：入参 → 局部变量 mode）
        Task.RepeatMode mode = repeatMode == null ? Task.RepeatMode.ONCE : repeatMode;
        // 分支判断：mode 为 DAILY（每天闹钟）走专属「参数校验 + 冲突检测 + 窗口占位落库」逻辑
        if (mode == Task.RepeatMode.DAILY) {
            // DAILY：参数校验 + 同设备去重/活跃任务冲突
            // DAILY 参数校验：dailyHour 必须在 [0,23]、durationMinutes 必须 >0，任一不满足即抛 IllegalArgumentException（由全局异常处理转 400）
            if (dailyHour == null || dailyHour < 0 || dailyHour > 23 || durationMinutes == null || durationMinutes <= 0) {
                throw new IllegalArgumentException("每日任务参数非法：dailyHour(0-23)/durationMinutes(>0) 必填");
            }
            // 冲突检测：同设备已存在活跃 DAILY 或 RUNNING 任务 → 拒建（同一设备同一时刻只允许一个浇灌动作）
            if (hasDailyConflict(deviceId)) {
                // 命中冲突：记录告警日志（入参 deviceId 由调用方传入）→ 返回 null，调用方据此提示“冲突拒绝”，不落库
                log.warn("每日任务冲突被拒：deviceId={}", deviceId);
                return null;
            }
            // 以当天 dailyHour 窗口作为 initial 时间占位（DB 非空约束），真正的重复由 dailyHour 驱动
            // 时间窗口计算：用「今天」的 dailyHour 起算，返回 [startMs, endMs] 两个毫秒值（数据流：dailyHour/durationMinutes → 窗口数组）
            long[] window = dailyWindow(dailyHour, durationMinutes);
            Task t = new Task(); // 新建任务实体（未落库，暂存内存）
            t.setDeviceId(deviceId);                                  // 写入设备 ID（ThingsBoard 设备标识）
            t.setDeviceName(deviceName == null ? deviceId : deviceName); // 设备名称冗余字段，缺省回退为 deviceId
            t.setStartTime(window[0]);                                // 记录 DAILY 窗口起始毫秒（当天 dailyHour 时刻）
            t.setEndTime(window[1]);                                  // 记录 DAILY 窗口结束毫秒（dailyHour + durationMinutes 时刻）
            t.setAction(action == null ? DEFAULT_ACTION : action);    // 动作：null 时取默认“on”开启
            t.setRepeatMode(Task.RepeatMode.DAILY);                   // 标记为每天任务
            t.setDailyHour(dailyHour);                                // 每天执行的小时（0-23）
            t.setDurationMinutes(durationMinutes);                    // 每天持续分钟数
            t.setStatus(Task.Status.PENDING); // DAILY 常驻 PENDING = 闹钟挂起
            t.setTenantId(tenantId);                                  // 任务归属租户（多租户隔离）
            // 落库：save 把内存中的任务对象 INSERT 进 task 表，并回填自增 ID 后返回（数据流：对象 → task 表 → 带 ID 实体）
            return taskRepository.save(t);
        }
        // ONCE：原逻辑
        // ONCE 时间参数校验：startTime/endTime 必填且 endTime 必须大于 startTime，非法即抛 IllegalArgumentException（由全局异常处理转 400）
        if (startTime == null || endTime == null || endTime <= startTime) {
            throw new IllegalArgumentException("时间参数非法：startTime/endTime 必填且 endTime > startTime");
        }
        // 冲突检测（两层）：与该设备活跃 DAILY 每日窗口重叠（dailyOverlapsOnce）或存在 PENDING/RUNNING 同时间段冲突（hasConflict），任一命中即拒建
        if (dailyOverlapsOnce(deviceId, startTime, endTime) || hasConflict(deviceId, startTime, endTime)) {
            // 命中冲突：记录告警日志（含设备与时间区间）→ 返回 null，调用方提示“冲突拒绝”，不落库
            log.warn("任务冲突被拒：deviceId={} [{},{}]", deviceId, startTime, endTime);
            return null;
        }
        Task t = new Task(); // 新建任务实体（未落库，暂存内存）
        t.setDeviceId(deviceId);                                  // 写入设备 ID（ThingsBoard 设备标识）
        t.setDeviceName(deviceName == null ? deviceId : deviceName); // 设备名称冗余字段，缺省回退为 deviceId
        t.setStartTime(startTime);                                // 开始时间戳（毫秒，来自入参）
        t.setEndTime(endTime);                                    // 结束时间戳（毫秒，来自入参）
        t.setAction(action == null ? DEFAULT_ACTION : action);    // 动作：null 时取默认“on”开启
        t.setRepeatMode(Task.RepeatMode.ONCE);                    // 标记为一次性任务
        t.setStatus(Task.Status.PENDING); // 新建任务固定为等待执行
        t.setTenantId(tenantId);          // 第二版多租户：任务归属租户（APP 从 JWT 解析）
        // 落库：save 把内存中的任务对象 INSERT 进 task 表，并回填自增 ID 后返回（数据流：对象 → task 表 → 带 ID 实体）
        return taskRepository.save(t);
    }

    /**
     * DAILY 任务冲突检测：同设备不允许有另一条活跃 DAILY 或其他 RUNNING 一次性任务
     * （每日固定时段常驻，同一设备同一时刻只允许一个浇灌动作）
     */
    private boolean hasDailyConflict(String deviceId) {
        // 读库：查该设备全部任务 → 列表（数据流：task 表 → 内存），随后遍历判断是否构成冲突
        for (Task t : taskRepository.findByDeviceId(deviceId)) {
            // 判断：设备上存在 RUNNING 任务（正在执行的一次性任务）→ 判定冲突
            if (t.getStatus() == Task.Status.RUNNING) {
                return true; // 正在执行的一次性任务
            }
            // 判断：设备上已有一条活跃 DAILY（PENDING）每天任务 → 同设备同时刻只允许一个浇灌动作，判定冲突
            if (t.getRepeatMode() == Task.RepeatMode.DAILY && t.getStatus() == Task.Status.PENDING) {
                return true; // 已有活跃每天任务
            }
        }
        // 遍历完未命中任何冲突条件 → 允许创建，返回 false
        return false;
    }

    /**
     * 一次性任务与同设备活跃 DAILY 任务每日窗口是否重叠。
     * 若重叠，一次性任务相应时间段的浇灌会与每天任务冲突，拒建。
     */
    private boolean dailyOverlapsOnce(String deviceId, Long startTime, Long endTime) {
        // 查找该设备活跃 DAILY 任务（PENDING）
        Task daily = null; // 局部引用：用于持有遍历到的活跃 DAILY 任务，初始为 null（默认未找到）
        // 读库：查该设备全部任务 → 列表（数据流：task 表 → 内存），遍历定位活跃 DAILY 任务
        for (Task t : taskRepository.findByDeviceId(deviceId)) {
            // 命中“DAILY 且 PENDING”即该设备的活跃每天任务：记录后提前结束循环
            if (t.getRepeatMode() == Task.RepeatMode.DAILY && t.getStatus() == Task.Status.PENDING) {
                daily = t;
                break;
            }
        }
        // 判断：未找到活跃 DAILY 任务，或该任务缺少 dailyHour 参数 → 无法计算窗口，视为无重叠，返回 false
        if (daily == null || daily.getDailyHour() == null) {
            return false;
        }
        long dailyStart = daily.getStartTime(); // initial 当天窗口（dailyHour 起点）
        long dailyEnd = daily.getEndTime();
        // 检查 [startTime,endTime) 是否落在同一设备 DAILY 的某天窗口内：取 DAILY 窗口所在日
        // 重算窗口：以该 DAILY 的 dailyHour + durationMinutes，基于 startTime 所在自然日恢复出当天 [startMs,endMs] 窗口（durationMinutes 为 null 时按 0 处理）
        long[] win = dailyWindowAt(daily.getDailyHour(), daily.getDurationMinutes() == null ? 0 : daily.getDurationMinutes(), startTime);
        // 区间重叠判断：一次性任务开始早于 DAILY 窗口结束 && 一次性任务结束晚于 DAILY 窗口开始 → 时间重叠，返回 true（否则 false）
        return startTime < win[1] && win[0] < endTime;
    }

    /** 计算 dailyHour + durationMinutes 在「今天」的毫秒窗口（用于 initial 占位） */
    private long[] dailyWindow(int dailyHour, int durationMinutes) {
        // 取当前系统时间戳（毫秒）作为参考点（数据流：系统时钟 → now），用于对齐“今天”这个自然日
        long now = Instant.now().toEpochMilli();
        // 委托给 dailyWindowAt：以 now 所在自然日为基准计算 dailyHour 窗口（返回 [startMs,endMs]）
        return dailyWindowAt(dailyHour, durationMinutes, now);
    }

    /** 计算 dailyHour 窗口：以 reference 所在自然日起算 */
    private long[] dailyWindowAt(int dailyHour, int durationMinutes, long referenceTs) {
        // 把参考时间戳（毫秒）转换成系统时区下的 ZonedDateTime（数据流：referenceTs → 时区化时间对象）
        java.time.ZonedDateTime ref = java.time.ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(referenceTs), java.time.ZoneId.systemDefault());
        // 取 ref 所在自然日的 00:00，再加 dailyHour 小时 → 得到当天 dailyHour 时刻（浇灌起始点）
        java.time.ZonedDateTime start = ref.toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).plusHours(dailyHour);
        // 将窗口起始时刻换算成毫秒时间戳（startMs）
        long startMs = start.toInstant().toEpochMilli();
        // 结束毫秒 = 起始毫秒 + durationMinutes 分钟对应的毫秒数（1 分钟 = 60000ms）
        long endMs = startMs + durationMinutes * 60_000L;
        // 返回 [startMs, endMs] 二元窗口数组，供调用方作为 DAILY 浇灌时段（落库 initial 或冲突重叠判断）
        return new long[]{startMs, endMs};
    }

    /**
     * 批量创建任务（多选设备场景，需求文档 ⑤）
     *
     * 规则：先对全部设备做整体冲突预检，任一设备冲突则整体拒绝（全部不创建）；
     *      预检通过后逐个创建，返回成功创建的列表。
     *
     * @param tasks 每个元素为单条任务的五元组（deviceId/deviceName/startTime/endTime/action）
     * @return 成功创建的任务列表（长度 ≤ 入参数量）
     */
    @Transactional
    public List<Task> createTasks(List<TaskDraft> tasks) {
        // 整体冲突预检：任一设备冲突 → 全部拒绝（文档 ⑤「有冲突则所有任务不让添加」）
        // 遍历每个草稿（入参列表 → 内存），先做冲突检测，任一命中即整体失败（不落库任何一条）
        for (TaskDraft d : tasks) {
            // 冲突检测：该设备在 [startTime,endTime) 区间是否已有 PENDING/RUNNING 任务
            if (hasConflict(d.deviceId(), d.startTime(), d.endTime())) {
                // 命中冲突：记录告警日志（含设备与时间区间）→ 返回空列表，整体拒绝，调用方据此提示“全部不添加”
                log.warn("批量任务冲突被拒：设备 {} 时段 [{},{}]", d.deviceId(), d.startTime(), d.endTime());
                return List.of();
            }
        }
        // 预检通过，逐个创建
        List<Task> created = new ArrayList<>(); // 结果容器：用于累积成功创建的任务实体
        // 逐个创建（入参列表 → 内存循环），每一条都委托给单条 createTask（带 @Transactional，整体在一个事务内）
        for (TaskDraft d : tasks) {
            // 调用单条创建，把新建 Task 追加进结果列表（数据流：草稿 → Task 实体 → created 列表）
            created.add(createTask(d.deviceId(), d.deviceName(), d.startTime(), d.endTime(), d.action(),
                    d.tenantId()));
        }
        // 返回成功创建的任务列表（长度 ≤ 入参数量；若中途某条冲突在单条层返回 null，则此列表会出现 null，供调用方过滤）
        return created;
    }

    /**
     * 取消任务（软删除，需求文档 ⑥）：置 CANCELLED，不物理删除
     *  - PENDING（未开始）：直接置 CANCELLED
     *  - RUNNING（进行中）：先发 pauseValve 暂停设备，再置 CANCELLED
     *  - COMPLETED / CANCELLED：终态，不可再取消
     *
     * @return true = 取消成功；false = 任务不存在或处于终态
     */
    @Transactional
    public boolean cancelTask(Long taskId) {
        // 读库：按主键查任务；缺失时 orElse(null) 把 Optional 转成 null（数据流：task 表 → Optional → 实体或 null）
        Task t = taskRepository.findById(taskId).orElse(null);
        // 判断：任务不存在 → 无法取消，返回 false（调用方据此提示“任务不存在”）
        if (t == null) {
            return false;
        }
        // 终态不可取消（文档：保持原状态）
        // 判断：任务已处于 COMPLETED 或 CANCELLED（终态）→ 状态不可再回退，记录日志并返回 false（保持原状态，不修改）
        if (t.getStatus() == Task.Status.COMPLETED || t.getStatus() == Task.Status.CANCELLED) {
            log.info("任务 {} 状态为 {}（终态），不可取消", taskId, t.getStatus());
            return false;
        }
        // 运行中任务先发暂停指令，避免设备继续工作（文档 ⑦）
        // 判断：任务为 RUNNING → 先向 ThingsBoard 发 pauseValve RPC 暂停设备，再进入取消流程（数据流：taskId → tbClient → 设备）
        if (t.getStatus() == Task.Status.RUNNING) {
            tbClient.pauseValve(t.getDeviceId());
            log.info("取消运行中任务 {} -> 已发 pauseValve 暂停设备 {}", taskId, t.getDeviceId());
        }
        // 状态流转：将任务状态改为 CANCELLED（软删除，不物理删除）
        t.setStatus(Task.Status.CANCELLED);
        // 落库：save 把更新后的状态写回 task 表（数据流：内存对象 → task 表）
        taskRepository.save(t);
        log.info("任务 {} 已取消（状态 CANCELLED）", taskId);
        // 返回 true 表示取消成功
        return true;
    }

    /**
     * 按设备取消其全部未完成任务（第二版新增：APP 删除设备前调用）
     *  - RUNNING 的任务先发 pauseValve 暂停设备，再置 CANCELLED；
     *  - 已完成/已取消的终态任务不受影响（保留历史）。
     *
     * @param deviceId ThingsBoard 设备 ID
     * @return 被取消的任务数量
     */
    @Transactional
    public int cancelTasksByDevice(String deviceId) {
        // 只处理未完成任务（PENDING/RUNNING），终态任务保留
        // 读库：按设备 + 状态(PENDING/RUNNING) 查询该设备全部未完成任务 → 列表（数据流：task 表 → 内存）
        List<Task> active = taskRepository.findByDeviceIdAndStatusIn(
                deviceId, List.of(Task.Status.PENDING, Task.Status.RUNNING));
        // 遍历未完成任务列表（内存循环），逐条处理
        for (Task t : active) {
            // 判断：任务为 RUNNING → 先向 ThingsBoard 发 pauseValve RPC 暂停设备，再进入取消流程
            if (t.getStatus() == Task.Status.RUNNING) {
                tbClient.pauseValve(deviceId);
                log.info("删除设备级联取消：任务 {} 运行中，已发 pauseValve 暂停", t.getId());
            }
            // 状态流转：将任务状态改为 CANCELLED（软删除；已完成/已取消的终态任务不在此列表，故不受影响）
            t.setStatus(Task.Status.CANCELLED);
        }
        // 一次性落库：saveAll 把列表内所有改过的任务批量写回 task 表（数据流：内存列表 → task 表）
        taskRepository.saveAll(active);
        log.info("删除设备级联取消：deviceId={} 共取消 {} 条任务", deviceId, active.size());
        // 返回被取消的任务数量（即 active 列表大小）
        return active.size();
    }

    /**
     * 查询全部任务（任务管理页：含已完成/已取消，保留记录供展示）
     *
     * @param tenantId 租户 ID；非 null 时只返回该租户的任务（第二版多租户隔离，第一版传 null 查全部）
     */
    public List<Task> listAll(String tenantId) {
        // 判断：tenantId 为 null（第一版，不区分租户）→ 查询全部任务
        if (tenantId == null) {
            return taskRepository.findAll(); // 读库：查全部任务（含已完成/已取消）
        }
        // 否则（第二版多租户隔离）：按租户查询，只返回该租户的任务
        return taskRepository.findByTenantId(tenantId);
    }

    /**
     * 查询某任务的全部执行流水（每天任务 task_runs：App 展示"昨天浇没浇/是否因雨跳过"）
     */
    public List<TaskRun> listRuns(Long taskId) {
        // 读库：按任务 ID 查询其全部执行流水，按运行日期倒序排序（数据流：task_runs 表 → 列表）
        return taskRunRepository.findByTaskIdOrderByRunDateDesc(taskId);
    }

    /**
     * 批量创建任务的入参（轻量 DTO：避免 Controller 直接依赖 JsonNode）
     * 使用 record：Java 17 简洁不可变数据结构
     */
    public record TaskDraft(String deviceId, String deviceName, Long startTime, Long endTime, String action,
                            String tenantId) {
    }
}
