package com.ybf.smbbak.util

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 目标探测：在“手机开热点、电脑连接”等场景下定位电脑的 SMB(445) 地址。
 */
object NetScan {

    const val SMB_PORT = 445

    // Android/鸿蒙热点常见网段：手机为 .1（热点主机），电脑为 DHCP 客户端（.2~.254）
    private const val AP_BASE = "192.168.43."

    /** 当前各活动网络的默认网关（覆盖“电脑开热点/路由器给手机”的情况） */
    fun gateways(context: Context): List<String> {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = LinkedHashSet<String>()
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            for (r in lp.routes) {
                if (!r.isDefaultRoute) continue
                val gw = r.gateway ?: continue
                if (gw is Inet4Address) out.add(gw.hostAddress ?: continue)
            }
        }
        return out.toList()
    }

    /** 手机热点网段 192.168.43.x 上的 SMB 主机扫描 */
    fun scanHotspotRange(): List<String> {
        val ips = (2..254).map { AP_BASE + it }
        return probeSmb(ips)
    }

    /** 并发快速探测一批 IP 的 445 端口，返回开放的主机 */
    fun probeSmb(hosts: List<String>, port: Int = SMB_PORT): List<String> {
        val open = java.util.Collections.synchronizedList(ArrayList<String>())
        if (hosts.isEmpty()) return open
        val pool = Executors.newFixedThreadPool(32)
        try {
            for (h in hosts) {
                pool.execute {
                    runCatching {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(h, port), 250)
                            if (s.isConnected) open.add(h)
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(8, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val sorted = ArrayList(open)
        sorted.sortBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
        return sorted
    }

    /** 对单个主机快速 TCP 探活（防止 smbj 在不可达主机上长时间阻塞） */
    fun tcpReachable(host: String, port: Int = SMB_PORT, timeoutMs: Int = 4000): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.isConnected
            }
        }.getOrDefault(false)
    }
}
