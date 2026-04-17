package com.imam.app4

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.imam.app4.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var punchSensor: Sensor? = null
    private val gravity = FloatArray(3)

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val records = mutableListOf<PunchRecord>()

    private var isArmed = false
    private var inPunch = false
    private var peakAccel = 0.0
    private var deltaV = 0.0
    private var punchStartNs = 0L
    private var lastTimestampNs = 0L
    private var lastActiveNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        punchSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        records.addAll(loadRecords().sortedByDescending { it.momentum })
        binding.edtPhoneMass.setText(DEFAULT_PHONE_MASS_KG.toString())
        binding.txtStatus.text = getIdleStatus()
        renderBestRecord()
        renderHistory()
        renderCurrent(0.0)

        binding.btnArm.setOnClickListener {
            resetPunchState()
            isArmed = true
            binding.txtStatus.text = "Armed. Hold the phone tightly, then do one punch motion."
            Toast.makeText(this, "Punch detector armed", Toast.LENGTH_SHORT).show()
        }

        binding.btnReset.setOnClickListener {
            records.clear()
            prefs.edit().remove(KEY_RECORDS).apply()
            resetPunchState()
            renderBestRecord()
            renderHistory()
            binding.txtStatus.text = getIdleStatus()
            Toast.makeText(this, "Records cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnShare.setOnClickListener {
            shareBestRecord()
        }

        if (punchSensor == null) {
            binding.txtStatus.text = "This phone does not expose an accelerometer sensor for this app."
            binding.btnArm.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        punchSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = getLinearAcceleration(event)
        val magnitude = sqrt(
            values[0] * values[0] +
                values[1] * values[1] +
                values[2] * values[2]
        ).toDouble()

        renderCurrent(magnitude)

        if (!isArmed) {
            lastTimestampNs = event.timestamp
            return
        }

        if (!inPunch && magnitude >= START_THRESHOLD_MS2) {
            inPunch = true
            peakAccel = magnitude
            deltaV = 0.0
            punchStartNs = event.timestamp
            lastActiveNs = event.timestamp
            lastTimestampNs = event.timestamp
            binding.txtStatus.text = "Punch detected... keep going until movement stops."
            return
        }

        if (!inPunch) {
            lastTimestampNs = event.timestamp
            return
        }

        val dtSeconds = if (lastTimestampNs == 0L) {
            0.0
        } else {
            ((event.timestamp - lastTimestampNs) / 1_000_000_000.0).coerceIn(0.0, MAX_DT_SECONDS)
        }
        lastTimestampNs = event.timestamp

        peakAccel = maxOf(peakAccel, magnitude)
        deltaV += magnitude * dtSeconds

        if (magnitude >= ACTIVE_THRESHOLD_MS2) {
            lastActiveNs = event.timestamp
        }

        val durationMs = (event.timestamp - punchStartNs) / 1_000_000.0
        val quietEnoughToStop = event.timestamp - lastActiveNs >= QUIET_END_NS
        val tooLong = durationMs >= MAX_PUNCH_WINDOW_MS

        if (quietEnoughToStop || tooLong) {
            finishPunch(durationMs)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun getLinearAcceleration(event: SensorEvent): FloatArray {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            return event.values.clone()
        }

        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        return floatArrayOf(
            event.values[0] - gravity[0],
            event.values[1] - gravity[1],
            event.values[2] - gravity[2]
        )
    }

    private fun finishPunch(durationMs: Double) {
        val validPunch = durationMs >= MIN_PUNCH_WINDOW_MS && peakAccel >= MIN_PEAK_MS2 && deltaV > 0.0
        isArmed = false
        inPunch = false

        if (!validPunch) {
            binding.txtStatus.text = "Motion was too small or too short. Press Arm Punch and try again."
            resetPunchState(keepUi = true)
            return
        }

        val phoneMass = parsePhoneMassKg()
        val momentum = phoneMass * deltaV
        val record = PunchRecord(
            timestamp = System.currentTimeMillis(),
            peakAccel = peakAccel,
            deltaV = deltaV,
            momentum = momentum,
            phoneMassKg = phoneMass,
            sensorName = punchSensor?.name ?: "Unknown sensor"
        )

        records.add(record)
        records.sortByDescending { it.momentum }
        while (records.size > HISTORY_LIMIT) {
            records.removeLast()
        }
        saveRecords(records)
        renderBestRecord()
        renderHistory()

        val isBest = records.firstOrNull() == record
        binding.txtStatus.text = if (isBest) {
            "New best record! Estimated momentum ${format(record.momentum)} kg·m/s"
        } else {
            "Punch saved. Estimated momentum ${format(record.momentum)} kg·m/s"
        }

        resetPunchState(keepUi = true)
    }

    private fun shareBestRecord() {
        val best = records.firstOrNull()
        if (best == null) {
            Toast.makeText(this, "No record to share yet", Toast.LENGTH_SHORT).show()
            return
        }

        val dateText = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
            .format(Date(best.timestamp))

        val message = buildString {
            appendLine("💥 App4 Punch Challenge")
            appendLine("Rekor terbaik saya:")
            appendLine("Momentum estimasi HP: ${format(best.momentum)} kg·m/s")
            appendLine("Peak acceleration: ${format(best.peakAccel)} m/s²")
            appendLine("Delta-v estimasi: ${format(best.deltaV)} m/s")
            appendLine("Massa HP: ${format(best.phoneMassKg)} kg")
            appendLine("Waktu: $dateText")
            appendLine()
            appendLine("Berani kalahkan rekor ini? Coba dan share balik hasilmu!")
            appendLine()
            appendLine("Catatan: ini adalah estimasi momentum gerak handphone, bukan momentum tinju murni.")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }

        startActivity(Intent.createChooser(intent, "Share challenge"))
    }

    private fun renderCurrent(magnitude: Double) {
        val sensorName = punchSensor?.name ?: "No sensor"
        binding.txtCurrent.text = buildString {
            appendLine("Sensor: $sensorName")
            appendLine("Acceleration magnitude: ${format(magnitude)} m/s²")
            appendLine("Armed: ${if (isArmed) "YES" else "NO"}")
            appendLine("In punch: ${if (inPunch) "YES" else "NO"}")
        }
    }

    private fun renderBestRecord() {
        val best = records.firstOrNull()
        binding.txtBest.text = if (best == null) {
            "No record yet"
        } else {
            formatRecord(best)
        }
    }

    private fun renderHistory() {
        binding.txtHistory.text = if (records.isEmpty()) {
            "No punches recorded"
        } else {
            records.mapIndexed { index, record ->
                "#${index + 1}\n${formatRecord(record)}"
            }.joinToString("\n\n")
        }
    }

    private fun formatRecord(record: PunchRecord): String {
        val timestampText = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(record.timestamp))

        return buildString {
            appendLine("Time: $timestampText")
            appendLine("Momentum: ${format(record.momentum)} kg·m/s")
            appendLine("Peak accel: ${format(record.peakAccel)} m/s²")
            appendLine("Delta-v: ${format(record.deltaV)} m/s")
            appendLine("Phone mass: ${format(record.phoneMassKg)} kg")
        }.trim()
    }

    private fun parsePhoneMassKg(): Double {
        return binding.edtPhoneMass.text?.toString()?.toDoubleOrNull()
            ?.takeIf { it in 0.05..1.0 }
            ?: DEFAULT_PHONE_MASS_KG
    }

    private fun saveRecords(items: List<PunchRecord>) {
        val json = JSONArray()
        items.forEach { record ->
            json.put(
                JSONObject().apply {
                    put("timestamp", record.timestamp)
                    put("peakAccel", record.peakAccel)
                    put("deltaV", record.deltaV)
                    put("momentum", record.momentum)
                    put("phoneMassKg", record.phoneMassKg)
                    put("sensorName", record.sensorName)
                }
            )
        }
        prefs.edit().putString(KEY_RECORDS, json.toString()).apply()
    }

    private fun loadRecords(): List<PunchRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        PunchRecord(
                            timestamp = item.optLong("timestamp"),
                            peakAccel = item.optDouble("peakAccel"),
                            deltaV = item.optDouble("deltaV"),
                            momentum = item.optDouble("momentum"),
                            phoneMassKg = item.optDouble("phoneMassKg", DEFAULT_PHONE_MASS_KG),
                            sensorName = item.optString("sensorName", "Unknown sensor")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun resetPunchState(keepUi: Boolean = false) {
        inPunch = false
        peakAccel = 0.0
        deltaV = 0.0
        punchStartNs = 0L
        lastTimestampNs = 0L
        lastActiveNs = 0L
        if (!keepUi) {
            binding.txtStatus.text = getIdleStatus()
        }
    }

    private fun getIdleStatus(): String {
        return "Press Arm Punch, grip the phone securely, and perform one punch motion."
    }

    private fun format(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    companion object {
        private const val PREFS_NAME = "app4_prefs"
        private const val KEY_RECORDS = "records"
        private const val DEFAULT_PHONE_MASS_KG = 0.20
        private const val HISTORY_LIMIT = 10
        private const val START_THRESHOLD_MS2 = 8.0
        private const val ACTIVE_THRESHOLD_MS2 = 4.0
        private const val MIN_PEAK_MS2 = 10.0
        private const val MIN_PUNCH_WINDOW_MS = 70.0
        private const val MAX_PUNCH_WINDOW_MS = 900.0
        private const val QUIET_END_NS = 180_000_000L
        private const val MAX_DT_SECONDS = 0.05
    }
}
