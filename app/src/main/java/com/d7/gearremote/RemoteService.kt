package com.d7.gearremote

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Accessibility service that:
 *  1. Connects to the Samsung Gear VR Controller over BLE (custom GATT protocol, not HID).
 *  2. Puts it in sensor mode so it streams touchpad + button data.
 *  3. Turns that data into screen swipes / taps / volume / back actions.
 *
 * Protocol reverse-engineered by jsyang (gearvr-controller-webbluetooth).
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class RemoteService : AccessibilityService() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4f63756c-7573-2054-6872-65656d6f7465")
        val NOTIFY_UUID: UUID = UUID.fromString("c8c51726-81bc-483b-a052-f7a14ea3d281")
        val WRITE_UUID: UUID = UUID.fromString("c8c51726-81bc-483b-a052-f7a14ea3d282")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val CMD_SENSOR = byteArrayOf(0x01, 0x00)
        val CMD_VR_MODE = byteArrayOf(0x08, 0x00)

        // Button bits in byte 58
        const val BTN_TRIGGER = 0x01
        const val BTN_HOME = 0x02
        const val BTN_BACK = 0x04
        const val BTN_TOUCHPAD = 0x08
        const val BTN_VOL_UP = 0x10
        const val BTN_VOL_DOWN = 0x20

        const val PREFS = "prefs"
        const val PREF_INVERT = "invert"
        const val PREF_SENSITIVITY = "sensitivity"

        var instance: RemoteService? = null
            private set

        @Volatile
        var status: String = "الخدمة غير مفعّلة"
            private set

        var statusListener: ((String) -> Unit)? = null
        var debugListener: ((String) -> Unit)? = null

        private val uiHandler = Handler(Looper.getMainLooper())

        fun setStatus(s: String) {
            status = s
            uiHandler.post { statusListener?.invoke(s) }
        }

        fun hasBlePermissions(ctx: Context): Boolean {
            val g = PackageManager.PERMISSION_GRANTED
            return if (Build.VERSION.SDK_INT >= 31) {
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == g &&
                    ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == g
            } else {
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == g
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var ready = false
    private var gotData = false
    private var preferScan = false
    private var lastPacketAt = 0L
    private var altMode = false
    private var reconnectDelay = 2000L

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private var writeStartedAt = 0L

    // Input state
    private var prevButtons = 0
    private var touching = false
    private var gestureFired = false
    private var startX = 0
    private var startY = 0
    private var volHeldSince = 0L
    private var lastVolAdjust = 0L
    private var lastDebugAt = 0L

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setStatus("الخدمة شغالة")
        handler.post { startConnect() }
        handler.postDelayed(watchdog, 2000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disconnect()
        instance = null
        setStatus("الخدمة غير مفعّلة")
        super.onDestroy()
    }

    fun reconnectNow() {
        handler.post {
            reconnectDelay = 2000L
            disconnect()
            startConnect()
        }
    }

    // ---------------------------------------------------------------- connection

    private val reconnectRunnable = Runnable { startConnect() }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, reconnectDelay)
        reconnectDelay = min(reconnectDelay * 2, 30_000L)
    }

    private fun startConnect() {
        handler.removeCallbacks(reconnectRunnable)
        if (gatt != null || scanning) return
        if (!hasBlePermissions(this)) {
            setStatus("افتح التطبيق وامنح صلاحية البلوتوث")
            return
        }
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            setStatus("البلوتوث مقفل، شغّله")
            scheduleReconnect()
            return
        }

        val bonded = ad.bondedDevices?.firstOrNull { it.name?.contains("Gear VR", true) == true }
        if (bonded != null && !preferScan) {
            connect(bonded)
            return
        }

        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            scheduleReconnect()
            return
        }
        setStatus("أدوّر على الريموت... اضغط زر Home فيه ضغطة وحدة")
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
            handler.postDelayed(scanTimeout, 20_000)
        } catch (e: Exception) {
            scanning = false
            setStatus("فشل البحث: ${e.message}")
            scheduleReconnect()
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            preferScan = false
            setStatus("ما لقيت الريموت، بحاول مرة ثانية...")
            scheduleReconnect()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (name.contains("Gear VR", ignoreCase = true)) {
                handler.post {
                    if (gatt == null && scanning) {
                        stopScan()
                        connect(result.device)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                setStatus("فشل البحث (كود $errorCode)")
                scheduleReconnect()
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun connect(device: BluetoothDevice) {
        setStatus("جاري الاتصال بـ ${device.name ?: device.address}...")
        ready = false
        gotData = false
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        stopScan()
        writeQueue.clear()
        writeBusy = false
        ready = false
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        writeChar = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            handler.post {
                if (g != gatt) {
                    try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    setStatus("اتصل، جاري التهيئة...")
                    // Packets are 60 bytes, larger than the default 20-byte payload.
                    if (!g.requestMtu(247)) g.discoverServices()
                } else {
                    val wasWorking = gotData
                    disconnect()
                    if (wasWorking) reconnectDelay = 2000L else preferScan = !preferScan
                    setStatus("انقطع الاتصال، بعيد المحاولة تلقائياً...")
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            handler.post { if (g == gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post { if (g == gatt) setupNotifications(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            handler.post {
                if (g != gatt) return@post
                ready = true
                lastPacketAt = SystemClock.uptimeMillis()
                setStatus("حرّك الريموت أو المس اللوحة...")
                enqueue(CMD_SENSOR)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            handler.post {
                writeBusy = false
                pump()
            }
        }

        // Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: return
            handler.post { onPacket(v) }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            handler.post { onPacket(v) }
        }
    }

    private fun setupNotifications(g: BluetoothGatt) {
        val svc = g.getService(SERVICE_UUID)
        val notify = svc?.getCharacteristic(NOTIFY_UUID)
        val write = svc?.getCharacteristic(WRITE_UUID)
        if (notify == null || write == null) {
            setStatus("الجهاز المتصل مو ريموت Gear VR")
            disconnect()
            preferScan = true
            scheduleReconnect()
            return
        }
        writeChar = write
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            ready = true
            lastPacketAt = SystemClock.uptimeMillis()
            enqueue(CMD_SENSOR)
            return
        }
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, v)
        } else {
            cccd.value = v
            g.writeDescriptor(cccd)
        }
    }

    private fun enqueue(cmd: ByteArray) {
        writeQueue.add(cmd)
        pump()
    }

    private fun pump() {
        if (writeBusy) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val cmd = writeQueue.poll() ?: return
        writeBusy = true
        writeStartedAt = SystemClock.uptimeMillis()
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = cmd
            g.writeCharacteristic(c)
        }
        if (!ok) {
            writeBusy = false
            writeQueue.addFirst(cmd)
            handler.postDelayed({ pump() }, 300)
        }
    }

    /** Re-sends the start command if the controller goes quiet; unsticks a stalled write. */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (writeBusy && now - writeStartedAt > 2000) {
                writeBusy = false
                pump()
            }
            if (ready && now - lastPacketAt > 2500 && writeQueue.isEmpty()) {
                altMode = !altMode
                if (altMode) enqueue(CMD_VR_MODE)
                enqueue(CMD_SENSOR)
                lastPacketAt = now
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ---------------------------------------------------------------- input

    private fun onPacket(d: ByteArray) {
        if (d.size < 59) return
        val now = SystemClock.uptimeMillis()
        lastPacketAt = now
        if (!gotData) {
            gotData = true
            reconnectDelay = 2000L
            setStatus("✅ متصل وجاهز")
        }

        val x = (((d[54].toInt() and 0xF) shl 6) + ((d[55].toInt() and 0xFC) shr 2)) and 0x3FF
        val y = (((d[55].toInt() and 0x3) shl 8) + (d[56].toInt() and 0xFF)) and 0x3FF
        val buttons = d[58].toInt() and 0x3F
        val pressed = buttons and prevButtons.inv()
        prevButtons = buttons

        if (now - lastDebugAt > 120) {
            lastDebugAt = now
            val msg = "X=$x  Y=$y  أزرار=${Integer.toBinaryString(buttons).padStart(6, '0')}"
            uiHandler.post { debugListener?.invoke(msg) }
        }

        // Buttons
        if ((pressed and BTN_TRIGGER) != 0) tapCenter(2)          // like
        if ((pressed and BTN_TOUCHPAD) != 0) tapCenter(1)         // pause / play
        if ((pressed and BTN_BACK) != 0) performGlobalAction(GLOBAL_ACTION_BACK)
        if ((pressed and BTN_HOME) != 0) performGlobalAction(GLOBAL_ACTION_HOME)

        handleVolume(pressed, buttons, BTN_VOL_UP, AudioManager.ADJUST_RAISE, now)
        handleVolume(pressed, buttons, BTN_VOL_DOWN, AudioManager.ADJUST_LOWER, now)

        // Touchpad swipes (0..~315 on each axis, 0/0 = finger lifted)
        val isTouching = x != 0 || y != 0
        if (!isTouching) {
            touching = false
            return
        }
        if (!touching) {
            touching = true
            gestureFired = false
            startX = x
            startY = y
            return
        }
        if ((buttons and BTN_TOUCHPAD) != 0) gestureFired = true // clicking, not swiping
        if (gestureFired) return

        val threshold = prefs().getInt(PREF_SENSITIVITY, 60)
        val dx = x - startX
        val dy = y - startY
        if (abs(dy) > threshold && abs(dy) > abs(dx)) {
            gestureFired = true
            onVerticalSwipe(fingerMovedUp = dy < 0)
        } else if (abs(dx) > threshold && abs(dx) > abs(dy)) {
            gestureFired = true
            onHorizontalSwipe(fingerMovedRight = dx > 0)
        }
    }

    private fun handleVolume(pressed: Int, held: Int, bit: Int, dir: Int, now: Long) {
        if ((pressed and bit) != 0) {
            adjustVolume(dir)
            volHeldSince = now
            lastVolAdjust = now
        } else if ((held and bit) != 0 && now - volHeldSince > 500 && now - lastVolAdjust > 150) {
            adjustVolume(dir)
            lastVolAdjust = now
        }
    }

    private fun adjustVolume(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun onVerticalSwipe(fingerMovedUp: Boolean) {
        val up = if (prefs().getBoolean(PREF_INVERT, false)) !fingerMovedUp else fingerMovedUp
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val h = m.heightPixels.toFloat()
        if (up) swipe(cx, h * 0.72f, cx, h * 0.28f)   // next reel
        else swipe(cx, h * 0.28f, cx, h * 0.72f)      // previous reel
    }

    private fun onHorizontalSwipe(fingerMovedRight: Boolean) {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val cy = m.heightPixels / 2f
        if (fingerMovedRight) swipe(w * 0.2f, cy, w * 0.8f, cy)
        else swipe(w * 0.8f, cy, w * 0.2f, cy)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 160L) {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun tapCenter(times: Int) {
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val b = GestureDescription.Builder()
        for (i in 0 until times) {
            val p = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 1, cy + 1)
            }
            b.addStroke(GestureDescription.StrokeDescription(p, i * 130L, 50L))
        }
        dispatchGesture(b.build(), null, null)
    }
}
