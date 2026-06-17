package sh.haven.feature.connections.usb

import sh.haven.core.ssh.SshClient
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbIpServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-forwards a phone-attached USB device to a connected SSH host over USB/IP
 * (Slice 1 of the USB/IP feature). On connect: resolve the profile's VID:PID to
 * the live device, open it, start the userspace [UsbIpServer] on loopback, add a
 * remote port-forward for the usbip port, and best-effort `usbip attach` on the
 * remote so the device appears there as a real node — the touch stays on the
 * phone. On teardown: drop the forward (which detaches the remote device as its
 * usbip socket closes), stop the server, and close the device.
 *
 * One forwarded device at a time (the YubiKey case). [server] is the shared DI
 * singleton, so this never fights the MCP `start_usbip_export` path for the port.
 */
@Singleton
class UsbipConnectionForwarder @Inject constructor(
    private val broker: UsbBroker,
    private val server: UsbIpServer,
) {
    /** A live forward, returned by [attach] and handed back to [teardown]. */
    data class Handle(val deviceName: String, val busid: String, val remotePort: Int)

    /**
     * Attached phone USB devices as `(vidPid, label)` for the connection-settings
     * picker. Product names need USB permission, so an un-permissioned device
     * shows its VID:PID alone.
     */
    fun availableDevices(): List<Pair<String, String>> =
        broker.listDevices().map { d ->
            d.vidPid to (d.productName?.let { "$it (${d.vidPid})" } ?: d.vidPid)
        }

    /**
     * Resolve [vidPid] (e.g. "1050:0406") to a live phone device and forward it
     * to [client]. Returns the handle once the export + remote tunnel are up
     * (the remote `usbip attach` is best-effort — it needs passwordless sudo for
     * usbip on the host), or null if no such device is attached / permission is
     * denied. [log] receives human-readable progress for the connection log.
     */
    suspend fun attach(client: SshClient, vidPid: String, log: suspend (String) -> Unit): Handle? {
        val dev = broker.listDevices().firstOrNull { it.vidPid.equals(vidPid, ignoreCase = true) }
        val label = dev?.productName ?: vidPid
        if (dev == null) {
            log("USB forward: no $vidPid device attached to the phone — skipped")
            return null
        }
        if (!dev.hasPermission && !broker.requestPermission(dev.deviceName)) {
            log("USB forward: USB permission denied for $label")
            return null
        }
        return try {
            broker.openDevice(dev.deviceName)
            val port = server.start(dev.deviceName, bindAddress = LOOPBACK)
            val busid = busidOf(dev.deviceName)
            // The remote host reaches the phone's usbip server via its own loopback:port.
            client.setPortForwardingR(LOOPBACK, port, LOOPBACK, port)
            val attach = runCatching {
                client.execCommand(
                    "(sudo -n modprobe vhci_hcd 2>/dev/null; " +
                        "sudo -n usbip attach -r $LOOPBACK -b $busid) >/dev/null 2>&1; echo rc=$?",
                )
            }.getOrNull()
            if (attach?.stdout?.contains("rc=0") == true) {
                log("USB forward: $label attached on the remote (busid $busid)")
            } else {
                log(
                    "USB forward: export + tunnel up for $label; run " +
                        "`sudo usbip attach -r 127.0.0.1 -b $busid` on the host to bind it " +
                        "(passwordless sudo for usbip unavailable)",
                )
            }
            Handle(dev.deviceName, busid, port)
        } catch (e: Exception) {
            log("USB forward: failed for $label — ${e.message}")
            runCatching { server.stop() }
            runCatching { broker.closeDevice(dev.deviceName) }
            null
        }
    }

    /** Tear down a forward established by [attach]. Best-effort; never throws. */
    suspend fun teardown(client: SshClient, handle: Handle, log: suspend (String) -> Unit) {
        // Removing the remote forward closes the usbip socket, which makes the
        // remote vhci detach the device on its own — no host sudo needed, and we
        // don't touch any other usbip devices the host may have.
        runCatching { client.delPortForwardingR(handle.remotePort) }
        runCatching { server.stop() }
        runCatching { broker.closeDevice(handle.deviceName) }
        log("USB forward: ${handle.busid} torn down")
    }

    /** "/dev/bus/usb/001/002" -> "1-2" (the usbip busid; leading zeros stripped). */
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
    }
}
