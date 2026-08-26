package com.irrigation.task.service.executor;

import com.irrigation.task.entity.Task;
import com.irrigation.task.service.ThingsBoardClient;
import org.springframework.stereotype.Component;

/**
 * 默认任务执行器：通过 ThingsBoard RPC 同步执行任务
 *
 * 实现 {@link TaskExecutor} 接口：
 *  - executeStart：按任务 action 下发 setValveState（on=开 / off=关）
 *  - executeFinish：到期自动关闭阀门
 *
 * 后续如需异步/消息队列，新增一个实现类替换本类即可（Spring 按接口注入，
 * 用 @Primary 或限定名选择），调度代码无需改动。
 */
@Component
public class RpcTaskExecutor implements TaskExecutor {

    private final ThingsBoardClient tbClient;

    public RpcTaskExecutor(ThingsBoardClient tbClient) {
        this.tbClient = tbClient;
    }

    @Override
    /**
     * 执行任务开始动作：按任务 action 下发阀门控制（off=关闭，其余=开启）
     * @param task 到点任务
     * @return true=RPC 下发成功（任务置 RUNNING）；false=失败（保留 PENDING 重试）
     */
    public boolean executeStart(Task task) {
        // 任务动作：off=关闭，其余一律视为开启（动作默认值已在 TaskService 收敛为 on/off）
        if ("off".equalsIgnoreCase(task.getAction())) {
            return tbClient.closeValve(task.getDeviceId());
        }
        return tbClient.openValve(task.getDeviceId());
    }

    @Override
    /**
     * 执行任务收尾：到期自动关闭阀门（忽略返回，任务已转 COMPLETED 不再重复处理）
     * @param task 超时任务
     */
    public void executeFinish(Task task) {
        // 到期收尾：自动关闭阀门（忽略返回值，下一轮扫描不会重复处理该任务）
        tbClient.closeValve(task.getDeviceId());
    }
}
