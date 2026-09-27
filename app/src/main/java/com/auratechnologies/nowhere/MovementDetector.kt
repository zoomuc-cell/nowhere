package com.auratechnologies.nowhere


import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.sqrt

class MovementDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    // 센서 데이터 저장
    private var gyroData = FloatArray(3) // x, y, z 회전
    private var accelData = FloatArray(3) // x, y, z 가속도
    private var currentPressure = 0f
    private var lastPressure = 0f
    private var lastPressureTime = 0L

    // 이동 수단 판별 결과
    enum class TransportMode {
        STATIONARY,    // 정지
        WALKING,       // 도보
        VEHICLE,       // 차량
        FALLING,       // 낙하
        ELEVATOR       // 엘리베이터
    }

    var currentMode = TransportMode.STATIONARY
        private set

    private var onModeChanged: ((TransportMode) -> Unit)? = null

    fun startDetection(callback: (TransportMode) -> Unit) {
        onModeChanged = callback

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopDetection() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyroData = event.values.clone()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelData = event.values.clone()
            }
            Sensor.TYPE_PRESSURE -> {
                lastPressure = currentPressure
                currentPressure = event.values[0]
                lastPressureTime = System.currentTimeMillis()
            }
        }

        // 이동 수단 판별 로직 실행
        analyzeMovement()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun analyzeMovement() {
        // 자이로스코프 회전 강도 계산
        val gyroMagnitude = sqrt(
            gyroData[0] * gyroData[0] +
                    gyroData[1] * gyroData[1] +
                    gyroData[2] * gyroData[2]
        )

        // 가속도 변화 계산
        val accelMagnitude = sqrt(
            accelData[0] * accelData[0] +
                    accelData[1] * accelData[1] +
                    accelData[2] * accelData[2]
        )

        // 기압 변화량 (고도 변화)
        val pressureChange = currentPressure - lastPressure
        val altitudeChange = calculateAltitudeChange(pressureChange)

        // 이동 수단 판별
        val detectedMode = when {
            // 낙하 감지 (급격한 하강 + 높은 가속도)
            altitudeChange < -3 && accelMagnitude > 15 -> TransportMode.FALLING

            // 엘리베이터 감지 (급격한 고도 변화 + 낮은 자이로 회전)
            kotlin.math.abs(altitudeChange) > 2 && gyroMagnitude < 0.5 -> TransportMode.ELEVATOR

            // 차량 이동 (중간 진동 + 낮은 자이로 회전)
            gyroMagnitude < 1.5 && accelMagnitude in 10.0..14.0 -> TransportMode.VEHICLE

            // 도보 이동 (규칙적인 진동 + 중간 자이로 회전)
            gyroMagnitude in 0.5..3.0 && accelMagnitude in 8.0..12.0 -> TransportMode.WALKING

            // 정지
            else -> TransportMode.STATIONARY
        }

        // 모드 변경 시 콜백 실행
        if (detectedMode != currentMode) {
            currentMode = detectedMode
            onModeChanged?.invoke(currentMode)
        }
    }

    // 기압 → 고도 변화 계산 (간략 공식)
    private fun calculateAltitudeChange(pressureChange: Float): Float {
        // 1 hPa ≈ 8.3m 고도 변화
        return -pressureChange * 8.3f
    }

    // GPS 속도 기반 보조 판별
    fun updateSpeed(location: Location) {
        val speedKmh = location.speed * 3.6f // m/s → km/h

        // 속도 기반 추가 판별
        when {
            speedKmh < 1 -> {} // 정지 상태 유지
            speedKmh in 1.0..7.0 && currentMode != TransportMode.ELEVATOR -> {
                currentMode = TransportMode.WALKING
                onModeChanged?.invoke(currentMode)
            }
            speedKmh > 15 -> {
                currentMode = TransportMode.VEHICLE
                onModeChanged?.invoke(currentMode)
            }
        }
    }
}



