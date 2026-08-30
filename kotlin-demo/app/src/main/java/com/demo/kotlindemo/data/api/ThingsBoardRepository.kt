/**
 * 【文件职责】ThingsBoard 数据仓库：封装全部 TB REST 调用（登录、田块/资产、设备、关系、遥测、RPC、用户/客户管理），并把 DTO 转换为 APP 模型（转模型扩展见 data/model 包）。
 * 【数据流】ViewModel → ThingsBoardRepository 挂起函数 → ApiClient.thingsboard（带 JWT）→ ThingsBoardApi → HTTP → 响应 DTO → 模型转换扩展（toDevice/applyTelemetry）→ UI 模型；身份/角色经缓存（cachedAuthority/cachedCustomerId/cachedTenantId）按角色选择接口。
 */
// 声明包名：数据仓库层（ThingsBoard 域）
package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.DeviceInfoDto
import com.demo.kotlindemo.data.dto.LoginResponse
import com.demo.kotlindemo.data.dto.TelemetryItem
import com.demo.kotlindemo.data.dto.CurrentUserDto
import com.demo.kotlindemo.data.dto.CustomerDto
import com.demo.kotlindemo.data.dto.AssetInfoDto
import com.demo.kotlindemo.data.dto.MemberDto
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.Field
import com.demo.kotlindemo.data.model.applyTelemetry
import com.demo.kotlindemo.data.model.toDevice
import com.demo.kotlindemo.util.FieldCoords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * ThingsBoard 数据仓库（由原 FarmRepository 拆分出的「TB 域」部分）
 *
 * 职责：封装所有 ThingsBoard REST 调用（登录/田块/设备/遥测/RPC/凭证），
 *      并把 DTO 转换为 APP 模型（转换扩展函数见 data/model 包）。
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类只依赖 ThingsBoard，与微服务端（任务）彻底分离——微服务端调用见 TaskRepository；
 *  - 所有方法均为挂起函数，需在协程中调用；
 *  - 未来多租户/角色区分（第二版）只改本类内部接口选择，UI 层无感知。
 */
class ThingsBoardRepository {

    /** ThingsBoard API（带 JWT 认证 client，登录/田块/设备/遥测/RPC 均走这里） */
    private val api = ApiClient.thingsboard

    /** 登录：成功则缓存 JWT 到 AuthInterceptor 与 TokenStore（后续请求自动携带，重启不掉线） */
    suspend fun login(username: String, password: String): LoginResponse {
        val resp = api.login(mapOf("username" to username, "password" to password))
        AuthInterceptor.token = resp.token
        // 第二版：token 持久化，杀进程重开后仍保持登录
        if (resp.token.isNotEmpty()) {
            TokenStore.save(resp.token)
        }
        // 切换账号时重置角色缓存，下次查询重新拉取（避免跨账号串号）
        cachedAuthority = null
        cachedCustomerId = null
        return resp
    }

    /** 退出登录：清空内存与持久化的 JWT + 角色缓存 */
    fun logout() {
        AuthInterceptor.token = null
        TokenStore.clear()
        TokenStore.resetTasksVisited()  // 任务红点状态重置（第二版）
        clearCachedIdentity()
    }

    /**
     * 仅清空身份/租户缓存（不动 token）
     * 第三版：切换账号时各 ViewModel 持有的独立 Repository 实例都要调用，
     * 否则会用上个账号缓存的 tenantId 查数据（跨公司残留的元凶）
     */
    fun clearCachedIdentity() {
        cachedAuthority = null
        cachedCustomerId = null
        cachedTenantId = null
    }

    /** 获取所有田块（资产列表 + 每个田块的设备数）；员工(CUSTOMER_USER)只能看到被分配的 */
    suspend fun loadFields(): List<Field> = withContext(Dispatchers.IO) {
        // IO 线程池执行网络操作，避免阻塞主线程
        val page = fetchAssetsByRole()
        // coroutineScope：开启子协程并发查询每个田块的关系，全部完成后返回
        coroutineScope {
            page.map { asset ->
                async {
                    // 每个田块查 Contains 关系，统计设备数
                    val relations = api.getAssetRelations(asset.id.id)
                    val center = FieldCoords.centerFor(asset.name)
                    Field(
                        id = asset.id.id,
                        name = asset.name,
                        deviceCount = relations.size,
                        activeCount = relations.size, // 设备在线数由详情页实时查，这里先用总数
                        lat = center.first,
                        lon = center.second
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * 按角色取田块列表（第二版：老板=全部田块；员工=被分配的田块）
     */
    private suspend fun fetchAssetsByRole(): List<AssetInfoDto> {
        ensureIdentity()
        return if (cachedAuthority == "TENANT_ADMIN")
            api.getAssets(pageSize = AppConfig.PAGE_SIZE, page = 0).data
        else
            api.getCustomerAssets(cachedCustomerId ?: "", pageSize = AppConfig.PAGE_SIZE, page = 0).data
    }

    /**
     * 按角色取设备列表（第二版：老板=全部设备；员工=被分配的设备）
     */
    private suspend fun fetchDevicesByRole(type: String): List<DeviceInfoDto> {
        ensureIdentity()
        return if (cachedAuthority == "TENANT_ADMIN")
            api.getDevices(pageSize = AppConfig.PAGE_SIZE, page = 0, type = type).data
        else
            api.getCustomerDevices(cachedCustomerId ?: "", pageSize = AppConfig.PAGE_SIZE, page = 0, type = type).data
    }

    /** 获取某个田块下的所有设备（关系 → 设备信息 → 最新遥测） */
    suspend fun loadFieldDevices(fieldId: String): List<Device> = withContext(Dispatchers.IO) {
        // 田块 id → 查询全部出向关系（含设备挂载关系）
        val relations = api.getAssetRelations(fieldId)
        // 仅保留类型为 DEVICE 的关系，取其目标设备 id 列表
        val deviceIds = relations.filter { it.to.entityType == "DEVICE" }.map { it.to.id }
        // 并发拉取每个设备的信息与遥测，全部完成后合并返回
        coroutineScope {
            deviceIds.map { id ->
                async { fetchDeviceWithTelemetry(api.getDevice(id), fieldId) }
            }.awaitAll()
        }
    }

    /**
     * 获取全部设备（type=VALVE 或 TEMPERATURE_HUMIDITY，用于"设备"页）
     * 第二版修复：补全 fieldId（设备所属田块），用于区分「自由设备」与「已挂载设备」
     */
    suspend fun loadAllDevices(): List<Device> = withContext(Dispatchers.IO) {
        // 分角色取两类设备，合并后全量列表
        val valves = fetchDevicesByRole("VALVE")
        val sensors = fetchDevicesByRole("TEMPERATURE_HUMIDITY")
        // 构建「设备ID → 田块ID」映射（遍历田块 Contains 关系，员工视角只有被分配的田块）
        val fieldIdByDevice = buildFieldIdByDevice()
        // 并发拉取设备信息 + 最新遥测，并按映射补充所属田块 id
        coroutineScope {
            (valves + sensors).map { info ->
                async { fetchDeviceWithTelemetry(info, fieldIdByDevice[info.id.id]) }
            }.awaitAll()
        }
    }

    /**
     * 构建「设备ID → 田块ID」映射：遍历全部田块的 Contains 关系
     * 用途：「设备」页区分自由设备（fieldId 为空）与已挂载设备（第二版）
     */
    private suspend fun buildFieldIdByDevice(): Map<String, String> = withContext(Dispatchers.IO) {
        val fields = fetchAssetsByRole()
        val map = mutableMapOf<String, String>()
        for (field in fields) {
            val relations = api.getAssetRelations(field.id.id)
            for (r in relations) {
                if (r.to.entityType == "DEVICE") {
                    map[r.to.id] = field.id.id
                }
            }
        }
        map
    }

    /** 读取单台设备（按 id，用于关系解析后的详情） */
    suspend fun fetchDevice(deviceId: String, fieldId: String?): Device = withContext(Dispatchers.IO) {
        val info = api.getDevice(deviceId)
        fetchDeviceWithTelemetry(info, fieldId)
    }

    /**
     * 新增田块（第二版，租户管理员专属）
     * 流程：查 FIELD 资产配置 profileId → POST /api/asset（type=FIELD）
     * @param name 田块名称（租户内唯一）
     * @return true=创建成功
     */
    suspend fun createField(name: String): Boolean = withContext(Dispatchers.IO) {
        // 查 FIELD profile（不存在则只传 type，由 TB 使用默认资产配置）
        val profileId = api.getAssetProfiles(pageSize = 100, page = 0, textSearch = "FIELD").data
            .firstOrNull { it.name == "FIELD" }?.id?.id
        val body = mutableMapOf<String, Any>("name" to name, "type" to "FIELD")
        if (profileId != null) {
            body["assetProfileId"] = mapOf("entityType" to "ASSET_PROFILE", "id" to profileId)
        }
        api.createAsset(body).id.id.isNotEmpty()
    }

    /**
     * 删除田块（第二版，租户管理员专属）
     * TB 删除 Asset 会级联清理其 Contains 关系，田块下设备自动变为「自由设备」（可重新挂载）
     * @param fieldId 田块 ID
     * @return true=删除成功
     */
    suspend fun deleteField(fieldId: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteAsset(fieldId).isSuccessful
    }

    // ---------- 设备管理（第二版：新增/挂载/删除） ----------

    /**
     * 新增设备（第二版，租户管理员专属）：创建后为「自由设备」（不归属任何田块）
     * 流程：按类型查 DeviceProfile（VALVE / TEMPERATURE_HUMIDITY）→ POST /api/device → 取 accessToken
     *
     * @param name 设备名称
     * @param type 设备类型：VALVE（电动阀）/ TEMPERATURE_HUMIDITY（温湿度计）
     * @return accessToken（凭证，配置到真设备用）；失败返回 null
     */
    suspend fun createDevice(name: String, type: String): String? = withContext(Dispatchers.IO) {
        // 类型 → DeviceProfile 搜索词（与 tb_setup 建的 profile 同名）
        val profileSearch = if (type == "VALVE") "VALVE" else "TEMPERATURE_HUMIDITY"
        val profileId = api.getDeviceProfiles(pageSize = 100, page = 0, textSearch = profileSearch).data
            .firstOrNull { it.name == profileSearch }?.id?.id ?: return@withContext null
        val body = mapOf(
            "name" to name,
            "deviceProfileId" to mapOf("entityType" to "DEVICE_PROFILE", "id" to profileId)
        )
        val deviceId = api.createDevice(body).id.id
        // 取设备凭证（accessToken），供 App 展示给用户配置真设备
        api.getDeviceCredentials(deviceId).credentialsId
    }

    /**
     * 挂载设备到田块（第二版：两种挂载方式共用）
     * 流程：POST /api/relation {from=ASSET田块, to=DEVICE设备, type=Contains}
     *
     * @param deviceId 设备 ID
     * @param fieldId  目标田块 ID
     * @return true=挂载成功
     */
    suspend fun mountDevice(deviceId: String, fieldId: String): Boolean = withContext(Dispatchers.IO) {
        val body = mapOf(
            "from" to mapOf("entityType" to "ASSET", "id" to fieldId),
            "type" to "Contains",
            "to" to mapOf("entityType" to "DEVICE", "id" to deviceId)
        )
        api.createRelation(body).isSuccessful
    }

    /**
     * 取下设备（第三版）：删除设备与田块的 Contains 关系，设备变为自由设备
     * 调用 TB DELETE /api/v2/relation（query 参数形式，实测 /api/relation 无 DELETE）
     * @param deviceId 设备 id
     * @param fieldId  当前所属田块 id
     */
    suspend fun unmountDevice(deviceId: String, fieldId: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteRelation(
            fromType = "ASSET",
            fromId = fieldId,
            relationType = "Contains",
            toType = "DEVICE",
            toId = deviceId
        ).isSuccessful
    }

    /**
     * 改挂设备到别的田块（第三版）：先删旧关系，再建新关系
     * @param deviceId    设备 id
     * @param oldFieldId  原田块 id
     * @param newFieldId  新田块 id
     */
    suspend fun remountDevice(deviceId: String, oldFieldId: String, newFieldId: String): Boolean =
        withContext(Dispatchers.IO) {
            val ok = unmountDevice(deviceId, oldFieldId)
            if (!ok) return@withContext false
            mountDevice(deviceId, newFieldId)
        }

    /**
     * 删除设备（第二版，租户管理员专属）：DELETE /api/device/{deviceId}
     * TB 删除设备会自动清理其挂载关系（设备消失、田块设备数减少）
     * 注意：取消该设备未完成任务由 ViewModel 编排（先调微服务端 DELETE /api/tasks/device/{id}）
     *
     * @param deviceId 设备 ID
     * @return true=删除成功
     */
    suspend fun deleteDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteDevice(deviceId).isSuccessful
    }

    // ---------- 员工（使用者）管理（第二版，租户管理员专属） ----------

    /** 员工列表（Customer） */
    suspend fun loadCustomers(): List<CustomerDto> = withContext(Dispatchers.IO) {
        api.getCustomers(pageSize = AppConfig.PAGE_SIZE, page = 0).data
            // 过滤 TB 系统默认客户「Public」（无业务意义，不应在成员管理中展示）
            .filter { it.title != "Public" && it.name != "Public" }
    }

    /** 当前登录用户身份（GET /api/auth/user），并缓存角色供后续查询按角色走接口 */
    suspend fun loadCurrentUser(): CurrentUserDto = withContext(Dispatchers.IO) {
        val u = api.getCurrentUser()
        cachedAuthority = u.authority
        cachedCustomerId = u.customerId
        cachedTenantId = u.tenantId
        u
    }

    // ── 角色/租户缓存（第二版：查询接口按角色切换；第三版：创建成员需本公司 tenantId）──
    private var cachedAuthority: String? = null
    private var cachedCustomerId: String? = null
    private var cachedTenantId: String? = null

    /** 确保身份已加载（首次查询时从 TB 拉取一次） */
    private suspend fun ensureIdentity() {
        if (cachedAuthority == null) {
            val u = api.getCurrentUser()
            cachedAuthority = u.authority
            cachedCustomerId = u.customerId
            cachedTenantId = u.tenantId
        }
    }

    /** 当前登录者所属租户（公司）id（创建成员时使用，防止跨公司越权） */
    suspend fun myTenantId(): String? {
        ensureIdentity()
        return cachedTenantId
    }

    /** 是否为租户管理员（员工 CUSTOMER_USER 无管理权限，App 隐藏管理按钮） */
    suspend fun isAdmin(): Boolean {
        ensureIdentity()
        return cachedAuthority == "TENANT_ADMIN"
    }

    /**
     * 创建成员（第三版：成员管理统一入口，取代旧 createCustomerUser）
     * 流程（与《成员管理设计方案》4.1 一致）：
     *  - 管理员：POST /api/user {email, authority:TENANT_ADMIN, tenantId:本公司} → 激活
     *  - 使用者+新建家庭：POST /api/customer {title} → customerId → POST /api/user {..., customerId} → 激活
     *  - 使用者+加入已有家庭：POST /api/user {..., customerId:已有} → 激活
     *
     * @param role       角色："ADMIN"=租户管理员（加入本公司）/ "USER"=客户用户（家庭成员）
     * @param familyId   已有家庭（客户）id；null 表示新建家庭
     * @param familyName 新建家庭名称（familyId 为 null 且 role=USER 时必填）
     * @param email      账号邮箱（登录账号）
     * @param password   初始密码（默认 123456，首登强制改密）
     * @return true=创建成功（含激活设密成功）
     */
    suspend fun createMember(role: String, familyId: String?, familyName: String,
                             email: String, password: String = "123456"): Boolean =
        withContext(Dispatchers.IO) {
            // 本公司 tenantId（防止跨公司创建账号越权）
            val tenantId = myTenantId() ?: return@withContext false
            // ① 使用者且未指定家庭 → 新建家庭（客户）
            var customerId = familyId
            if (role != "ADMIN" && customerId == null) {
                val customer = api.createCustomer(mapOf("title" to familyName))
                customerId = customer.id.id
            }
            // ② 创建账号（不发送激活邮件）
            val user = api.createUser(
                sendActivationMail = false,
                body = if (role == "ADMIN")
                    mapOf(
                        "email" to email,
                        "authority" to "TENANT_ADMIN",
                        "tenantId" to mapOf("entityType" to "TENANT", "id" to tenantId)
                    )
                else
                    mapOf(
                        "email" to email,
                        "authority" to "CUSTOMER_USER",
                        "tenantId" to mapOf("entityType" to "TENANT", "id" to tenantId),
                        "customerId" to mapOf("entityType" to "CUSTOMER", "id" to customerId!!)
                    )
            )
            val userId = user.id.id
            // ③ 激活并设初始密码（TB 4.x：activateToken 在 activationLinkInfo 的 value URL 里）
            val activateUrl = api.getActivationLinkInfo(userId).value
            val activateToken = extractQueryParam(activateUrl, "activateToken")
                ?: return@withContext false
            api.activateUser(mapOf("activateToken" to activateToken, "password" to password)).isSuccessful
        }

    /** 加载成员列表（第三版）：本公司所有家庭（客户）+ 每个家庭下的账号
     * 说明：管理员（TENANT_ADMIN）不属于任何家庭，故本列表仅含家庭成员（CUSTOMER_USER）；
     *       管理员数量在 UI 层通过 currentUser 身份/成员页单独呈现。
     * @return 成员列表（含所属家庭名与账号 id）
     */
    suspend fun loadMembers(): List<MemberDto> = withContext(Dispatchers.IO) {
        val customers = api.getCustomers(pageSize = AppConfig.PAGE_SIZE, page = 0).data
            .filter { it.title != "Public" && it.name != "Public" }  // 与 loadCustomers 一致：排除 TB 默认客户
        customers.flatMap { c ->
            val cid = c.id.id
            api.getCustomerUsers(cid).data.map { u ->
                MemberDto(
                    customerId = cid,
                    customerTitle = c.title.ifBlank { c.name },
                    userId = u.id.id,
                    email = u.email,
                    authority = u.authority
                )
            }
        }
    }

    /**
     * 加载本公司所有管理员（第三版增强：成员管理页顶部展示 + 可删除）
     * 调 GET /api/users（租户管理员可列出本公司全部用户），过滤 TENANT_ADMIN
     * @return 管理员列表（含账号 id，删除用；含当前登录者自己）
     */
    suspend fun loadTenantAdmins(): List<CurrentUserDto> = withContext(Dispatchers.IO) {
        api.getUsers(pageSize = AppConfig.PAGE_SIZE, page = 0).data
            .filter { it.authority == "TENANT_ADMIN" }
    }

    /** 删除成员账号（第三版：只删账号，不动家庭/设备）：DELETE /api/user/{userId} */
    suspend fun deleteMember(userId: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteUser(userId).isSuccessful
    }

    /**
     * 从 URL 提取 query 参数（TB activationLinkInfo 的 value 形如 ...?activateToken=xxx）
     */
    private fun extractQueryParam(url: String, name: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        return url.substring(q + 1).split("&")
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
    }

    /** 删除员工（Customer）：DELETE /api/customer/{customerId} */
    suspend fun deleteCustomer(customerId: String): Boolean = withContext(Dispatchers.IO) {
        api.deleteCustomer(customerId).isSuccessful
    }

    /** 分配田块给员工（可见范围）：POST /api/customer/{id}/asset/{assetId} */
    suspend fun assignAssetToCustomer(customerId: String, assetId: String): Boolean =
        withContext(Dispatchers.IO) {
            api.assignAssetToCustomer(customerId, assetId).isSuccessful
        }

    /** 分配设备给员工（可见范围）：POST /api/customer/{id}/device/{deviceId} */
    suspend fun assignDeviceToCustomer(customerId: String, deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            api.assignDeviceToCustomer(customerId, deviceId).isSuccessful
        }

    /** 开关阀门（RPC oneway，不等待设备回执） */
    suspend fun toggleValve(deviceId: String, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val resp = api.sendRpc(
            deviceId = deviceId,
            body = mapOf("method" to "setValveState", "params" to mapOf("state" to on))
        )
        resp.isSuccessful
    }

    /** 历史遥测（温度/湿度曲线数据） */
    suspend fun loadHistory(
        deviceId: String,
        keys: String,
        startTs: Long,
        endTs: Long,
        interval: Long
    ): Map<String, List<TelemetryItem>> = withContext(Dispatchers.IO) {
        api.getTelemetryHistory(deviceId, keys, startTs, endTs, interval)
    }

    /**
     * 读取单台设备的完整信息（设备信息 + 最新遥测，一次性填充 UI 模型）
     * @param fieldId 所属田块 ID；未知时传 null（"设备"页全量列表场景）
     */
    private suspend fun fetchDeviceWithTelemetry(info: DeviceInfoDto, fieldId: String?): Device {
        val telemetry = api.getLatestTelemetry(
            deviceId = info.id.id,
            keys = AppConfig.TELEMETRY_KEYS,
            limit = 1
        )
        return info.toDevice(fieldId).applyTelemetry(telemetry)
    }
}
