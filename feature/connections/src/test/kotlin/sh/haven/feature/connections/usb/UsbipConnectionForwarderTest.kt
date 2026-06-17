package sh.haven.feature.connections.usb

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SshClient
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import sh.haven.core.usb.UsbIpServer

class UsbipConnectionForwarderTest {

    private fun yubikey(hasPermission: Boolean = true) = UsbDeviceInfo(
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x1050, productId = 0x0406, deviceClass = 0,
        manufacturerName = "Yubico", productName = "YubiKey FIDO+CCID",
        serialNumber = null, hasPermission = hasPermission, isOpen = false,
        interfaces = emptyList(),
    )

    @Test
    fun `attach exports the device and forwards its usbip port with the right busid`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        val server = mockk<UsbIpServer>(relaxed = true)
        val client = mockk<SshClient>(relaxed = true)
        every { broker.listDevices() } returns listOf(yubikey())
        coEvery { broker.openDevice(any()) } returns yubikey()
        every { server.start(any(), any(), any()) } returns 3240
        coEvery { client.execCommand(any()) } returns ExecResult(0, "rc=0", "")

        val handle = UsbipConnectionForwarder(broker, server).attach(client, "1050:0406") {}

        assertEquals("1-2", handle?.busid) // "/dev/bus/usb/001/002" -> "1-2"
        assertEquals(3240, handle?.remotePort)
        verify { server.start(eq("/dev/bus/usb/001/002"), any(), eq("127.0.0.1")) }
        verify { client.setPortForwardingR("127.0.0.1", 3240, "127.0.0.1", 3240) }
        coVerify { client.execCommand(match { it.contains("usbip attach") && it.contains("1-2") }) }
    }

    @Test
    fun `attach returns null and logs when the device is not attached`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        every { broker.listDevices() } returns emptyList()
        var logged = false

        val handle = UsbipConnectionForwarder(broker, mockk(relaxed = true))
            .attach(mockk(relaxed = true), "1050:0406") { logged = true }

        assertNull(handle)
        assertTrue(logged)
    }

    @Test
    fun `teardown drops the forward, stops the server, and closes the device`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        val server = mockk<UsbIpServer>(relaxed = true)
        val client = mockk<SshClient>(relaxed = true)
        val handle = UsbipConnectionForwarder.Handle("/dev/bus/usb/001/002", "1-2", 3240)

        UsbipConnectionForwarder(broker, server).teardown(client, handle) {}

        verify { client.delPortForwardingR(3240) }
        verify { server.stop() }
        verify { broker.closeDevice("/dev/bus/usb/001/002") }
    }
}
