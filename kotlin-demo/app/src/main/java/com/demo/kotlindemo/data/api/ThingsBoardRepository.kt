// 声明包名：数据仓库层（ThingsBoard 域）
package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.DeviceInfoDto
import com.demo.kotlindemo.data.dto.LoginResponse
import com.demo.kotlindemo.data.dto.TelemetryItem
import com.demo.kotlindemo.data.dto.CurrentUserDto
import com.demo.kotlindemo.data.dto.CustomerDto
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.Field
import com.demo.kotlindemo.data.model.applyTelemetry
import com.demo.kotlindemo.data.model.toDevice
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

    private val api = ApiClient.thingsboard

    /** 登录：成功则缓存 JWT 到 AuthInterceptor 与 TokenStore（后续请求自动携带，重启不掉线） */
    suspend fun login(username: String, password: String): LoginResponse {
        val resp = api.login(mapOf("username" to username, "password" to password))
        AuthInterceptor.token = resp.token
        // 第二版：token 持久化，杀进程重开后仍保持登录
        if (resp.token.isNotEmpty()) {
            TokenStore.save(resp.token)
        }
        return resp
    }

    /** 退出登录：清空内存与持久化的 JWT */
    fun logout() {
        AuthInterceptor.token = null
        TokenStore.clear()
    }

    /** 获取所有田块（资产列表 + 每个田块的设备数） */
    suspend fun loadFields(): List<Field> = withContext(Dispatchers.IO) {
        val page = api.getAssets(pageSize = AppConfig.PAGE_SIZE, page = 0)
        coroutineScope {
            page.data.map { asset ->
                async {
                    // 每个田块查 Contains 关系，统计设备数
                    val relations = api.getAssetRelations(asset.id.id)
                    Field(
                        id = asset.id.id,
                        name = asset.name,
                        deviceCount = relations.size,
                        activeCount = relations.size // 设备在线数由详情页实时查，这里先用总数
                    )
                }
            }.awaitAll()
        }
    }

    /** 获取某个田块下的所有设备（关系 → 设备信息 → 最新遥测） */
    suspend fun loadFieldDevices(fieldId: String): List<Device> = withContext(Dispatchers.IO) {
        val relations = api.getAssetRelations(fieldId)
        val deviceIds = relations.filter { it.to.entityType == "DEVICE" }.map { it.to.id }
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
        val valves = api.getDevices(pageSize = AppConfig.PAGE_SIZE, page = 0, type = "VALVE").data
        val sensors = api.getDevices(pageSize = AppConfig.PAGE_SIZE, page = 0, type = "TEMPERATURE_HUMIDITY").data
        // 构建「设备ID → 田块ID」映射（遍历全部田块 Contains 关系）
        val fieldIdByDevice = buildFieldIdByDevice()
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
        val fields = api.getAssets(pageSize = AppConfig.PAGE_SIZE, page = 0).data
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
    }

    /** 当前登录用户身份（GET /api/auth/user） */
    suspend fun loadCurrentUser(): CurrentUserDto = withContext(Dispatchers.IO) {
        api.getCurrentUser()
    }

    /**
     * 创建员工（Customer + CUSTOMER_USER + 激活设初始密码）
     * 流程（与内部需求文档 3.3 一致）：
     * ① POST /api/customer {title: 员工名称} → customerId
     * ② POST /api/user?sendActivationMail=false {email, authority:CUSTOMER_USER, customerId} → userId
     * ③ activationLinkInfo → 解析 activateToken → noauth/activate 设初始密码
     *
     * @param name  员工名称（Customer 标题）
     * @param email 员工邮箱（登录账号）
     * @param password 初始密码（默认 123456，首登强制改密）
     * @return true=创建成功
     */
    suspend fun createCustomerUser(name: String, email: String, password: String = "123456"): Boolean =
        withContext(Dispatchers.IO) {
            // ① 创建 Customer（title=员工名称）
            val customer = api.createCustomer(mapOf("title" to name))
            val customerId = customer.id.id
            // ② 创建 CUSTOMER_USER（不发送激活邮件）
            val user = api.createUser(
                sendActivationMail = false,
                body = mapOf(
                    "email" to email,
                    "authority" to "CUSTOMER_USER",
                    "customerId" to mapOf("entityType" to "CUSTOMER", "id" to customerId)
                )
            )
            val userId = user.id.id
            // ③ 激活并设初始密码（TB 4.x：activateToken 在 activationLinkInfo 的 value URL 里）
            val activateUrl = api.getActivationLinkInfo(userId).value
            val activateToken = extractQueryParam(activateUrl, "activateToken")
                ?: return@withContext false
            api.activateUser(mapOf("activateToken" to activateToken, "password" to password)).isSuccessful
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
