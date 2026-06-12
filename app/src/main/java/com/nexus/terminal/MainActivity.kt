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
            val rawCommand = etInput.text.toString()
            val command = rawCommand.trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                val isLinuxReady = File(filesDir, "linux/etc").exists()

                if (command.startsWith("cd ") || command == "cd") {
                    handleCdCommand(command)
                } else if (command == "clear") {
                    tvOutput.text = ""
                    checkPermission()
                } else if (command == "permit") {
                    requestStoragePermission()
                } else if (command == "setup-linux") {
                    setupLinuxEnvironment()
                } else if (isLinuxReady) {
                    // টারমাক্সের মতো জাদুকরী ফিচার: যেকোনো কমান্ড এখন সরাসরি Ubuntu-তে রান হবে!
                    runLinuxCommand(command)
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
                    tvOutput.append("\n[SUCCESS] Storage Ready! Type 'setup-linux' to install Ubuntu (APT) environment.\n")
                    permissionChecked = true
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
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
                val baseDir = filesDir.absolutePath
                val linuxDir = File(baseDir, "linux")
                val prootFile = File(baseDir, "proot")

                runOnUiThread { tvOutput.append("[*] Preparing pure Ubuntu APT Environment...\n") }

                // আগের Alpine Linux মুছে ফেলার লজিক
                if (linuxDir.exists() && File(linuxDir, "sbin/apk").exists()) {
                    runOnUiThread { tvOutput.append("[*] Removing old Alpine system...\n") }
                    linuxDir.deleteRecursively()
                }
                if (!linuxDir.exists()) linuxDir.mkdirs()

                if (!prootFile.exists() || prootFile.length() < 500000) {
                    runOnUiThread { tvOutput.append("[*] Downloading PRoot Engine...\n") }
                    downloadFile("https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static", prootFile.absolutePath)
                    prootFile.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("chmod", "+x", prootFile.absolutePath)).waitFor()
                }

                val rootfsTar = File(baseDir, "rootfs.tar.gz")
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[*] Downloading Official Ubuntu Base (Please wait, ~25MB)...\n") }
                    // 100% রিলায়েবল Ubuntu Base URL
                    downloadFile("https://old-releases.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.1-base-arm64.tar.gz", rootfsTar.absolutePath)
                    
                    runOnUiThread { tvOutput.append("[*] Extracting Ubuntu File System...\n") }
                    val extProcess = Runtime.getRuntime().exec(arrayOf("tar", "-xf", rootfsTar.absolutePath, "-C", linuxDir.absolutePath))
                    extProcess.waitFor()
                    
                    if (extProcess.exitValue() == 0) {
                        rootfsTar.delete()
                        
                        // APT আপডেট যাতে ঠিকমতো কাজ করে তার জন্য DNS ফিক্স
                        val resolvConf = File(linuxDir, "etc/resolv.conf")
                        resolvConf.parentFile.mkdirs()
                        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

                        runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu (APT) Installed Perfectly!\nTest it by typing: apt update\n") }
                    } else {
                        runOnUiThread { tvOutput.append("[ERROR] Extraction failed.\n") }
                    }
                } else {
                    runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu APT is already installed and ready!\n") }
                }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun downloadFile(urlString: String, destPath: String) {
        var url = URL(urlString)
        var conn: HttpURLConnection
        var redirect: Boolean
        do {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                redirect = true
                url = URL(conn.getHeaderField("Location"))
            } else {
                redirect = false
            }
        } while (redirect)

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

        Thread {
            try {
                // Ubuntu-র ভেতর কমান্ড রান করানোর ম্যাজিক
                val pb = ProcessBuilder(
                    prootBinary, "-0", "--link2symlink", 
                    "-b", "$sdcard:/sdcard", "-b", "$baseDir:$baseDir", 
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-r", rootfsDir, "-w", currentDirectory, 
                    "/usr/bin/env", "PATH=/bin:/usr/bin:/sbin:/usr/sbin", 
                    "/bin/sh", "-c", linuxCmd
                )
                pb.redirectErrorStream(true)
                val process = pb.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                process.waitFor()
                runOnUiThread {
                    if (output.isNotEmpty()) tvOutput.append(output.toString())
                    else tvOutput.append("[Executed successfully, no output]\n")
                }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("Ubuntu Error: ${e.message}\n") }
            }
        }.start()
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
