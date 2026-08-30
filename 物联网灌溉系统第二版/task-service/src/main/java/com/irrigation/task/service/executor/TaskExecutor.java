package com.irrigation.task.service.executor;

import com.irrigation.task.entity.Task;

/**
 * 任务执行器接口（为未来铺垫的关键抽象）
 *
 * 职责：真正"执行"一个任务（开启/关闭阀门）。
 *
 * 当前实现：{@link RpcTaskExecutor} —— 同步调 ThingsBoard RPC。
 * 未来演进（无需改动调用方）：
 *  - 高并发：替换为线程池异步执行；
 *  - 引入消息队列：替换为向 Kafka/RabbitMQ 投递消息，由消费者执行；
 *  - 失败重试/死信：可在实现内扩展重试计数与告警钩子。
 *
 * 调用方 {@link com.irrigation.task.service.TaskScanScheduler} 只依赖本接口，
 * 与具体执行方式彻底解耦（依赖倒置，高内聚低耦合）。
 */
public interface TaskExecutor {

    /**
     * 执行任务的"开始"动作（到点开启/关闭阀门）
     *
     * @param task 到点任务（PENDING → 待执行）
     * @return true = 执行成功（任务可置 RUNNING）；false = 失败（保留 PENDING 下轮重试）
     */
    boolean executeStart(Task task);

    /**
     * 执行任务的"结束"动作（到点自动关闭阀门，收尾逻辑）
     *
     * @param task 超时任务（RUNNING → 待完成）
     */
    void executeFinish(Task task);
}
