package com.d7.gearremote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var debugView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val prefs = getSharedPreferences(RemoteService.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(s: String, size: Float = 15f) = TextView(this).apply {
            text = s
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }

        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(text("Gear Remote", 26f).apply { gravity = Gravity.CENTER })

        statusView = text("", 18f)
        debugView = text("—", 13f)
        root.addView(statusView)
        root.addView(debugView)

        root.addView(button("١) منح صلاحية البلوتوث") { requestBle() })
        root.addView(button("٢) تفعيل الخدمة من إعدادات إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("إذا الخدمة مقفلة: افتح معلومات التطبيق ⋮ ← السماح بالإعدادات المقيدة") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("٣) إعادة الاتصال بالريموت") {
            val s = RemoteService.instance
            if (s == null) toast("فعّل الخدمة أول (الخطوة ٢)") else s.reconnectNow()
        })

        root.addView(Switch(this).apply {
            text = "عكس اتجاه السحب للأعلى/الأسفل"
            isChecked = prefs.getBoolean(RemoteService.PREF_INVERT, false)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(RemoteService.PREF_INVERT, c).apply() }
        })

        val sensLabel = text("")
        fun updateSensLabel(v: Int) { sensLabel.text = "مسافة السحب المطلوبة: $v (أقل = أحسّ)" }
        val current = prefs.getInt(RemoteService.PREF_SENSITIVITY, 60)
        updateSensLabel(current)
        root.addView(sensLabel)
        root.addView(SeekBar(this).apply {
            max = 150
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p.coerceAtLeast(20)
                    updateSensLabel(v)
                    prefs.edit().putInt(RemoteService.PREF_SENSITIVITY, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        root.addView(text(
            "الأزرار:\n" +
                "• سحب للأعلى على اللوحة ← الريل التالي\n" +
                "• سحب للأسفل ← الريل السابق\n" +
                "• سحب يمين/يسار ← تقليب الستوري\n" +
                "• ضغط اللوحة ← إيقاف/تشغيل\n" +
                "• الزناد (تحت) ← لايك (ضغطتين)\n" +
                "• + و − ← الصوت (اضغط مطوّل للتكرار)\n" +
                "• زر الرجوع ← رجوع، زر البيت ← الشاشة الرئيسية"
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        RemoteService.statusListener = { statusView.text = it }
        RemoteService.debugListener = { debugView.text = it }
        refreshStatus()
    }

    private fun refreshStatus() {
        statusView.text = when {
            !RemoteService.hasBlePermissions(this) -> "⚠️ صلاحية البلوتوث ناقصة (الخطوة ١)"
            RemoteService.instance == null -> "⚠️ الخدمة مو مفعّلة (الخطوة ٢)"
            else -> RemoteService.status
        }
    }

    override fun onPause() {
        super.onPause()
        RemoteService.statusListener = null
        RemoteService.debugListener = null
    }

    private fun requestBle() {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (RemoteService.hasBlePermissions(this)) {
            toast("تم ✅")
            RemoteService.instance?.reconnectNow()
        } else {
            toast("لازم توافق على الصلاحية")
        }
        refreshStatus()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
