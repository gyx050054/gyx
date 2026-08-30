/**
 * 【文件职责】
 * 任务执行器接口（为未来铺垫的关键抽象）：真正「执行」一个任务的开启/关闭阀门动作。
 * 只声明两个动作：
 *  - executeStart(task)：到点执行「开始」动作（按 action 开/关阀）
 *  - executeFinish(task)：到期执行「结束」动作（收尾关阀）
 * 当前默认实现为 RpcTaskExecutor（同步调 ThingsBoard RPC）；未来可替换为线程池异步/消息队列实现，调用方无需改动。
 *
 * 【数据流】
 *  TaskScanScheduler --> TaskExecutor.executeStart / executeFinish(传入 task 对象) --> 具体实现下发 RPC
 *  executeStart 返回 boolean：true = 执行成功（调度器置 RUNNING）；false = 失败（保留 PENDING 下轮重试）。
 *  本接口只定义契约、不持有状态；任务的状态流转由 TaskScanScheduler 负责。
 */
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
