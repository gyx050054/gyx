/**
 * 【文件职责】智能灌溉系统定时任务调度微服务的启动入口。
 * 【数据流】Spring Boot 启动 → 自动装配全部 Bean（数据库、ThingsBoard 客户端、定时器）→ @EnableScheduling 让任务/告警/每天任务三个扫描器开始周期轮询。
 */
package com.irrigation.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能灌溉系统 - 定时任务调度微服务入口
 *
 * 职责（对应需求文档「微服务端定时任务完整执行流程」）：
 *  1. 接收 APP 创建的任务（单个/批量设备）
 *  2. 冲突检测：同一设备时间段交集（s1<e2 && e1>s2）则拒绝
 *  3. 每 10 秒扫描：到期任务 → 调 ThingsBoard RPC 开启/关闭阀门
 *  4. 到达结束时间自动删除任务
 *  5. 手动删除：未开始直接删；已开始先发 pauseValve 暂停
 */
@SpringBootApplication
@EnableScheduling
public class TaskServiceApplication {

    /**
     * 启动入口：Spring Boot 内嵌容器启动，自动装配全部 Bean 与定时扫描器
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
