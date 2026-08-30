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
/**
     * 登录获取 JWT（成功后由调用方缓存到拦截器/持久化存储）
     */
    suspend fun login(@Body body: Any): LoginResponse

    // 修改密码：{currentPassword, newPassword}（第二版：首次登录强制改密用，App 代填当前密码）
    @POST("api/auth/changePassword")
/**
     * 修改密码（校验当前密码；强制改密流程 App 代填默认密码）
     */
    suspend fun changePassword(@Body body: Any): Response<ResponseBody>

    // ---------- 设备 ----------
    // GET /api/tenant/deviceInfos?pageSize&page&type&textSearch（租户管理员视角）
    @GET("api/tenant/deviceInfos")
/**
     * 租户管理员视角：分页查询本租户全部设备
     */
    suspend fun getDevices(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null,
        @Query("textSearch") textSearch: String? = null
    ): PageData<DeviceInfoDto>

    // 员工(CUSTOMER_USER)视角设备：GET /api/customer/{customerId}/deviceInfos（只能看到被分配的）
    @GET("api/customer/{customerId}/deviceInfos")
/**
     * 员工(CUSTOMER_USER)视角：只返回被分配给该客户的设备
     */
    suspend fun getCustomerDevices(
        @Path("customerId") customerId: String,
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null,
        @Query("textSearch") textSearch: String? = null
    ): PageData<DeviceInfoDto>

    // 新增设备（第二版）：POST /api/device {"name","deviceProfileId":{...}}，创建后为自由设备
    @POST("api/device")
/**
     * 新增设备（创建后为自由设备，需另行挂载到田块）
     */
    suspend fun createDevice(@Body body: Any): DeviceInfoDto

    // 查询设备配置（新增设备需 VALVE / TEMPERATURE_HUMIDITY 的 profileId）
    @GET("api/deviceProfileInfos")
/**
     * 查询设备配置列表（新增设备时取 VALVE/TEMPERATURE_HUMIDITY 的 profileId）
     */
    suspend fun getDeviceProfiles(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("textSearch") textSearch: String? = null
    ): PageData<DeviceProfileDto>

    // 删除设备（第二版）：DELETE /api/device/{deviceId}
    @DELETE("api/device/{deviceId}")
/**
     * 删除设备（TB 自动清理其挂载关系）
     */
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<ResponseBody>

    // 建立挂载关系（第二版）：POST /api/relation {from:ASSET田块, to:DEVICE设备, type:Contains}
    @POST("api/relation")
/**
     * 建立关系（田块 Contains 设备 = 挂载）
     */
    suspend fun createRelation(@Body body: Any): Response<ResponseBody>

    // 删除单条关系（第三版：取下/改挂设备）：DELETE /api/v2/relation?fromType&fromId&relationType&toType&toId
    @DELETE("api/v2/relation")
/**
     * 删除单条关系（取下/改挂设备用，query 参数形式）
     */
    suspend fun deleteRelation(
        @Query("fromType") fromType: String,
        @Query("fromId") fromId: String,
        @Query("relationType") relationType: String,
        @Query("toType") toType: String,
        @Query("toId") toId: String
    ): Response<ResponseBody>

    // ---------- 资产（田块） ----------
    // GET /api/tenant/assetInfos（租户管理员视角）
    @GET("api/tenant/assetInfos")
/**
     * 租户管理员视角：分页查询本租户全部田块（资产）
     */
    suspend fun getAssets(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("type") type: String? = null
    ): PageData<AssetInfoDto>

    // 员工(CUSTOMER_USER)视角田块：GET /api/customer/{customerId}/assetInfos（只能看到被分配的）
    @GET("api/customer/{customerId}/assetInfos")
/**
     * 员工视角：只返回被分配的田块
     */
    suspend fun getCustomerAssets(
        @Path("customerId") customerId: String,
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0
    ): PageData<AssetInfoDto>

    // 新增田块（第二版）：POST /api/asset {"name","type":"FIELD","assetProfileId":{...}}
    @POST("api/asset")
/**
     * 新增田块（type=FIELD + assetProfileId）
     */
    suspend fun createAsset(@Body body: Any): AssetInfoDto

    // 删除田块（第二版）：DELETE /api/asset/{assetId}，其下设备自动变为自由设备
    @DELETE("api/asset/{assetId}")
/**
     * 删除田块（其下设备自动变自由设备）
     */
    suspend fun deleteAsset(@Path("assetId") assetId: String): Response<ResponseBody>

    // 查询田块配置（新增田块需 FIELD assetProfileId）
    @GET("api/assetProfileInfos")
/**
     * 查询田块配置（新增田块时取 FIELD profileId）
     */
    suspend fun getAssetProfiles(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("textSearch") textSearch: String? = null
    ): PageData<AssetProfileDto>

    // ---------- 关系（资产 Contains 设备） ----------
    // GET /api/relations/from/ASSET/{assetId}
    @GET("api/relations/from/ASSET/{assetId}")
/**
     * 查询田块下全部关系（含设备挂载关系）
     */
    suspend fun getAssetRelations(@Path("assetId") assetId: String): List<EntityRelationDto>

    // ---------- 遥测 ----------
    // GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries?keys&limit&orderBy
    @GET("api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries")
/**
     * 查询设备最新遥测（limit=1 取最近一条，orderBy=DESC）
     */
    suspend fun getLatestTelemetry(
        @Path("deviceId") deviceId: String,
        @Query("keys") keys: String? = null,
        @Query("limit") limit: Int = 1,
        @Query("orderBy") orderBy: String = "DESC"
    ): Map<String, List<TelemetryItem>>

    // 历史数据（曲线图）：GET .../values/timeseries?keys&startTs&endTs&interval&agg
    @GET("api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries")
/**
     * 查询历史遥测（曲线图数据，支持时间范围/聚合）
     */
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
/**
     * 下发 RPC 指令（oneway 不等待回执；开关阀用 setValveState）
     */
    suspend fun sendRpc(
        @Path("deviceId") deviceId: String,
        @Body body: Any
    ): Response<ResponseBody>

    // 获取设备凭证（accessToken；响应含嵌套 id 对象，用 DTO 解析避免 Map 解析失败）
    @GET("api/device/{deviceId}/credentials")
/**
     * 获取设备凭证（accessToken，真设备接入用）
     */
    suspend fun getDeviceCredentials(@Path("deviceId") deviceId: String): DeviceCredentialsDto

    // ---------- 单设备查询（关系解析后按 id 取设备信息） ----------
    @GET("api/device/{deviceId}")
/**
     * 按 id 查询单台设备信息
     */
    suspend fun getDevice(@Path("deviceId") deviceId: String): DeviceInfoDto

    // ---------- 认证（第二版：当前用户身份） ----------
    @GET("api/auth/user")
/**
     * 查询当前登录用户身份（判断角色/租户）
     */
    suspend fun getCurrentUser(): CurrentUserDto

    // ---------- 员工（使用者）管理（第二版，租户管理员专属） ----------
    // 员工列表（Customer）：GET /api/customers
    @GET("api/customers")
/**
     * 分页查询客户列表（家庭；排除系统默认 Public）
     */
    suspend fun getCustomers(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0,
        @Query("textSearch") textSearch: String? = null
    ): PageData<CustomerDto>

    // 创建员工（Customer）：POST /api/customer {title}
    @POST("api/customer")
/**
     * 创建客户（家庭）
     */
    suspend fun createCustomer(@Body body: Any): CustomerDto

    // 创建员工账号（CUSTOMER_USER）：POST /api/user?sendActivationMail=false
    @POST("api/user")
/**
     * 创建账号（TENANT_ADMIN 或 CUSTOMER_USER；可指定客户归属）
     */
    suspend fun createUser(
        @Query("sendActivationMail") sendActivationMail: Boolean = false,
        @Body body: Any
    ): DeviceInfoDto  // 响应含 {id:{id},...}，复用 DeviceInfoDto 的 id 结构

    // 获取激活信息（激活 token 在 value 的 URL 参数里）：GET /api/user/{userId}/activationLinkInfo
    @GET("api/user/{userId}/activationLinkInfo")
/**
     * 获取用户激活信息（激活 token 嵌在 value 的 URL 参数里）
     */
    suspend fun getActivationLinkInfo(@Path("userId") userId: String): ActivationInfoDto

    // 激活并设初始密码：POST /api/noauth/activate
    @POST("api/noauth/activate")
/**
     * 激活用户并设置初始密码（noauth/activate）
     */
    suspend fun activateUser(@Body body: Any): Response<ResponseBody>

    // 删除员工（Customer）：DELETE /api/customer/{customerId}
    @DELETE("api/customer/{customerId}")
/**
     * 删除客户（家庭；其下成员账号级联删除）
     */
    suspend fun deleteCustomer(@Path("customerId") customerId: String): Response<ResponseBody>

    // 删除成员账号（第三版：只删账号，不动家庭/设备）：DELETE /api/user/{userId}
    @DELETE("api/user/{userId}")
/**
     * 删除账号（只删账号，不动家庭/设备）
     */
    suspend fun deleteUser(@Path("userId") userId: String): Response<ResponseBody>

    // 分配田块给员工：POST /api/customer/{customerId}/asset/{assetId}
    @POST("api/customer/{customerId}/asset/{assetId}")
/**
     * 分配田块给客户（可见范围）
     */
    suspend fun assignAssetToCustomer(
        @Path("customerId") customerId: String,
        @Path("assetId") assetId: String
    ): Response<ResponseBody>

    // 分配设备给员工：POST /api/customer/{customerId}/device/{deviceId}
    @POST("api/customer/{customerId}/device/{deviceId}")
/**
     * 分配设备给客户（可见范围）
     */
    suspend fun assignDeviceToCustomer(
        @Path("customerId") customerId: String,
        @Path("deviceId") deviceId: String
    ): Response<ResponseBody>

    // 查询员工（Customer）下的账号：GET /api/customer/{customerId}/users
    // 注意：TB 4.x 此接口 pageSize 为必填参数，缺失会返回 500
    @GET("api/customer/{customerId}/users")
/**
     * 查询客户下的账号列表（家庭成员）
     */
    suspend fun getCustomerUsers(
        @Path("customerId") customerId: String,
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0
    ): PageData<CurrentUserDto>

    // 查询本公司全部用户（第三版增强：成员管理页展示本公司管理员列表）
    @GET("api/users")
/**
     * 查询本公司全部用户（成员管理页管理员列表用）
     */
    suspend fun getUsers(
        @Query("pageSize") pageSize: Int = 100,
        @Query("page") page: Int = 0
    ): PageData<CurrentUserDto>
}
