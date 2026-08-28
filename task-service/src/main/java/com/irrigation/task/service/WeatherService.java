package com.irrigation.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 天气网关（第三代第一版 §4）
 *
 * 内部调用 Open-Meteo 免费接口（无需 key），做字段映射与 10 分钟缓存，避免高频调用。
 * 对外提供 {@link #current(String, String)}，失败时降级返回"天气暂不可用"。
 *
 * 接口：
 *   GET https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}
 *       &current=temperature_2m,weather_code,precipitation
 *       &hourly=precipitation_probability,temperature_2m,precipitation
 *       &forecast_hours=4&timezone=auto
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private static final String OPEN_METEO_URL =
            "https://api.open-meteo.com/v1/forecast"
                    + "?latitude={lat}&longitude={lon}"
                    + "&current=temperature_2m,weather_code,precipitation"
                    + "&hourly=precipitation_probability,temperature_2m,precipitation"
                    + "&forecast_hours=4&timezone=auto";

    /** 缓存有效期（毫秒）：10 分钟 */
    private static final long CACHE_TTL_MS = 10 * 60_000L;

    private final RestClient rest = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 简易缓存：key="lat,lon" → [缓存时刻, 天气结果] */
    private final java.util.Map<String, long[]> cacheTs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, WeatherResult> cacheData = new java.util.concurrent.ConcurrentHashMap<>();

    /** 天气结果（对外 DTO） */
    public record WeatherResult(String weatherDesc, double temperature,
                                double precipitation, Integer precipProb1h) {}

    /**
     * 获取天气。命中缓存直接返回；未命中/过期则调 Open-Meteo。
     * 失败时返回 null（调用方降级提示"天气暂不可用"）。
     */
    public WeatherResult current(String lat, String lon) {
        String key = (lat == null ? "0" : lat.trim()) + "," + (lon == null ? "0" : lon.trim());
        long now = Instant.now().toEpochMilli();
        long[] ts = cacheTs.get(key);
        if (ts != null && now - ts[0] < CACHE_TTL_MS) {
            WeatherResult cached = cacheData.get(key);
            if (cached != null) {
                return cached;
            }
        }
        try {
            String body = rest.get()
                    .uri(OPEN_METEO_URL, lat, lon)
                    .retrieve()
                    .body(String.class);
            WeatherResult result = parse(body);
            if (result != null) {
                cacheTs.put(key, new long[]{now});
                cacheData.put(key, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("天气接口调用失败 lat={} lon={}: {}", lat, lon, e.getMessage());
            return null;
        }
    }

    /** 解析 Open-Meteo 响应为内部结果（含 WMO 天气码映射与未来1小时降雨概率） */
    private WeatherResult parse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode current = root.path("current");
            double temperature = current.path("temperature_2m").asDouble(Double.NaN);
            double precipitation = current.path("precipitation").asDouble(0.0);
            int weatherCode = current.path("weather_code").asInt(-1);

            // 未来 1 小时降雨概率：取 hourly.precipitation_probability 数组第一项
            Integer precipProb1h = null;
            JsonNode probArr = root.path("hourly").path("precipitation_probability");
            if (probArr.isArray() && probArr.size() > 0) {
                precipProb1h = probArr.get(0).asInt();
            }

            return new WeatherResult(weatherDesc(weatherCode),
                    temperature, precipitation, precipProb1h);
        } catch (Exception e) {
            log.warn("天气响应解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** WMO 天气码 → 中文描述（简化映射） */
    private String weatherDesc(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云";      // 少云/晴间多云/阴
        if (code <= 48) return "雾";
        if (code <= 57) return "小雨";     // 毛毛雨/冻毛毛雨
        if (code <= 67) return "雨";       // 冻雨
        if (code <= 77) return "雪";
        if (code <= 82) return "阵雨";
        if (code <= 86) return "阵雪";
        if (code <= 99) return "雷雨";
        return "未知";
    }

    /** 供调试：清空缓存 */
    public void clearCache() {
        cacheTs.clear();
        cacheData.clear();
    }
}
