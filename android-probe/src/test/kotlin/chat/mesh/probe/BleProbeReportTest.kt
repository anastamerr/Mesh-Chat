package chat.mesh.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class BleProbeReportTest {
    @Test
    fun `renders unknown capabilities and incomplete peer observation honestly`() {
        val report = BleProbeReport(
            manufacturer = "Example",
            model = "Phone",
            sdk = 36,
            permissionsGranted = true,
            bleFeature = true,
            adapterPresent = true,
            adapterEnabled = true,
            scannerAvailable = true,
            advertiserAvailable = null,
            multipleAdvertisementSupported = null,
            offloadedFilteringSupported = true,
            offloadedBatchingSupported = false,
            le2mPhySupported = true,
            leCodedPhySupported = false,
            extendedAdvertisingSupported = true,
            periodicAdvertisingSupported = false,
            maximumAdvertisingDataLength = null,
            runtime = BleRuntimeResult(
                durationMillis = 12_000,
                scanStarted = true,
                advertisingStarted = false,
                gattServerPublished = true,
                peerDiscovered = false,
                gattClientConnected = false,
                peerServiceDiscovered = false,
                negotiatedMtu = null,
                gattClientWriteConfirmed = false,
                gattServerReceivedWrite = false,
                failures = listOf("advertising:4"),
            ),
        )

        assertEquals(
            """
            Phase 0 BLE capability report
            Device: Example Phone
            Android API: 36
            Permissions granted: yes
            BLE feature: yes
            Bluetooth adapter: yes
            Adapter enabled: yes
            Scanner available: yes
            Advertiser available: unknown
            Multiple advertising: unknown
            Offloaded filtering: yes
            Offloaded batching: no
            LE 2M PHY: yes
            LE coded PHY: no
            Extended advertising: yes
            Periodic advertising: no
            Maximum advertising data: unknown bytes

            Runtime observation (12000 ms)
            Scan started: yes
            Advertising started: no
            GATT server published: yes
            Probe peer discovered: no
            GATT client connected: no
            Peer service discovered: no
            Negotiated ATT MTU: unknown bytes
            Client write confirmed: no
            Server write received: no
            Failures: advertising:4
            """.trimIndent(),
            report.render(),
        )
    }
}
