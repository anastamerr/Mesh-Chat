package chat.mesh.probe

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class MainActivity : Activity() {
    private lateinit var runButton: Button
    private lateinit var reportView: TextView
    private var activeProbe: BleProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        showReport(BleProbe.readCapabilities(this))
        runButton.setOnClickListener { ensurePermissionsAndRun() }
    }

    override fun onDestroy() {
        activeProbe?.close()
        activeProbe = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            runProbe()
        } else {
            reportView.text = getString(
                R.string.status_and_report,
                getString(R.string.permission_denied),
                BleProbe.readCapabilities(this).render(),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun ensurePermissionsAndRun() {
        val missing = BleProbe.requiredPermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            runProbe()
        } else {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun runProbe() {
        if (activeProbe != null) return
        runButton.isEnabled = false
        reportView.text = getString(
            R.string.status_and_report,
            getString(R.string.probe_running),
            BleProbe.readCapabilities(this).render(),
        )
        activeProbe = BleProbe(this) { runtime ->
            activeProbe = null
            runButton.isEnabled = true
            showReport(BleProbe.readCapabilities(this).copy(runtime = runtime))
        }.also(BleProbe::start)
    }

    private fun showReport(report: BleProbeReport) {
        val rendered = report.render()
        reportView.text = rendered
        Log.i(LOG_TAG, rendered)
    }

    private fun createContentView(): LinearLayout {
        val padding = (16 * resources.displayMetrics.density).toInt()
        reportView = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        runButton = Button(this).apply {
            setText(R.string.run_probe)
        }
        val scrollView = ScrollView(this).apply {
            addView(
                reportView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                runButton,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val LOG_TAG = "MeshPhase0Probe"
    }
}
