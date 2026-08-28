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

    private final WeatherService weatherService;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping
    public JsonNode weather(@RequestParam(required = false, defaultValue = "0") String lat,
                            @RequestParam(required = false, defaultValue = "0") String lon) {
        ObjectNode resp = mapper.createObjectNode();
        WeatherService.WeatherResult r = weatherService.current(lat, lon);
        if (r == null) {
            resp.put("success", false);
            resp.put("message", "天气暂不可用");
            resp.put("weatherDesc", "——");
            resp.put("temperature", Double.NaN);
            resp.put("precipitation", 0.0);
            resp.putNull("precipProb1h");
            return resp;
        }
        resp.put("success", true);
        resp.put("message", "success");
        resp.put("weatherDesc", r.weatherDesc());
        resp.put("temperature", r.temperature());
        resp.put("precipitation", r.precipitation());
        if (r.precipProb1h() != null) {
            resp.put("precipProb1h", r.precipProb1h());
        } else {
            resp.putNull("precipProb1h");
        }
        return resp;
    }
}
