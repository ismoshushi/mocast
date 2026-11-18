package app.mocast.net

import app.mocast.data.Settings
import java.net.Proxy

/**
 * 代理配置（0.4.0 新增）。
 *
 * 与 [Settings] 的代理字段一一对应，是 Http 层唯一认识的代理结构；
 * Settings 只负责持久化，生效走 [Http.configure]。
 *
 * 注意：OkHttp 4.12 不支持 SOCKS5 用户名/密码认证，SOCKS 代理填了账号密码
 * 也不会生效（见 Http.buildClient 注释）；HTTP 代理的 407 认证完整支持。
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    /** "HTTP" 或 "SOCKS"，与 Settings.proxyType 保持一致 */
    val type: String = "HTTP",
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = ""
) {
    /** 只有开关开着、host 非空、端口合法时才真正生效，否则等同于无代理。 */
    val isUsable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    val proxyType: Proxy.Type
        get() = if (type.equals("SOCKS", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP

    companion object {
        fun fromSettings(settings: Settings): ProxyConfig = ProxyConfig(
            enabled = settings.proxyEnabled,
            type = settings.proxyType,
            host = settings.proxyHost.trim(),
            port = settings.proxyPort,
            username = settings.proxyUsername.trim(),
            password = settings.proxyPassword
        )
    }
}
