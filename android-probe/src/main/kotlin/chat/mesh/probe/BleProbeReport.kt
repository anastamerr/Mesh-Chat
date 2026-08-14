package chat.mesh.probe

internal data class BleProbeReport(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val permissionsGranted: Boolean,
    val bleFeature: Boolean,
    val adapterPresent: Boolean,
    val adapterEnabled: Boolean?,
    val scannerAvailable: Boolean?,
    val advertiserAvailable: Boolean?,
    val multipleAdvertisementSupported: Boolean?,
    val offloadedFilteringSupported: Boolean?,
    val offloadedBatchingSupported: Boolean?,
    val le2mPhySupported: Boolean?,
    val leCodedPhySupported: Boolean?,
    val extendedAdvertisingSupported: Boolean?,
    val periodicAdvertisingSupported: Boolean?,
    val maximumAdvertisingDataLength: Int?,
    val runtime: BleRuntimeResult? = null,
) {
    fun render(): String = buildString {
        appendLine("Phase 0 BLE capability report")
        appendLine("Device: $manufacturer $model")
        appendLine("Android API: $sdk")
        appendLine("Permissions granted: ${permissionsGranted.yesNo()}")
        appendLine("BLE feature: ${bleFeature.yesNo()}")
        appendLine("Bluetooth adapter: ${adapterPresent.yesNo()}")
        appendLine("Adapter enabled: ${adapterEnabled.yesNoUnknown()}")
        appendLine("Scanner available: ${scannerAvailable.yesNoUnknown()}")
        appendLine("Advertiser available: ${advertiserAvailable.yesNoUnknown()}")
        appendLine("Multiple advertising: ${multipleAdvertisementSupported.yesNoUnknown()}")
        appendLine("Offloaded filtering: ${offloadedFilteringSupported.yesNoUnknown()}")
        appendLine("Offloaded batching: ${offloadedBatchingSupported.yesNoUnknown()}")
        appendLine("LE 2M PHY: ${le2mPhySupported.yesNoUnknown()}")
        appendLine("LE coded PHY: ${leCodedPhySupported.yesNoUnknown()}")
        appendLine("Extended advertising: ${extendedAdvertisingSupported.yesNoUnknown()}")
        appendLine("Periodic advertising: ${periodicAdvertisingSupported.yesNoUnknown()}")
        appendLine("Maximum advertising data: ${maximumAdvertisingDataLength ?: "unknown"} bytes")

        runtime?.let { observation ->
            appendLine()
            appendLine("Runtime observation (${observation.durationMillis} ms)")
            appendLine("Scan started: ${observation.scanStarted.yesNo()}")
            appendLine("Advertising started: ${observation.advertisingStarted.yesNo()}")
            appendLine("GATT server published: ${observation.gattServerPublished.yesNo()}")
            appendLine("Probe peer discovered: ${observation.peerDiscovered.yesNo()}")
            appendLine("GATT client connected: ${observation.gattClientConnected.yesNo()}")
            appendLine("Peer service discovered: ${observation.peerServiceDiscovered.yesNo()}")
            appendLine("Negotiated ATT MTU: ${observation.negotiatedMtu ?: "unknown"} bytes")
            appendLine("Client write confirmed: ${observation.gattClientWriteConfirmed.yesNo()}")
            appendLine("Server write received: ${observation.gattServerReceivedWrite.yesNo()}")
            appendLine("Failures: ${observation.failures.ifEmpty { listOf("none") }.joinToString()}")
        }
    }.trimEnd()
}

internal data class BleRuntimeResult(
    val durationMillis: Long,
    val scanStarted: Boolean,
    val advertisingStarted: Boolean,
    val gattServerPublished: Boolean,
    val peerDiscovered: Boolean,
    val gattClientConnected: Boolean,
    val peerServiceDiscovered: Boolean,
    val negotiatedMtu: Int?,
    val gattClientWriteConfirmed: Boolean,
    val gattServerReceivedWrite: Boolean,
    val failures: List<String>,
)

private fun Boolean.yesNo(): String = if (this) "yes" else "no"

private fun Boolean?.yesNoUnknown(): String = when (this) {
    true -> "yes"
    false -> "no"
    null -> "unknown"
}
