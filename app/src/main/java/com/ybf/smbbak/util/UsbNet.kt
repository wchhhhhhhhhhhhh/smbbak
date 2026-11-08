package com.ybf.smbbak.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 通过“USB 网络共享”（USB tethering / RNDIS / NCM）反向直连电脑时的电脑地址探测。
 * 手机无需开 USB 调试（ADB）。
 */
object UsbNet {

    /**
     * 尝试找出 USB 链路上电脑（网关）的 IPv4，返回去重后的候选列表。
     */
    fun findPcIpv4Candidates(context: Context): List<String> {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = LinkedHashSet<String>()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            val lp = cm.getLinkProperties(net) ?: continue
            val iface = lp.interfaceName ?: ""
            val isUsbLink = caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                iface.startsWith("usb") || iface.startsWith("rndis") ||
                iface.startsWith("ncm") || iface.startsWith("rmnet_usb")
            if (!isUsbLink) continue

            // 网关优先（反向网络共享/手机网络共享时网关通常即电脑）
            for (r in lp.routes) {
                if (!r.isDefaultRoute) continue
                val gw = r.gateway
                if (gw is Inet4Address) out.add(gw.hostAddress ?: continue)
            }
            // 启发式：USB 网段里的 .1（Windows ICS 常见 192.168.137.1）
            for (la in lp.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address) {
                    val host = addr.hostAddress ?: continue
                    val parts = host.split('.')
                    if (parts.size == 4) {
                        val base = parts.subList(0, 3).joinToString(".")
                        out.add("$base.1")
                    }
                }
            }
        }
        return out.toList()
    }
}
