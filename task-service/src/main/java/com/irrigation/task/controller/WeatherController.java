/**
 * 【文件职责】天气 REST 控制器（第三代第一版 §4）：接收 APP 的经纬度天气查询请求，参数解析后委托 WeatherService，返回 {success,weatherDesc,temperature,precipitation,precipProb1h} 响应。
 * 【数据流】APP → HTTP GET /api/weather?lat=&lon= → 本控制器解析经纬度 → WeatherService 查询天气 → 返回天气字段；不可用/失败时降级返回 success=false, "天气暂不可用"。
 */
package com.irrigation.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.service.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 天气 REST API（第三代第一版 §4）
 *
 * GET /api/weather?lat=..&lon=..
 *  → {success, weatherDesc, temperature, precipitation, precipProb1h}
 *
 * 失败/不可用降级返回 success=false, message="天气暂不可用"，App 友好提示。
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    // 天气查询服务（委托外部天气源，含降级）；mapper 用于构造响应 JsonNode。
    private final WeatherService weatherService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造注入 WeatherService，由 Spring 容器装配。 */
    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /** 查询当前天气：lat/lon 默认 "0"；WeatherService 返回 null 时整体降级为 success=false，App 友好提示。 */
    @GetMapping
    public JsonNode weather(@RequestParam(required = false, defaultValue = "0") String lat,
                            @RequestParam(required = false, defaultValue = "0") String lon) {
        // 新建空的 JSON 对象节点，作为响应体容器（各天气字段后续写入）。
        ObjectNode resp = mapper.createObjectNode();
        // 委托 WeatherService 查询当前天气
        WeatherService.WeatherResult r = weatherService.current(lat, lon);
        if (r == null) {
            // 降级分支：天气源不可用/查询失败，返回 success=false 且各字段置为占位/空值，避免前端拿到脏数据。
            resp.put("success", false);
            resp.put("message", "天气暂不可用");
            resp.put("weatherDesc", "——");
            resp.put("temperature", Double.NaN);
            resp.put("precipitation", 0.0);
            resp.putNull("precipProb1h");
            return resp;
        }
        // 正常分支：按 WeatherResult 组装天气字段
        resp.put("success", true);                          // 天气源成功返回，置成功标志
        resp.put("message", "success");                     // 提示文案固定为 success
        resp.put("weatherDesc", r.weatherDesc());           // 天气描述取自查询结果
        resp.put("temperature", r.temperature());           // 当前温度取自查询结果
        resp.put("precipitation", r.precipitation());       // 降水量取自查询结果
        resp.put("city", r.city() == null ? "未知" : r.city());  // 需求4：田块所在城市
        // precipProb1h 有值放数值，为空则置为 JSON null
        if (r.precipProb1h() != null) {
            resp.put("precipProb1h", r.precipProb1h());     // 有1小时降水概率：写入该字段
        } else {
            resp.putNull("precipProb1h");                    // 无概率值：置为 JSON null 保持结构一致
        }
        // 返回拼装好的天气响应节点。
        return resp;
    }
}
