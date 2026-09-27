package com.auratechnologies.nowhere

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoRecordsActivity : AppCompatActivity() {

    private lateinit var photoDatabase: PhotoDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        photoDatabase = PhotoDatabase(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(245, 247, 249))
        }

        val title = TextView(this).apply {
            text = "촬영 사진 기록"
            textSize = 26f
            setTextColor(Color.rgb(25, 35, 45))
            setPadding(0, 12, 0, 8)
        }
        root.addView(title)

        val countText = TextView(this).apply {
            text = "저장된 현장 사진 ${photoDatabase.getTotalRecords()}장"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        }
        root.addView(countText)

        val scrollView = ScrollView(this)
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val records = photoDatabase.getAll().sortedByDescending { it.timestamp }

        if (records.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "아직 촬영된 사진이 없습니다.\n\n홈 화면에서 '현장 사진 촬영'을 선택해 사진을 기록해 주세요."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(20, 100, 20, 100)
            }
            listLayout.addView(emptyText)
        } else {
            records.forEach { record ->
                listLayout.addView(createPhotoCard(record))
            }
        }

        scrollView.addView(listLayout)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun createPhotoCard(record: PhotoRecord): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                520
            )
            scaleType = ImageView.ScaleType.CENTER_CROP

            try {
                setImageURI(Uri.parse(record.photoUri))
            } catch (_: Exception) {
                setBackgroundColor(Color.LTGRAY)
            }

            setOnClickListener {
                showLargePhoto(record)
            }
        }

        card.addView(imageView)

        val dateFormat =
            SimpleDateFormat("yyyy년 MM월 dd일 HH:mm:ss", Locale.KOREA)

        val direction = directionName(record.azimuth)
        val accuracyText = directionAccuracyText(record.directionAccuracy)

        val information = TextView(this).apply {
            text = """
                
                촬영시각  ${dateFormat.format(Date(record.timestamp))}
                
                위치
                위도  ${String.format(Locale.US, "%.6f", record.latitude)}
                경도  ${String.format(Locale.US, "%.6f", record.longitude)}
                고도  ${String.format(Locale.US, "%.1f", record.altitude)} m
                
                GPS 정확도  ±${record.locationAccuracy.toInt()} m
                
                촬영방향  ${String.format(Locale.US, "%.1f", record.azimuth)}° · $direction
                방향센서 정확도  $accuracyText
                
                이동상태  ${record.transportMode}
                
                사진을 누르면 크게 볼 수 있습니다.
            """.trimIndent()

            textSize = 15f
            setTextColor(Color.rgb(40, 45, 50))
            setPadding(4, 12, 4, 20)
        }

        card.addView(information)

        val divider = TextView(this).apply {
            setBackgroundColor(Color.rgb(225, 228, 232))
        }

        card.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                3
            ).apply {
                setMargins(0, 12, 0, 28)
            }
        )

        return card
    }

    private fun showLargePhoto(record: PhotoRecord) {

        val dialog = Dialog(this)

        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)

            try {
                setImageURI(Uri.parse(record.photoUri))
            } catch (_: Exception) {
            }

            setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.setContentView(imageView)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        dialog.show()

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun directionName(azimuth: Float): String {
        return when {
            azimuth >= 337.5f || azimuth < 22.5f -> "북"
            azimuth < 67.5f -> "북동"
            azimuth < 112.5f -> "동"
            azimuth < 157.5f -> "남동"
            azimuth < 202.5f -> "남"
            azimuth < 247.5f -> "남서"
            azimuth < 292.5f -> "서"
            else -> "북서"
        }
    }

    private fun directionAccuracyText(accuracy: Int): String {
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "높음"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "보통"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "낮음"
            else -> "신뢰도 낮음"
        }
    }
}