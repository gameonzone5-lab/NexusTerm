package com.nexus.terminal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    
    private var currentDirectory: String = ""
    private var permissionChecked: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnRun = findViewById(R.id.btnRun)

        currentDirectory = filesDir.absolutePath

        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                if (command.startsWith("cd ") || command == "cd") {
                    handleCdCommand(command)
                } else if (command == "clear") {
                    tvOutput.text = ""
                    checkPermission()
                } else if (command == "permit") {
                    requestStoragePermission()
                } else if (command == "setup-linux") {
                    setupLinuxEnvironment()
                } else if (command.startsWith("linux ")) {
                    runLinuxCommand(command.removePrefix("linux ").trim())
                } else {
                    executeCommand(command)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!permissionChecked) checkPermission()
    }

    private fun checkPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                tvOutput.append("\n[WARNING] Full Storage Permission Not Granted! Type 'permit' to allow.\n")
                permissionChecked = false
            } else {
                if (!permissionChecked) {
                    tvOutput.append("\n[SUCCESS] Storage Ready! Type 'setup-linux' to install AI environment.\n")
                    permissionChecked = true
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse(String.format("package:%s", packageName))
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun handleCdCommand(command: String) {
        val newDir = command.removePrefix("cd").trim()
        if (newDir.isEmpty() || newDir == "~") {
            currentDirectory = filesDir.absolutePath
            return
        }
        var targetPath = newDir
        if (newDir == "/sdcard" || newDir.startsWith("/sdcard/")) {
            targetPath = newDir.replace("/sdcard", Environment.getExternalStorageDirectory().absolutePath)
        }
        val targetFile = if (targetPath.startsWith("/")) File(targetPath) else File(currentDirectory, targetPath)
        if (targetFile.exists() && targetFile.isDirectory) {
            currentDirectory = targetFile.canonicalPath
        } else {
            tvOutput.append("cd: $newDir: Directory not found\n")
        }
    }

    private fun setupLinuxEnvironment() {
        Thread {
            try {
                runOnUiThread { tvOutput.append("[*] Starting Linux Setup (Requires Internet)...\n") }
                val baseDir = filesDir.absolutePath
                val linuxDir = File(baseDir, "linux")
                if (!linuxDir.exists()) linuxDir.mkdirs()

                val prootFile = File(baseDir, "proot")
                if (!prootFile.exists()) {
                    runOnUiThread { tvOutput.append("[*] Downloading PRoot Engine...\n") }
                    downloadFile("https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static", prootFile.absolutePath)
                    Runtime.getRuntime().exec(arrayOf("chmod", "+x", prootFile.absolutePath)).waitFor()
                }

                val rootfsTar = File(baseDir, "rootfs.tar.gz")
                if (!File(linuxDir, "bin").exists()) {
                    runOnUiThread { tvOutput.append("[*] Downloading Alpine Linux RootFS (Super Fast)...\n") }
                    downloadFile("https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/aarch64/alpine-minirootfs-3.18.4-aarch64.tar.gz", rootfsTar.absolutePath)
                    
                    runOnUiThread { tvOutput.append("[*] Extracting File System...\n") }
                    Runtime.getRuntime().exec(arrayOf("tar", "-xf", rootfsTar.absolutePath, "-C", linuxDir.absolutePath)).waitFor()
                    rootfsTar.delete()
                }

                runOnUiThread { tvOutput.append("[SUCCESS] Linux Installed!\nTest it by typing: linux cat /etc/os-release\n") }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun downloadFile(urlString: String, destPath: String) {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connect()
        conn.inputStream.use { input ->
            FileOutputStream(destPath).use { output ->
                val data = ByteArray(4096)
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    output.write(data, 0, count)
                }
            }
        }
    }

    private fun runLinuxCommand(linuxCmd: String) {
        val baseDir = filesDir.absolutePath
        val prootBinary = "$baseDir/proot"
        val rootfsDir = "$baseDir/linux"
        val sdcard = Environment.getExternalStorageDirectory().absolutePath

        if (!File(prootBinary).exists()) {
            tvOutput.append("Linux not installed! Type 'setup-linux' first.\n")
            return
        }

        // PRoot কমান্ড: অ্যান্ড্রয়েডের ভেতরে লিনাক্স রান করানো এবং মেমোরি কার্ড বাইন্ড করা
        val fullCmd = "$prootBinary -b $sdcard:/sdcard -r $rootfsDir -w /root /bin/sh -c \"$linuxCmd\""
        executeCommand(fullCmd)
    }

    private fun executeCommand(command: String) {
        Thread {
            try {
                val workingDir = File(currentDirectory)
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command), null, workingDir)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
                while (errorReader.readLine().also { line = it } != null) output.append(line).append("\n")

                process.waitFor()
                runOnUiThread { if (output.isNotEmpty()) tvOutput.append(output.toString()) }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("Error: ${e.message}\n") }
            }
        }.start()
    }
}
