/**
 * ═══════════════════════════════════════════════════════════════
 * 【文件职责】
 * 田块地图视图（第三代第一版 §6）：用 WebView 加载本地 assets/map.html
 * （Leaflet + 高德底图，免费无 key），把田块中心坐标 + 设备点位坐标注入 JS 渲染，
 * 点击地图上的设备点位通过 JSBridge 回调 onDeviceClick。
 * 无坐标的设备按田块中心作编号示意分布（FieldCoords.devicePoint），避免多个设备叠在一点。
 *
 * 【数据流】
 *  - 入参：fieldName 地图标题/中心标注；centerLat/centerLon 田块中心（0 视为无坐标，
 *    退化为 FieldCoords.FALLBACK 默认位置）；devices 该田块下的设备列表；
 *    onDeviceClick(设备名) 点击回调；modifier 布局修饰符。
 *  - 注入：buildJson 把设备坐标/类型/开关状态序列化为 JSON 数组，escapeJs 做单引号转义；
 *    组装成 `window.__inject={...}; if(typeof renderField==='function') renderField(...)`
 *    的双保险注入，并放在 onPageFinished 之后执行，修复「renderField 未定义 → 白屏」的
 *    旧问题（update 回调再兜底注入一次，用 isInjected 去重）。
 *  - JS→Kotlin：map.html 里点击设备点时调 window.JSBridge.onDeviceClick(name)，
 *    @JavascriptInterface 接收到后由 mainHandler.post 切回主线程，再调用 onDeviceClick。
 *  - 排障：WebViewClient.onReceivedError / WebChromeClient.onConsoleMessage 捕获
 *    加载错误与 JS console，异常时 Toast 提示，避免白屏却无法定位。
 *  本文件仅做展示与坐标注入，不含业务/网络逻辑。
 * ═══════════════════════════════════════════════════════════════
 */
// 声明包名：UI 组件层
package com.demo.kotlindemo.ui.components

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.util.FieldCoords

/**
 * 田块地图视图（第三代第一版 §6）
 *
 * WebView 加载本地 assets/map.html（Leaflet + 高德底图，免费无 key），
 * 把田块中心 + 设备点位坐标注入 JS 渲染；点击设备点通过 JSBridge 回调 onDeviceClick。
 *
 * 排障（2026-08-27）：开启 setWebContentsDebuggingEnabled 以便 PC 用 chrome://inspect 抓取；
 * 并用 WebViewClient/WebChromeClient 捕获 加载错误 / JS console，遇到异常优先 Toast 提示，避免"白屏却不知原因"。
 *
 * @param fieldName  田块名称（地图标题/中心标注）
 * @param centerLat  田块中心纬度（无坐标时用 FieldCoords 岳麓默认）
 * @param centerLon  田块中心经度
 * @param devices    该田块下设备列表（无坐标设备按田块中心作示意分布）
 * @param onDeviceClick 点击设备点位回调（设备名）
 */
@Composable
fun FieldMapView(
    fieldName: String,
    centerLat: Double,
    centerLon: Double,
    devices: List<Device>,
    onDeviceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 主线程 Handler：JS 回调可能不在主线程触发，需切回
    val mainHandler = Handler(Looper.getMainLooper())
    // 点击回调包装（切主线程）
    val click = onDeviceClick

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                // 排障：开启后可用电脑 Chrome 打开 chrome://inspect 查看该 WebView 的 console / 网络 / 报错
                WebView.setWebContentsDebuggingEnabled(true)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onDeviceClick(name: String) {
                        mainHandler.post { click(name.replace("'", "")) }
                    }
                }, "JSBridge")
                // 排障：加载错误 / JS 异常 → Toast 提示具体原因，避免白屏无法定位
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?, errorCode: Int, description: String, failingUrl: String?
                    ) {
                        mainHandler.post {
                            Toast.makeText(ctx, "地图加载出错($errorCode) $description", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null &&
                            (consoleMessage.message().contains("error", true) ||
                             consoleMessage.message().contains("失败", true) ||
                             consoleMessage.message().contains("tile", true))) {
                            Log.w("FieldMapView", "JS: ${consoleMessage.message()}")
                        }
                        return true
                    }
                }
                // loadUrl 用 android_asset 路径加载本地地图页
                // 排障修复(2026-08-27)：此前 JS 注入放在 update 回调，可能早于页面 JS 加载完成，
                // 导致 "renderField is not defined" → 地图不渲染(白屏)。改为 onPageFinished 后再注入，
                // 确保 map.html 里的 renderField 已定义。
                var isInjected = false
                fun buildInjectJs(): String {
                    val devJson = buildJson(devices, centerLat, centerLon)
                    val safeName = fieldName.replace("'", "\\'")
                    val lat = if (centerLat == 0.0) "null" else centerLat
                    val lon = if (centerLon == 0.0) "null" else centerLon
                    // 双保险：① 写全局 __inject（map.html 轮询检测到即渲染，解决函数未定义竞态）
                    //         ② 直接调用 renderField（若页面已就绪则立即生效）
                    return "window.__inject={name:'${safeName}',lat:${lat},lon:${lon},devices:'${escapeJs(devJson)}'};" +
                            "if(typeof renderField==='function'){renderField('${safeName}',${lat},${lon},'${escapeJs(devJson)}');}"
                }
                fun ensureInjected(view: WebView?) {
                    if (view == null || isInjected) return
                    view.evaluateJavascript(buildInjectJs(), null)
                    isInjected = true
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 页面加载完成后再注入坐标，避免 renderField 未定义
                        ensureInjected(view)
                    }
                    override fun onReceivedError(
                        view: WebView?, errorCode: Int, description: String, failingUrl: String?
                    ) {
                        mainHandler.post {
                            Toast.makeText(ctx, "地图加载出错($errorCode) $description", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null &&
                            (consoleMessage.message().contains("error", true) ||
                             consoleMessage.message().contains("失败", true) ||
                             consoleMessage.message().contains("tile", true))) {
                            Log.w("FieldMapView", "JS: ${consoleMessage.message()}")
                        }
                        return true
                    }
                }
                loadUrl("file:///android_asset/map.html")
            }
        },
        update = { wv ->
            // 兜底：若 update 时页面已就绪(onPageFinished 已触发但注入失败)，再试一次
            // 双保险写法，与 ensureInjected 一致（注入已用 isInjected 去重）
            if (wv.url?.contains("map.html") == true) {
                val devJson = buildJson(devices, centerLat, centerLon)
                val safeName = fieldName.replace("'", "\\'")
                val lat = if (centerLat == 0.0) "null" else centerLat
                val lon = if (centerLon == 0.0) "null" else centerLon
                val js = "window.__inject={name:'${safeName}',lat:${lat},lon:${lon},devices:'${escapeJs(devJson)}'};" +
                        "if(typeof renderField==='function'){renderField('${safeName}',${lat},${lon},'${escapeJs(devJson)}');}"
                wv.evaluateJavascript(js, null)
            }
        }
    )
}

/** 构建设备坐标 JSON 数组字符串。
 *  有坐标的设备用真实坐标；无坐标设备以田块中心为基准按编号示意分布（FieldCoords.devicePoint），避免全叠一点。
 */
private fun buildJson(devices: List<Device>, centerLat: Double, centerLon: Double): String {
    // 中心坐标基准：经纬度都为 0 视为「无坐标」→ 退化为默认位置（FieldCoords.FALLBACK），
    // 作为无坐标设备示意分布的参考点
    val center = if (centerLat == 0.0 && centerLon == 0.0)
        FieldCoords.FALLBACK else (centerLat to centerLon)
    val items = devices.mapIndexed { index, it ->
        val hasCoord = it.lat != 0.0 || it.lon != 0.0
        val (lat, lon) = if (hasCoord) (it.lat to it.lon) else FieldCoords.devicePoint(center, index)
        val type = when (it.type) {
            DeviceType.VALVE -> "VALVE"
            DeviceType.SOIL_SENSOR -> "SOIL_DEVICE"
            else -> "SENSOR"
        }
        """{"name":${jsonStr(it.name)},"lat":${lat},"lon":${lon},"type":"$type","on":${it.isOn}}"""
    }
    return "[" + items.joinToString(",") + "]"
}

private fun jsonStr(s: String): String {
    val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$esc\""
}

/** JS 字符串转义（避免含单引号破坏脚本） */
private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
