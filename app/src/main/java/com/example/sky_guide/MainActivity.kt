package com.example.sky_guide

import CelestialDataResponse
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.Task
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var azimuth: Int = 0
    private val REQUEST_BLUETOOTH_PERMISSIONS = 1

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // HC-06 UUID
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkBluetoothPermissions()

        // Initialize SensorManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Initialize Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // UI Elements
        val connectButton = findViewById<Button>(R.id.connectButton)
        val sendButton = findViewById<Button>(R.id.sendButton)

        // Connect to Bluetooth
        connectButton.setOnClickListener {
            connectToBluetooth("eeehooo")
        }

        // Send Compass Data via Bluetooth
        sendButton.setOnClickListener {
            sendData("$azimuth")
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        // Check for location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        } else {
            getLocationAndQueryAPI()
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        closeBluetoothConnection()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values
        }

        if (gravity != null && geomagnetic != null) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                var azimuthF = Math.toDegrees(orientation[0].toDouble()).toFloat()
                azimuthF = (azimuthF + 360) % 360
                azimuth = azimuthF.toInt()
                if (azimuth <= 180) {
                    azimuth = -azimuth
                } else {
                    azimuth = 360 - azimuth
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for now
    }

    private fun connectToBluetooth(deviceName: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            showToast("Please enable Bluetooth")
            return
        }

        val device: BluetoothDevice? = bluetoothAdapter!!.bondedDevices.find { it.name == deviceName }
        if (device == null) {
            showToast("Device $deviceName not found")
            return
        }

        Thread {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket?.connect()
                runOnUiThread { showToast("Connected to $deviceName") }
            } catch (e: IOException) {
                runOnUiThread { showToast("Connection failed: ${e.message}") }
            }
        }.start()
    }

    private fun sendData(data: String) {
        if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
            showToast("Bluetooth is not connected")
            return
        }

        Thread {
            try {
                bluetoothSocket?.outputStream?.write(data.toByteArray())
                runOnUiThread { showToast("Data sent: $data") }
            } catch (e: IOException) {
                runOnUiThread { showToast("Failed to send data: ${e.message}") }
            }
        }.start()
    }

    private fun closeBluetoothConnection() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            // Handle silently
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkBluetoothPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val denied = grantResults.indices.filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "Bluetooth permissions are required for this app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryCelestialAPI(date: String, time: String, latitude: Double, longitude: Double) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://aa.usno.navy.mil/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(OkHttpClient())
            .build()

        val service = retrofit.create(CelestialApiService::class.java)
        val coords = "$latitude,$longitude"

        service.getCelestialData(date, time, coords).enqueue(object :
            Callback<CelestialDataResponse> {
            override fun onResponse(
                call: Call<CelestialDataResponse>,
                response: Response<CelestialDataResponse>
            ) {
                if (response.isSuccessful) {
                    val celestialData = response.body()?.properties?.data
                    val sunData = celestialData?.find { it.`object` == "Sun" }
                    if (sunData != null) {
                        val hc = sunData.almanac_data.hc
                        val zn = sunData.almanac_data.zn
                        Toast.makeText(this@MainActivity, "Sun: hc=$hc, zn=$zn", Toast.LENGTH_LONG).show()
                        // Send data via Bluetooth or display it
                    } else {
                        Toast.makeText(this@MainActivity, "Sun data not found!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "API Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<CelestialDataResponse>, t: Throwable) {
                Toast.makeText(this@MainActivity, "API Call Failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun getLocationAndQueryAPI() {
        val locationTask: Task<Location> = fusedLocationProviderClient.lastLocation
        locationTask.addOnSuccessListener { location ->
            if (location != null) {
                val latitude = location.latitude
                val longitude = location.longitude

                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                queryCelestialAPI(currentDate, currentTime, latitude, longitude)
            } else {
                Toast.makeText(this, "Failed to get location.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}