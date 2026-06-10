package com.example.a0610

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // ── 感應器 ──────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var stepDetectorSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private var linearAccSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // ── PDR 狀態 ────────────────────────────────────────────
    private var isRecording = false
    private var isCalibrating = false
    private var stepCount = 0
    private var calibrationSteps = 0
    private var calibrationTargetDist = 5.0
    private var distanceTravelled = 0.0
    private var currentX = 0.0
    private var currentY = 0.0
    private val pathPoints = mutableListOf<PointD>()

    // ── 方向角過濾 (針對閉環優化) ──────────────────────────────
    private var currentHeadingRad = 0.0
    private var filteredSinH = 0.0
    private var filteredCosH = 1.0
    private var isFirstHeading = true

    // ── 步長計算 (Weinberg 模型) ────────────────────────────
    private var weinbergK = 0.45
    private val accBuffer = mutableListOf<Double>()
    private var lastStepLen = 0.70
    private var useDynamic = true

    // ── UI ────────────────────────────────────────────────────
    private lateinit var pathView: PathView
    private lateinit var tvSteps: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvArea: TextView
    private lateinit var tvStepLen: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etStepLength: EditText
    private lateinit var btnStartPause: Button
    private lateinit var switchDynamic: SwitchCompat

    data class PointD(val x: Double, val y: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindUI()
        initSensors()
        resetAll()
        checkPermissions()
    }

    private fun bindUI() {
        pathView       = findViewById(R.id.pathView)
        tvSteps        = findViewById(R.id.tvSteps)
        tvDistance     = findViewById(R.id.tvDistance)
        tvHeading      = findViewById(R.id.tvHeading)
        tvArea         = findViewById(R.id.tvArea)
        tvStepLen      = findViewById(R.id.tvStepLen)
        tvStatus       = findViewById(R.id.tvStatus)
        etStepLength   = findViewById(R.id.etStepLength)
        btnStartPause  = findViewById(R.id.btnStartPause)
        switchDynamic  = findViewById(R.id.switchDynamic)

        switchDynamic.setOnCheckedChangeListener { _, checked ->
            useDynamic = checked
            etStepLength.isEnabled = !checked
            refreshDisplay()
        }

        btnStartPause.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.btnCalculate).setOnClickListener { calculateArea() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetAll() }
        findViewById<Button>(R.id.btnSnapHeading).setOnClickListener { snapHeading() }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener { startCalibration() }
    }

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun toggleRecording() {
        if (isCalibrating) {
            finishCalibration()
            return
        }
        isRecording = !isRecording
        if (isRecording) {
            accBuffer.clear()
            btnStartPause.text = "暫停記錄"
            btnStartPause.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPause))
            tvStatus.text = "▶ 記錄中..."
            pathView.closeLoop(false)
        } else {
            btnStartPause.text = "開始記錄"
            btnStartPause.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorStart))
            tvStatus.text = "⏸ 已暫停"
        }
    }

    private fun calculateArea() {
        if (pathPoints.size < 3) {
            Toast.makeText(this, "點位不足，請多走幾步", Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = false
        btnStartPause.text = "開始記錄"
        btnStartPause.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorStart))

        // ── 核心修正：閉環修正 (Loop Closure) ──
        val first = pathPoints.first()
        val last = pathPoints.last()
        val driftX = last.x - first.x
        val driftY = last.y - first.y
        val driftDist = sqrt(driftX * driftX + driftY * driftY)

        // 偵測是否回到起點附近，如果是則將累積誤差平攤到每一步
        val correctedPoints = if (driftDist < distanceTravelled * 0.3 || driftDist < 5.0) {
            pathPoints.mapIndexed { index, point ->
                val ratio = index.toDouble() / (pathPoints.size - 1)
                PointD(point.x - driftX * ratio, point.y - driftY * ratio)
            }
        }
        else {
            pathPoints
        }

        // 使用修正後的座標點，利用鞋帶公式計算面積
        var area = 0.0
        for (i in correctedPoints.indices) {
            val j = (i + 1) % correctedPoints.size
            area += correctedPoints[i].x * correctedPoints[j].y
            area -= correctedPoints[j].x * correctedPoints[i].y
        }
        area = abs(area) / 2.0

        val ping = area / 3.3058
        tvArea.text = String.format(Locale.getDefault(), "面積: %.2f ㎡ (%.2f 坪)", area, ping)

        // 更新 UI 以顯示修正後完美閉合的路徑
        pathView.setPoints(correctedPoints)
        pathView.closeLoop(true)
        tvStatus.text = "✅ 閉環修正完成"
    }

    private fun resetAll() {
        isRecording = false
        isCalibrating = false
        isFirstHeading = true
        stepCount = 0
        distanceTravelled = 0.0
        currentX = 0.0
        currentY = 0.0
        pathPoints.clear()
        pathPoints.add(PointD(0.0, 0.0))
        pathView.clearPath()
        pathView.setPoints(pathPoints)
        tvArea.text = "面積: -- ㎡"
        tvStatus.text = "⏹ 準備中"
        btnStartPause.text = "開始記錄"
        btnStartPause.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorStart))
        refreshDisplay()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rm = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rm, event.values)
                val ori = FloatArray(3)
                SensorManager.getOrientation(rm, ori)
                val raw = ori[0].toDouble()

                // 初始化濾波器：防止啟動時方向突然跳變
                if (isFirstHeading) {
                    filteredSinH = sin(raw)
                    filteredCosH = cos(raw)
                    isFirstHeading = false
                }

                // 平滑濾波：走曲線/圓形時建議 0.15 較滑順
                val alpha = 0.15
                filteredSinH = filteredSinH * (1.0 - alpha) + sin(raw) * alpha
                filteredCosH = filteredCosH * (1.0 - alpha) + cos(raw) * alpha
                currentHeadingRad = atan2(filteredSinH, filteredCosH)

                val deg = Math.toDegrees(currentHeadingRad).let { if (it < 0) it + 360 else it }
                tvHeading.text = String.format(Locale.getDefault(), "方向: %.1f°", deg)
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val mag = sqrt(event.values[0].toDouble().pow(2) + event.values[1].toDouble().pow(2) + event.values[2].toDouble().pow(2))
                accBuffer.add(mag)
            }
            Sensor.TYPE_STEP_DETECTOR -> if (isRecording || isCalibrating) onStep()
        }
    }

    private fun onStep() {
        if (isCalibrating) {
            calibrationSteps++
            return
        }

        // 動態步長計算
        val stepLen = if (useDynamic) {
            val range = if (accBuffer.isNotEmpty()) accBuffer.max() - accBuffer.min() else 0.0
            accBuffer.clear()
            (weinbergK * range.pow(0.25)).coerceIn(0.4, 1.2)
        } else {
            etStepLength.text.toString().toDoubleOrNull() ?: 0.7
        }

        stepCount++
        distanceTravelled += stepLen
        currentX += stepLen * sin(currentHeadingRad)
        currentY += stepLen * cos(currentHeadingRad)
        pathPoints.add(PointD(currentX, currentY))
        lastStepLen = stepLen

        runOnUiThread {
            pathView.setPoints(pathPoints)
            refreshDisplay()
        }
    }

    private fun refreshDisplay() {
        tvSteps.text = "步數: $stepCount 步"
        tvDistance.text = String.format(Locale.getDefault(), "距離: %.2f m", distanceTravelled)
        tvStepLen.text = String.format(Locale.getDefault(), "步長: %.2fm", if (useDynamic) lastStepLen else etStepLength.text.toString().toDoubleOrNull() ?: 0.7)
    }

    private fun startCalibration() {
        val et = EditText(this).apply { setText("5"); setInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL) }
        AlertDialog.Builder(this).setTitle("步長校準").setMessage("請走一段直線並輸入距離(m)").setView(et)
            .setPositiveButton("開始") { _, _ ->
                calibrationTargetDist = et.text.toString().toDoubleOrNull() ?: 5.0
                calibrationSteps = 0
                isCalibrating = true
                isRecording = false
                btnStartPause.text = "完成校準"
                tvStatus.text = "📏 校準中..."
            }.show()
    }

    private fun finishCalibration() {
        isCalibrating = false
        if (calibrationSteps >= 3) {
            val avgLen = calibrationTargetDist / calibrationSteps
            weinbergK = avgLen / 1.1
            lastStepLen = avgLen
            etStepLength.setText(String.format(Locale.getDefault(), "%.2f", avgLen))
            Toast.makeText(this, "校準成功！平均步長：%.2fm".format(avgLen), Toast.LENGTH_SHORT).show()
        }
        resetAll()
    }

    private fun snapHeading() {
        val deg = Math.toDegrees(currentHeadingRad).let { if (it < 0) it + 360 else it }
        val snapped = (round(deg / 45.0) * 45.0) % 360.0
        currentHeadingRad = Math.toRadians(snapped)
        filteredSinH = sin(currentHeadingRad); filteredCosH = cos(currentHeadingRad)
        Toast.makeText(this, "方向鎖定至 ${snapped.toInt()}°", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, linearAccSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}