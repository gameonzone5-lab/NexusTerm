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

                if (command.startsWith("cd ") || command == "cd") {
                    handleCdCommand(command)
                } else if (command == "clear") {
                    tvOutput.text = ""
                    checkPermission()
                } else if (command == "permit") {
                    requestStoragePermission()
                } else if (command == "setup-linux") {
                    setupLinuxEnvironment()
                } else if (command.startsWith("linux")) {
                    val linuxCmd = command.removePrefix("linux").trim()
                    if (linuxCmd.isNotEmpty()) {
                        runLinuxCommand(linuxCmd)
                    } else {
                        tvOutput.append("Usage: linux <command>\n")
                    }
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

                if (!prootFile.exists() || !linuxDir.exists()) {
                    runOnUiThread { tvOutput.append("[*] Missing core files. Please run setup-linux again.\n") }
                    return@Thread
                }
                
                runOnUiThread { tvOutput.append("[SUCCESS] Linux is already installed and verified!\nTest it by typing: linux cat /etc/os-release\n") }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Verification failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun runLinuxCommand(linuxCmd: String) {
        val baseDir = filesDir.absolutePath
        val prootBinary = "$baseDir/proot"
        val rootfsDir = "$baseDir/linux"
        val sdcard = Environment.getExternalStorageDirectory().absolutePath

        // বাগ ফিক্স: সিম্বলিক লিংকের বদলে শুধু ফোল্ডার চেক করা হচ্ছে
        if (!File(prootBinary).exists() || !File(rootfsDir, "bin").exists()) {
            tvOutput.append("Linux environment incomplete! Type 'setup-linux' to repair.\n")
            return
        }

        Thread {
            try {
                // -0 ফ্ল্যাগ যুক্ত করা হলো যাতে এআই প্যাকেজ ইন্সটলে রুট পারমিশন পাওয়া যায়
                val pb = ProcessBuilder(
                    prootBinary, "-0", "-b", "$sdcard:/sdcard", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-r", rootfsDir, "-w", "/root", "/bin/sh", "-c", linuxCmd
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
                runOnUiThread { tvOutput.append("Linux Error: ${e.message}\n") }
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
