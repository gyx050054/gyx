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
        for (Task t : taskRepository.findByRepeatModeAndStatus(Task.RepeatMode.DAILY, Task.Status.PENDING)) {
            processOne(t);
        }
    }

    /** 处理单个 DAILY 任务当天的一个时间点 */
    private void processOne(Task t) {
        if (t.getDailyHour() == null || t.getDurationMinutes() == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        String runDate = todayStr();
        long[] window = dailyWindow(t.getDailyHour(), t.getDurationMinutes(), now);

        boolean existed = taskRunRepository.findByTaskIdAndRunDate(t.getId(), runDate).isPresent();

        if (!existed) {
            // 当天尚未处理：判断是否到开浇窗口（dailyHour 起，允许 5 分钟窗口内触发）
            if (now >= window[0] && now <= window[0] + 5 * 60_000L) {
                boolean rain = willRain();
                TaskRun run = new TaskRun();
                run.setTaskId(t.getId());
                run.setDeviceId(t.getDeviceId());
                run.setDeviceName(t.getDeviceName());
                run.setRunDate(runDate);
                run.setAction(t.getAction());
                if (rain) {
                    // 降雨≥80% → 跳过，不开阀
                    run.setStartTs(now);
                    run.setStatus(TaskRun.Status.SKIPPED_WEATHER);
                    log.info("每日任务 {} 因降雨概率≥{}% 跳过（{}）", t.getId(), RAIN_PROB_THRESHOLD, runDate);
                } else {
                    // 正常开浇
                    boolean ok = openOrClose(t, true);
                    run.setStartTs(now);
                    run.setStatus(TaskRun.Status.COMPLETED);
                    log.info("每日任务 {} 开浇 {}：{}", t.getId(), t.getDeviceId(), ok ? "成功" : "失败");
                }
                taskRunRepository.save(run);
            }
            return;
        }

        // 当天已有流水：若开过浇但未记录关闭且已到结束时间 → 发关阀
        TaskRun run = taskRunRepository.findByTaskIdAndRunDate(t.getId(), runDate).get();
        if (run.getStatus() == TaskRun.Status.COMPLETED && run.getEndTs() == null && now >= window[1]) {
            openOrClose(t, false);
            run.setEndTs(now);
            taskRunRepository.save(run);
            log.info("每日任务 {} 到时关浇 {}", t.getId(), t.getDeviceId());
        }
    }

    /** 发开/关阀 RPC */
    private boolean openOrClose(Task t, boolean open) {
        if (open) {
            return tbClient.openValve(t.getDeviceId());
        }
        return tbClient.closeValve(t.getDeviceId());
    }

    /** 当天是否预报降雨概率≥80% */
    private boolean willRain() {
        try {
            WeatherService.WeatherResult r = weatherService.current(LAT, LON);
            return r != null && r.precipProb1h() != null && r.precipProb1h() >= RAIN_PROB_THRESHOLD;
        } catch (Exception e) {
            log.warn("天气联动判断失败，按不下雨处理：{}", e.getMessage());
            return false;
        }
    }

    /** 今天的日期（yyyy-MM-dd） */
    private String todayStr() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
    }

    /** 每天 dailyHour 的毫秒窗口 */
    private long[] dailyWindow(int dailyHour, int durationMinutes, long referenceTs) {
        java.time.ZonedDateTime ref = java.time.ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(referenceTs), ZoneId.systemDefault());
        java.time.ZonedDateTime start = ref.toLocalDate().atStartOfDay(ZoneId.systemDefault()).plusHours(dailyHour);
        long startMs = start.toInstant().toEpochMilli();
        long endMs = startMs + durationMinutes * 60_000L;
        return new long[]{startMs, endMs};
    }
}
