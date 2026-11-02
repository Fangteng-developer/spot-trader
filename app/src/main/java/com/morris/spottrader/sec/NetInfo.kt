package com.morris.spottrader.sec

import com.morris.spottrader.ex.Http
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

object NetInfo {
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map {
                "${ni.name}: ${if (it is Inet6Address) "IPv6 " else ""}${it.hostAddress?.substringBefore('%')}" }.sortedBy { if (it.contains("IPv6")) 1 else 0 } }
    } catch (e: Exception) { emptyList() }
    suspend fun publicIpv4(): String = try { Http.call("GET", "https://api.ipify.org").trim() } catch (e: Exception) { try { Http.call("GET", "https://ifconfig.me/ip").trim() } catch (e2: Exception) { "获取失败: ${e.message}" } }
    suspend fun publicIpv6(): String = try { Http.call("GET", "https://api64.ipify.org").trim() } catch (e: Exception) { "不可用" }
}
