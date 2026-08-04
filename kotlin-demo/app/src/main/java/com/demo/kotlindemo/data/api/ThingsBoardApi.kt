package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * ThingsBoard REST API 接口定义
 * 对应需求文档「客户端数据读取规范」全部端点
 */
interface ThingsBoardApi {

    // ---------- 认证 ----------
    @POST("api/auth/login")
    suspend fun login(@Body body: Any): LoginResponse

    // ---------- 设备 ----------
    // GET /api/tenant/deviceInfos?pageSize&page&type&textSearch
    @GET("api/tenant/deviceInfos")
    suspend fun getDevices(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null,
        @Query("textSearch") textSearch: String? = null
    ): PageData<DeviceInfoDto>

    // ---------- 资产（田块） ----------
    // GET /api/tenant/assetInfos
    @GET("api/tenant/assetInfos")
    suspend fun getAssets(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null
    ): PageData<AssetInfoDto>

    // ---------- 关系（资产 Contains 设备） ----------
    // GET /api/relations/from/ASSET/{assetId}
    @GET("api/relations/from/ASSET/{assetId}")
    suspend fun getAssetRelations(@Path("assetId") assetId: String): List<EntityRelationDto>

    // ---------- 遥测 ----------
    // GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries?keys&limit&orderBy
    @GET("api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries")
    suspend fun getLatestTelemetry(
        @Path("deviceId") deviceId: String,
        @Query("keys") keys: String? = null,
        @Query("limit") limit: Int = 1,
        @Query("orderBy") orderBy: String = "DESC"
    ): Map<String, List<TelemetryItem>>

    // 历史数据（曲线图）：GET .../values/timeseries?keys&startTs&endTs&interval&agg
    @GET("api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries")
    suspend fun getTelemetryHistory(
        @Path("deviceId") deviceId: String,
        @Query("keys") keys: String,
        @Query("startTs") startTs: Long,
        @Query("endTs") endTs: Long,
        @Query("interval") interval: Long,
        @Query("agg") agg: String = "AVG",
        @Query("limit") limit: Int = 1000
    ): Map<String, List<TelemetryItem>>

    // ---------- RPC 控制 ----------
    // POST /api/rpc/oneway/{deviceId}  {"method":"setValveState","params":{"state":true}}
    @POST("api/rpc/oneway/{deviceId}")
    suspend fun sendRpc(
        @Path("deviceId") deviceId: String,
        @Body body: Any
    ): Response<ResponseBody>

    // ---------- 设备凭据 ----------
    @GET("api/device/{deviceId}/credentials")
    suspend fun getDeviceCredentials(@Path("deviceId") deviceId: String): Map<String, String>

    // ---------- 单设备查询（关系解析后按 id 取设备信息） ----------
    @GET("api/device/{deviceId}")
    suspend fun getDevice(@Path("deviceId") deviceId: String): DeviceInfoDto
}
