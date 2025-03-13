package com.sudotronics.wiiloadandroid

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.Deflater
import kotlin.math.min
import androidx.activity.result.contract.ActivityResultContracts


class MainActivity : AppCompatActivity() {
    private var statusText: TextView? = null
    private var ipAddressField: EditText? = null
    private var selectFileButton: Button? = null
    private var transmitButton: Button? = null
    private var selectedFile: Uri? = null
    private var preferences: SharedPreferences? = null
    private var progressDialog: ProgressDialog? = null
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var themeToggle: ImageButton? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            handleFileSelection(result.data!!.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applyTheme()
        setContentView(R.layout.activity_main)
        initializeViews()
        setupPreferences()
        verifyPermissions()
        setupClickListeners()
    }

    private fun initializeViews() {
        statusText = findViewById<TextView>(R.id.fileNameText)
        ipAddressField = findViewById<EditText>(R.id.wiiIpInput)
        selectFileButton = findViewById<Button>(R.id.browseButton)
        transmitButton = findViewById<Button>(R.id.sendButton)
        transmitButton?.isEnabled = false

        progressDialog = ProgressDialog(this)
        progressDialog!!.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog!!.setCancelable(false)
        themeToggle = findViewById<ImageButton>(R.id.themeToggle)
    }

    private fun setupPreferences() {
        val savedIP = preferences!!.getString(LAST_IP_KEY, "")
        ipAddressField!!.setText(savedIP)
    }

    private fun verifyPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100
            )
        }
    }

    private fun setupClickListeners() {
        selectFileButton!!.setOnClickListener { v: View? -> launchFilePicker() }
        transmitButton!!.setOnClickListener { v: View? -> initiateFileTransfer() }
        themeToggle!!.setOnClickListener { v: View? -> showThemeMenu() }
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("*/*")
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        filePicker.launch(intent)
    }

    private fun handleFileSelection(uri: Uri?) {
        selectedFile = uri
        val fileName = getFileName(uri)
        statusText!!.text = fileName
        transmitButton!!.isEnabled = true
    }

    private fun getFileName(uri: Uri?): String? {
        var result = uri!!.lastPathSegment
        val cut = result!!.lastIndexOf('/')
        if (cut != -1) {
            result = result.substring(cut + 1)
        }
        return result
    }

    private fun initiateFileTransfer() {
        val ipAddress = ipAddressField!!.text.toString().trim { it <= ' ' }
        if (ipAddress.isEmpty()) {
            showMessage("Please enter Wii IP address")
            return
        }

        preferences!!.edit().putString(LAST_IP_KEY, ipAddress).apply()
        startFileTransfer(ipAddress)
    }

    private fun startFileTransfer(ipAddress: String) {
        progressDialog!!.setMessage("Preparing transfer...")
        progressDialog!!.progress = 0
        progressDialog!!.show()

        executorService.execute {
            try {
                val compressedData = compressFileData()
                sendToWii(ipAddress, compressedData)
                showTransferComplete()
            } catch (e: Exception) {
                handleTransferError(e)
            }
        }
    }

    @Throws(IOException::class)
    private fun compressFileData(): ByteArray {
        val compressedStream = ByteArrayOutputStream()
        val deflater = Deflater()
        val buffer = ByteArray(BUFFER_SIZE)

        contentResolver.openInputStream(selectedFile!!).use { inputStream ->
            val totalBytes = inputStream!!.available()
            var bytesRead: Int
            while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
                deflater.setInput(buffer, 0, bytesRead)

                val compressedBuffer =
                    ByteArray(BUFFER_SIZE)
                while (!deflater.needsInput()) {
                    val compressedBytes = deflater.deflate(compressedBuffer)
                    compressedStream.write(compressedBuffer, 0, compressedBytes)
                }

                updateProgress(
                    "Compressing...",
                    (compressedStream.size() * 100L / totalBytes).toInt()
                )
            }

            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                compressedStream.write(buffer, 0, count)
            }
        }
        return compressedStream.toByteArray()
    }

    @Throws(IOException::class)
    private fun sendToWii(ipAddress: String, compressedData: ByteArray) {
        Socket(ipAddress, WII_PORT).use { socket ->
            DataOutputStream(socket.getOutputStream()).use { output ->
                val fileName = getFileName(selectedFile)
                writeHeader(output, fileName, compressedData.size)

                val chunkSize = BUFFER_SIZE
                val totalChunks =
                    (compressedData.size + chunkSize - 1) / chunkSize

                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val length =
                        min(
                            chunkSize.toDouble(),
                            (compressedData.size - start).toDouble()
                        )
                            .toInt()
                    output.write(compressedData, start, length)
                    output.flush()

                    updateProgress("Sending file...", (i + 1) * 100 / totalChunks)
                }

                output.writeBytes(fileName + '\u0000')
                output.flush()
            }
        }
    }

    @Throws(IOException::class)
    private fun writeHeader(output: DataOutputStream, fileName: String?, compressedLength: Int) {
        output.writeBytes("1027") // magic number
        output.writeByte(0) // max version
        output.writeByte(5) // min version
        output.writeShort(fileName!!.length + 1)
        output.writeInt(compressedLength)

        contentResolver.openInputStream(selectedFile!!).use { inputStream ->
            output.writeInt(
                inputStream!!.available()
            ) // original size
        }
    }

    private fun updateProgress(message: String, progress: Int) {
        mainHandler.post {
            progressDialog!!.setMessage(message)
            progressDialog!!.progress = progress
        }
    }

    private fun showTransferComplete() {
        mainHandler.post {
            progressDialog!!.dismiss()
            showMessage("Transfer completed successfully!")
        }
    }

    private fun handleTransferError(e: Exception) {
        mainHandler.post {
            progressDialog!!.dismiss()
            showMessage("Transfer failed: " + e.message)
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showThemeMenu() {
        val popup = PopupMenu(this, themeToggle)
        popup.menuInflater.inflate(R.menu.theme_menu, popup.menu)

        popup.setOnMenuItemClickListener { item: MenuItem ->
            val itemId = item.itemId
            if (itemId == R.id.menu_light_mode) {
                setThemeMode(AppCompatDelegate.MODE_NIGHT_NO)
                return@setOnMenuItemClickListener true
            } else if (itemId == R.id.menu_dark_mode) {
                setThemeMode(AppCompatDelegate.MODE_NIGHT_YES)
                return@setOnMenuItemClickListener true
            }
            false
        }

        popup.show()
    }

    private fun setThemeMode(mode: Int) {
        preferences!!.edit().putInt(THEME_PREF_KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun applyTheme() {
        val savedTheme =
            preferences!!.getInt(THEME_PREF_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedTheme)
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService.shutdown()
    }

    companion object {
        private const val WII_PORT = 4299
        private const val BUFFER_SIZE = 8192
        private const val PREFS_NAME = "BeamUPrefs"
        private const val LAST_IP_KEY = "lastUsedIP"
        private const val THEME_PREF_KEY = "theme_mode"
    }
}