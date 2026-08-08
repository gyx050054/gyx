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

    // 修改密码：{currentPassword, newPassword}（第二版：首次登录强制改密用，App 代填当前密码）
    @POST("api/auth/changePassword")
    suspend fun changePassword(@Body body: Any): Response<ResponseBody>

    // ---------- 设备 ----------
    // GET /api/tenant/deviceInfos?pageSize&page&type&textSearch
    @GET("api/tenant/deviceInfos")
    suspend fun getDevices(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null,
        @Query("textSearch") textSearch: String? = null
    ): PageData<DeviceInfoDto>

    // 新增设备（第二版）：POST /api/device {"name","deviceProfileId":{...}}，创建后为自由设备
    @POST("api/device")
    suspend fun createDevice(@Body body: Any): DeviceInfoDto

    // 查询设备配置（新增设备需 VALVE / TEMPERATURE_HUMIDITY 的 profileId）
    @GET("api/deviceProfileInfos")
    suspend fun getDeviceProfiles(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("textSearch") textSearch: String? = null
    ): PageData<DeviceProfileDto>

    // 删除设备（第二版）：DELETE /api/device/{deviceId}
    @DELETE("api/device/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<ResponseBody>

    // 建立挂载关系（第二版）：POST /api/relation {from:ASSET田块, to:DEVICE设备, type:Contains}
    @POST("api/relation")
    suspend fun createRelation(@Body body: Any): Response<ResponseBody>

    // ---------- 资产（田块） ----------
    // GET /api/tenant/assetInfos
    @GET("api/tenant/assetInfos")
    suspend fun getAssets(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null
    ): PageData<AssetInfoDto>

    // 新增田块（第二版）：POST /api/asset {"name","type":"FIELD","assetProfileId":{...}}
    @POST("api/asset")
    suspend fun createAsset(@Body body: Any): AssetInfoDto

    // 删除田块（第二版）：DELETE /api/asset/{assetId}，其下设备自动变为自由设备
    @DELETE("api/asset/{assetId}")
    suspend fun deleteAsset(@Path("assetId") assetId: String): Response<ResponseBody>

    // 查询田块配置（新增田块需 FIELD assetProfileId）
    @GET("api/assetProfileInfos")
    suspend fun getAssetProfiles(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("textSearch") textSearch: String? = null
    ): PageData<AssetProfileDto>

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
