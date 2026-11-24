package com.nova.files.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.nova.files.transfer.protocol.DISCOVERY_PORT
import com.nova.files.transfer.protocol.ProtoJson
import com.nova.files.transfer.protocol.SERVICE_TYPE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class NearbyDevice(
    val deviceId: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val lastSeen: Long,
    val via: String,          // "wifi" (UDP), "mdns", "manual", "trusted"
)

@Serializable
private data class Beacon(val nova: Int = 1, val id: String, val name: String, val type: String, val port: Int, val probe: Boolean = false)

/**
 * Finds Nova Files devices on the local network using two independent mechanisms, because some
 * Android TV boxes ship broken mDNS and some routers block broadcast:
 *  1. UDP broadcast beacons on [DISCOVERY_PORT] (also works on a phone hotspot without internet)
 *  2. DNS-SD / NSD service "_novafiles._tcp"
 * Beacons only announce presence; nothing is trusted until the authenticated handshake.
 */
class Discovery(private val ctx: Context, private val scope: CoroutineScope, private val selfId: String) {
    private val tag = "NovaDiscovery"
    private val found = ConcurrentHashMap<String, NearbyDevice>()
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices

    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private var jobs = mutableListOf<Job>()
    private var socket: DatagramSocket? = null

    @Volatile private var advertise: Beacon? = null
    private val running = AtomicBoolean(false)

    fun start(name: String, type: String, port: Int, discoverable: Boolean) {
        advertise = if (discoverable) Beacon(id = selfId, name = name, type = type, port = port) else null
        if (!running.compareAndSet(false, true)) { reRegisterNsd(name, port); return }
        multicastLock = wifi.createMulticastLock("nova-discovery").apply { setReferenceCounted(false); runCatching { acquire() } }
        startUdp(type, port, name)
        reRegisterNsd(name, port)
        startNsdDiscovery()
        jobs += scope.launch { while (isActive) { delay(3000); prune() } }
    }

    fun updateAdvertisement(name: String, type: String, port: Int, discoverable: Boolean) {
        advertise = if (discoverable) Beacon(id = selfId, name = name, type = type, port = port) else null
        reRegisterNsd(name, port)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { socket?.close() }
        regListener?.let { runCatching { nsd.unregisterService(it) } }; regListener = null
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discListener = null
        runCatching { multicastLock?.release() }
    }

    /** Actively ask devices to announce themselves now. */
    fun probe() { scope.launch(Dispatchers.IO) { sendBeacon(advertise?.copy(probe = true) ?: Beacon(id = selfId, name = "", type = "", port = 0, probe = true)) } }

    fun addManual(d: NearbyDevice) { found[d.deviceId] = d; publish() }

    private fun publish() { _devices.value = found.values.sortedBy { it.name.lowercase() } }

    private fun prune() {
        val now = System.currentTimeMillis()
        val removed = found.entries.removeIf { it.value.via != "manual" && now - it.value.lastSeen > 15_000 }
        if (removed) publish()
    }

    private fun seen(id: String, name: String, type: String, host: String, port: Int, via: String) {
        if (id == selfId || id.isBlank() || port <= 0) return
        val prev = found[id]
        found[id] = NearbyDevice(id, name.ifBlank { prev?.name ?: "Nova device" }, type.ifBlank { prev?.type ?: "phone" }, host, port, System.currentTimeMillis(),
            if (prev?.via == "wifi" && via == "mdns") "wifi" else via)
        if (prev == null || prev.host != host || prev.name != name) publish()
    }

    // ---------------------------------------------------------------- UDP
    private fun startUdp(type: String, port: Int, name: String) {
        jobs += scope.launch(Dispatchers.IO) {
            try {
                val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(DISCOVERY_PORT)) }
                socket = s
                val buf = ByteArray(2048)
                while (isActive && !s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val b = runCatching { ProtoJson.decodeFromString(Beacon.serializer(), String(p.data, 0, p.length)) }.getOrNull() ?: continue
                    if (b.id == selfId) continue
                    if (b.port > 0) seen(b.id, b.name, b.type, p.address.hostAddress ?: continue, b.port, "wifi")
                    if (b.probe) advertise?.let { sendTo(it, p.address) }
                }
            } catch (e: Exception) { Log.w(tag, "UDP listener stopped: ${e.message}") }
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                advertise?.let { sendBeacon(it) }
                delay(3000)
            }
        }
    }

    private fun sendTo(b: Beacon, addr: InetAddress) = runCatching {
        val data = ProtoJson.encodeToString(Beacon.serializer(), b).toByteArray()
        (socket ?: return@runCatching).send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT))
    }

    private fun sendBeacon(b: Beacon) {
        val data = ProtoJson.encodeToString(Beacon.serializer(), b).toByteArray()
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        broadcastAddresses().forEach { targets += it }
        val s = socket ?: return
        for (t in targets) runCatching { s.send(DatagramPacket(data, data.size, t, DISCOVERY_PORT)) }
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- NSD
    private fun reRegisterNsd(name: String, port: Int) {
        regListener?.let { runCatching { nsd.unregisterService(it) } }; regListener = null
        val adv = advertise ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name.ifBlank { "Nova Files" }
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", adv.id); setAttribute("t", adv.type); setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(tag, "NSD register failed $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun startNsdDiscovery() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(tag, "NSD discovery failed $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.add(info); drainResolve() }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discListener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** Older NsdManager versions only allow one resolve at a time. */
    @Suppress("DEPRECATION")
    private fun drainResolve() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll() ?: run { resolving.set(false); return }
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { resolving.set(false); drainResolve() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val id = i.attributes["id"]?.let { String(it) } ?: ""
                    val type = i.attributes["t"]?.let { String(it) } ?: ""
                    val host = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress ?: i.host?.hostAddress
                               else i.host?.hostAddress
                    if (host != null) seen(id, i.serviceName, type, host, i.port, "mdns")
                    resolving.set(false); drainResolve()
                }
            })
        }.onFailure { resolving.set(false) }
    }

    companion object {
        fun localIpv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress!! } }
                .sortedBy { (n, _) -> if (n.startsWith("wlan") || n.startsWith("eth")) 0 else if (n.startsWith("ap") || n.startsWith("swlan")) 1 else 2 }
                .map { it.second }.distinct()
        }.getOrDefault(emptyList())
    }
}
