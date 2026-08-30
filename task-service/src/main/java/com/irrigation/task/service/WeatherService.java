/**
 * 【文件职责】
 * 天气网关（内部调用 Open-Meteo 免费接口，无需 key）。
 *  - 取指定坐标（lat,lon）当前天气：温度、天气码中文描述、降水量、未来1小时降雨概率、逆地理城市名；
 *  - 10 分钟缓存（key="lat,lon"）避免高频调用外部接口；
 *  - 失败降级：调用/解析失败返回 null，由调用方提示「天气暂不可用」。
 *
 * 【数据流】
 *  - 下游：Open-Meteo GET /v1/forecast（current + hourly + forecast_hours=4 + timezone=auto）；
 *    逆地理 BigDataCloud reverse-geocode-client（localityLanguage=zh）取城市名。
 *  - 上游：业务层传入 lat/lon → current(lat,lon) → 命中缓存直接返回；
 *    未命中则调 Open-Meteo → parse() 映射 WeatherResult（WMO 天气码→中文）→ 写缓存 → 返回。
 *  - 缓存：ConcurrentHashMap 双表（时间戳 + 结果），TTL=CACHE_TTL_MS（10 分钟）。
 *  - 降级：接口异常或解析失败返回 null；城市反查失败返回「未知」。
 */
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

    /** 天气结果（对外 DTO，新增 city：田块/坐标所属城市名，需求4） */
    public record WeatherResult(String weatherDesc, double temperature,
                                double precipitation, Integer precipProb1h,
                                String city) {}

    /**
     * 获取天气。命中缓存直接返回；未命中/过期则调 Open-Meteo。
     * 失败时返回 null（调用方降级提示"天气暂不可用"）。
     */
    public WeatherResult current(String lat, String lon) {
        String key = (lat == null ? "0" : lat.trim()) + "," + (lon == null ? "0" : lon.trim()); // 缓存键 = "lat,lon"；空值归一为 "0"（坐标缺失也当作有效键）
        long now = Instant.now().toEpochMilli(); // 当前毫秒时间戳，用于判断缓存是否过期
        long[] ts = cacheTs.get(key); // 读该坐标的缓存时间戳数组
        if (ts != null && now - ts[0] < CACHE_TTL_MS) { // 缓存存在且距今未超过 TTL（10 分钟）→ 命中缓存
            WeatherResult cached = cacheData.get(key); // 读对应的天气结果
            if (cached != null) { // 结果也存在 → 直接返回，避免调外部接口
                return cached;
            }
        }
        try {
            String body = rest.get() // 发起 GET 请求
                    .uri(OPEN_METEO_URL, lat, lon) // 目标 Open-Meteo 接口（latitude/longitude 占位符被 lat/lon 替换）
                    .retrieve() // 响应提取策略
                    .body(String.class); // 响应体以原始字符串接收
            WeatherResult result = parse(body, resolveCity(lat, lon));  // 需求4：附带城市名（先反查城市再解析天气）
            if (result != null) { // 解析成功才写缓存
                cacheTs.put(key, new long[]{now}); // 记录本次缓存时刻
                cacheData.put(key, result); // 缓存天气结果
            }
            return result; // 返回解析结果（可能为 null）
        } catch (Exception e) {
            log.warn("天气接口调用失败 lat={} lon={}: {}", lat, lon, e.getMessage()); // 调用/解析异常记日志
            return null; // 降级：返回 null，调用方提示「天气暂不可用」
        }
    }

    /**
     * 逆地理编码：坐标 → 城市名（需求4：天气显示田块所在城市）。
     * 用 BigDataCloud 免费 API（无 key），失败返回"未知"。
     */
    private String resolveCity(String lat, String lon) {
        try {
            if (lat == null || lon == null || lat.isBlank() || lon.isBlank()
                    || "0".equals(lat.trim()) || "0".equals(lon.trim())) { // 坐标缺失/为空/为 "0" → 无法反查，直接返回未知
                return "未知";
            }
            String url = "https://api.bigdatacloud.net/data/reverse-geocode-client"
                    + "?latitude=" + lat.trim() + "&longitude=" + lon.trim()
                    + "&localityLanguage=zh"; // 拼接逆地理接口 URL（坐标 + 中文地区语言）
            JsonNode node = rest.get().uri(url).retrieve().body(JsonNode.class); // 发起 GET 并反序列化为 JsonNode
            if (node == null) { // 响应为空 → 无法获取城市
                return "未知";
            }
            String city = node.path("city").asText(null); // 优先取 city 字段（地级市/城市名）
            if (city == null || city.isBlank()) { // city 缺失 → 回退取 locality
                city = node.path("locality").asText(null);
            }
            if (city == null || city.isBlank()) { // 仍缺失 → 回退取 principalSubdivision（省级/大区）
                city = node.path("principalSubdivision").asText(null);
            }
            return (city == null || city.isBlank()) ? "未知" : city; // 全部取不到则返回「未知」，否则返回城市名
        } catch (Exception e) {
            log.warn("城市反查失败 lat={} lon={}: {}", lat, lon, e.getMessage()); // 接口异常记日志
            return "未知"; // 降级：返回「未知」
        }
    }

    /** 解析 Open-Meteo 响应为内部结果（含 WMO 天气码映射与未来1小时降雨概率；city 为逆地理城市名） */
    private WeatherResult parse(String body, String city) {
        try {
            JsonNode root = mapper.readTree(body); // 把响应体 JSON 字符串解析成 JsonNode 树
            JsonNode current = root.path("current"); // 取 current 对象（当前天气）
            double temperature = current.path("temperature_2m").asDouble(Double.NaN); // 当前温度；缺失时默认 NaN
            double precipitation = current.path("precipitation").asDouble(0.0); // 当前降水量；缺失时默认 0
            int weatherCode = current.path("weather_code").asInt(-1); // 当前 WMO 天气码；缺失时默认 -1

            // 未来 1 小时降雨概率：取 hourly.precipitation_probability 数组第一项
            Integer precipProb1h = null; // 初始为空（无数据时）
            JsonNode probArr = root.path("hourly").path("precipitation_probability"); // 取未来小时级的降水概率数组
            if (probArr.isArray() && probArr.size() > 0) { // 是数组且有元素
                precipProb1h = probArr.get(0).asInt(); // 取第一项（未来 1 小时）的整型概率
            }

            return new WeatherResult(weatherDesc(weatherCode), // WMO 码 → 中文描述
                    temperature, precipitation, precipProb1h, city); // 组包为对外 DTO（含温度/降水/概率/城市）
        } catch (Exception e) {
            log.warn("天气响应解析失败：{}", e.getMessage()); // 解析异常记日志
            return null; // 降级：返回 null
        }
    }

    /** WMO 天气码 → 中文描述（简化映射） */
    private String weatherDesc(int code) {
        if (code == 0) return "晴"; // WMO 0：晴
        if (code <= 3) return "多云";      // 少云/晴间多云/阴
        if (code <= 48) return "雾"; // 雾/雾凇
        if (code <= 57) return "小雨";     // 毛毛雨/冻毛毛雨
        if (code <= 67) return "雨";       // 冻雨
        if (code <= 77) return "雪"; // 降雪/雪粒
        if (code <= 82) return "阵雨"; // 阵雨
        if (code <= 86) return "阵雪"; // 阵雪
        if (code <= 99) return "雷雨"; // 雷暴
        return "未知"; // 未覆盖码
    }

    /** 供调试：清空缓存 */
    public void clearCache() {
        cacheTs.clear(); // 清空缓存时间戳表（供调试用）
        cacheData.clear(); // 清空缓存结果表（供调试用）
    }
}
