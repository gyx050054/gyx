/**
 * 【文件职责】
 * 每天任务调度服务（第三代第一版 §2）：处理 tasks.repeatMode=DAILY 的常驻闹钟任务。
 *  - 每天到达 dailyHour：开浇（发开阀 RPC）
 *  - 到 durationMinutes：关浇（发关阀 RPC）
 *  - 同日去重：task_runs 按 taskId + runDate 判断，一天只处理一次
 *  - 天气联动：未来 1 小时降雨概率 ≥80% 时当天跳过（记 SKIPPED_WEATHER，不开阀）
 * 由 TaskScanScheduler 每 10 秒调用 processDaily() 触发。
 *
 * 【数据流】
 *  TaskScanScheduler.processDaily() --> 遍历 DAILY(PENDING) 任务 --> processOne()
 *  processOne() 依据当天 task_runs 是否已存在分两支：
 *    未处理且到开浇窗口(now∈[start,start+5min]) --> willRain()：
 *        降雨≥80% → 记 SKIPPED_WEATHER(不开阀)
 *        无雨     --> openValve → 记 COMPLETED(startTs)
 *    已开浇(COMPLETED 且 endTs 为空)且到关浇时间(now≥end) --> closeValve → 补记 endTs
 *  读写 task_runs 表；阀门动作经 ThingsBoardClient(openValve/closeValve) 发 RPC。
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 每天任务调度服务（第三代第一版 §2）
 *
 * 处理 tasks.repeatMode=DAILY 的任务（常驻闹钟）：
 *  - 每天到达 dailyHour 开浇（发开阀 RPC），到 durationMinutes 关浇（发关阀 RPC）
 *  - 同一天同一任务只执行一次（task_runs 按 taskId+runDate 去重）
 *  - 天气联动：预报 1 小时降雨概率 ≥80% 时当天跳过（task_runs 记 SKIPPED_WEATHER，不开阀）
 *  - 手动任务（ONCE）不在本服务内跳过，仅 App 提示
 */
@Service
public class DailyTaskService {

    private static final Logger log = LoggerFactory.getLogger(DailyTaskService.class);

    /** 天气判断阈值：未来1小时降雨概率 ≥80% 视为"要下雨" */
    private static final int RAIN_PROB_THRESHOLD = 80;

    private final TaskRepository taskRepository;
    private final TaskRunRepository taskRunRepository;
    private final ThingsBoardClient tbClient;
    private final WeatherService weatherService;

    /** 天气坐标（暂用默认，与 App 天气板块一致） */
    private static final String LAT = "28.6";
    private static final String LON = "115.9";

    public DailyTaskService(TaskRepository taskRepository,
                            TaskRunRepository taskRunRepository,
                            ThingsBoardClient tbClient,
                            WeatherService weatherService) {
        this.taskRepository = taskRepository;
        this.taskRunRepository = taskRunRepository;
        this.tbClient = tbClient;
        this.weatherService = weatherService;
    }

    /**
     * 处理所有活跃（PENDING）的 DAILY 任务（由 TaskScanScheduler 每 10 秒调用）。
     */
    @Transactional
    public void processDaily() {
        // 读库：查所有「DAILY 且 PENDING」的活跃每天任务 → 列表（数据流：task 表 → 内存），随后逐个处理
        for (Task t : taskRepository.findByRepeatModeAndStatus(Task.RepeatMode.DAILY, Task.Status.PENDING)) {
            // 委托给 processOne：针对单个 DAILY 任务处理「今天」这一个时间点（开浇/关浇/按雨跳过）
            processOne(t);
        }
    }

    /** 处理单个 DAILY 任务当天的一个时间点 */
    private void processOne(Task t) {
        // 参数守卫：DAILY 任务缺少 dailyHour 或 durationMinutes（数据异常）→ 无法计算开浇窗口，直接忽略该任务
        if (t.getDailyHour() == null || t.getDurationMinutes() == null) {
            return; // 提前返回，本轮不处理该 DAILY 任务
        }
        // 取当前系统时间戳（毫秒）作为本轮判断的基准时间（数据流：系统时钟 → now）
        long now = Instant.now().toEpochMilli();
        // 取今天的日期字符串 yyyy-MM-dd（数据流：系统时钟 → todayStr() → runDate），用于 task_runs 按「任务+日期」去重
        String runDate = todayStr();
        // 计算「今天」的 dailyHour 开浇窗口 [startMs,endMs]（数据流：dailyHour/durationMinutes/now → 窗口数组）
        long[] window = dailyWindow(t.getDailyHour(), t.getDurationMinutes(), now);

        // 读库：查该任务当天是否已有执行流水（task_runs 按 taskId+runDate 去重）→ existed 表示今天是否已处理过（数据流：task_runs 表 → 布尔）
        boolean existed = taskRunRepository.findByTaskIdAndRunDate(t.getId(), runDate).isPresent();

        // 分支：今天尚未处理 → 走「是否到点开浇」判断；今天已处理 → 跳到下方「到时关浇」逻辑
        if (!existed) {
            // 当天尚未处理：判断是否到开浇窗口（dailyHour 起，允许 5 分钟窗口内触发）
            if (now >= window[0] && now <= window[0] + 5 * 60_000L) {
                // 天气联动判断：未来1小时降雨概率是否≥80%（数据流：WeatherService.current → rain）
                boolean rain = willRain();
                TaskRun run = new TaskRun();                       // 新建当天执行流水实体（未落库，暂存内存）
                run.setTaskId(t.getId());                          // 关联任务 ID（数据流：任务 → 流水）
                run.setDeviceId(t.getDeviceId());                  // 冗余设备 ID，供 App 展示
                run.setDeviceName(t.getDeviceName());              // 冗余设备名称，供 App 展示
                run.setRunDate(runDate);                           // 记录运行日期（当天），用于按日去重
                run.setAction(t.getAction());                      // 记录本次浇灌动作（on/off）
                // 分支：降雨概率≥80% → 记跳过（不开阀）；否则正常开浇
                if (rain) {
                    // 降雨≥80% → 跳过，不开阀
                    run.setStartTs(now);                             // 记录跳过判断时刻（不开阀）
                    run.setStatus(TaskRun.Status.SKIPPED_WEATHER);   // 标记为「因降雨跳过」，供 App 展示当天未浇的原因
                    log.info("每日任务 {} 因降雨概率≥{}% 跳过（{}）", t.getId(), RAIN_PROB_THRESHOLD, runDate);
                } else {  // 无雨 → 正常开浇（发开阀）
                    // 正常开浇
                    boolean ok = openOrClose(t, true); // 发开阀 RPC（open=true 开阀），返回是否下发成功（数据流：任务 → tbClient → 设备）
                    run.setStartTs(now);                        // 记录开浇时刻（成功与否均记录时间点）
                    run.setStatus(TaskRun.Status.COMPLETED);    // 标记当天已开浇（endTs 留空，待到时关浇时补填）
                    log.info("每日任务 {} 开浇 {}：{}", t.getId(), t.getDeviceId(), ok ? "成功" : "失败");
                }
                // 落库：save 把当天执行流水 INSERT 进 task_runs 表（数据流：对象 → task_runs 表）
                taskRunRepository.save(run);
            }
            // 当天尚未处理分支到此结束：无论是否到点/是否处理，均直接返回，避免误入下方「已开浇再去关浇」分支
            return;
        }

        // 当天已有流水：若开过浇但未记录关闭且已到结束时间 → 发关阀
        // 读库：取当天已存在的那条流水实体（上文已确认 existed=true，故 get() 安全；数据流：task_runs 表 → 实体）
        TaskRun run = taskRunRepository.findByTaskIdAndRunDate(t.getId(), runDate).get();
        // 判断关浇条件：当天已开浇(COMPLETED) 且 尚未记录关浇(endTs 为空) 且 已到关浇时刻(now≥窗口结束) → 发关阀
        if (run.getStatus() == TaskRun.Status.COMPLETED && run.getEndTs() == null && now >= window[1]) {
            openOrClose(t, false); // 发关阀 RPC（open=false 关阀），关闭设备（数据流：任务 → tbClient → 设备）
            run.setEndTs(now);     // 补记关浇时刻，标记当天流水已完整（开+关）
            // 落库：save 把补记的 endTs 写回 task_runs 表（数据流：对象 → task_runs 表）
            taskRunRepository.save(run);
            // 记录关浇日志（任务 ID、设备 ID）
            log.info("每日任务 {} 到时关浇 {}", t.getId(), t.getDeviceId());
        }
    }

    /** 发开/关阀 RPC */
    private boolean openOrClose(Task t, boolean open) {
        // 判断：open=true → 开阀；open=false → 关阀（数据流：任务动作 → tbClient → 设备）
        if (open) {
            return tbClient.openValve(t.getDeviceId());   // 开阀 RPC：返回是否下发成功
        }
        return tbClient.closeValve(t.getDeviceId());      // 关阀 RPC：返回是否下发成功
    }

    /** 当天是否预报降雨概率≥80% */
    private boolean willRain() {
        // 用 try-catch 包裹：天气服务异常时按「不下雨」兜底，不影响开浇流程（仅记 warn）
        try {
            // 调天气服务查当前坐标(LAT,LON)的预报结果（数据流：WeatherService.current → WeatherResult）
            WeatherService.WeatherResult r = weatherService.current(LAT, LON);
            // 判断：结果非空 且 未来1小时降雨概率非空 且 ≥阈值(80%) → 视为「要下雨」，返回 true
            return r != null && r.precipProb1h() != null && r.precipProb1h() >= RAIN_PROB_THRESHOLD;
        } catch (Exception e) {
            // 异常兜底：天气接口异常记 warn 日志，返回 false（按不下雨处理）
            log.warn("天气联动判断失败，按不下雨处理：{}", e.getMessage());
            return false;
        }
    }

    /** 今天的日期（yyyy-MM-dd） */
    private String todayStr() {
        // 用系统时区把当前时间格式化为 yyyy-MM-dd（数据流：系统时钟 → Instant.now → 日期字符串）
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
    }

    /** 每天 dailyHour 的毫秒窗口 */
    private long[] dailyWindow(int dailyHour, int durationMinutes, long referenceTs) {
        // 把参考时间戳（毫秒）转成系统时区的 ZonedDateTime（数据流：referenceTs → 时区化时间对象）
        java.time.ZonedDateTime ref = java.time.ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(referenceTs), ZoneId.systemDefault());
        // 取 ref 所在自然日的 00:00，再加 dailyHour 小时 → 得到当天开浇起始时刻
        java.time.ZonedDateTime start = ref.toLocalDate().atStartOfDay(ZoneId.systemDefault()).plusHours(dailyHour);
        long startMs = start.toInstant().toEpochMilli();  // 起始毫秒时间戳
        long endMs = startMs + durationMinutes * 60_000L; // 结束毫秒 = 起始 + durationMinutes 分钟（1 分钟 = 60000ms）
        return new long[]{startMs, endMs};                // 返回 [startMs,endMs] 开浇窗口
    }
}
