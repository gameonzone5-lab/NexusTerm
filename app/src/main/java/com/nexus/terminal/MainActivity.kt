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

                val isLinuxReady = File(filesDir, "linux/etc").exists()

                when {
                    command == "setup-linux" -> setupLinuxEnvironment()
                    command == "permit" -> requestStoragePermission()
                    command == "clear" -> tvOutput.text = ""
                    command.startsWith("cd ") -> handleCdCommand(command)
                    // টারমাক্স প্রবাহ: লিনাক্স ইন্সটল থাকলে apt, pip বা যেকোনো কমান্ড সরাসরি রান হবে
                    isLinuxReady -> runLinuxCommand(command)
                    else -> executeCommand(command)
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
                runOnUiThread { tvOutput.append("[*] Downloading Termux Ubuntu (APT) Environment...\n") }
                val linuxDir = File(filesDir, "linux")
                if (!linuxDir.exists()) linuxDir.mkdirs()

                val tarFile = File(filesDir, "rootfs.tar.gz")
                // রিলায়েবল গিটহাব অ্যান্ডরোনিক্স মিরর
                downloadFile("https://raw.githubusercontent.com/AndronixApp/RootfsArchiver/master/Ubuntu20/ubuntu20-rootfs-arm64.tar.gz", tarFile.absolutePath)
                
                runOnUiThread { tvOutput.append("[*] Extracting Ubuntu RootFS (APT)...\n") }
                Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath)).waitFor()
                tarFile.delete()

                // ইন্টারনেট কানেকশন চালুর জন্য DNS কনফিগার
                val resolvConf = File(linuxDir, "etc/resolv.conf")
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

                runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu ready! Try typing: apt update\n") }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun runLinuxCommand(cmd: String) {
        Thread {
            try {
                val proot = File(filesDir, "proot")
                if (!proot.exists() || proot.length() < 500000) {
                     downloadFile("https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static", proot.absolutePath)
                }
                // রানটাইমে এক্সিকিউট পারমিশন জোরপূর্বক দেওয়া (SDK 28 এর সাথে এটি কাজ করবেই)
                proot.setExecutable(true, false)
                
                val pb = ProcessBuilder(
                    proot.absolutePath, "-0", "--link2symlink",
                    "-b", "/sdcard:/sdcard", "-r", File(filesDir, "linux").absolutePath, 
                    "/usr/bin/env", "PATH=/bin:/usr/bin:/sbin:/usr/sbin", 
                    "/bin/sh", "-c", cmd
                )
                pb.redirectErrorStream(true)
                val p = pb.start()
                p.inputStream.bufferedReader().useLines { lines -> 
                    lines.forEach { runOnUiThread { tvOutput.append("$it\n") } } 
                }
            } catch (e: Exception) { 
                runOnUiThread { tvOutput.append("Linux Error: ${e.message}\n") } 
            }
        }.start()
    }

    private fun downloadFile(url: String, path: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.inputStream.use { input -> FileOutputStream(path).use { output -> input.copyTo(output) } }
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
