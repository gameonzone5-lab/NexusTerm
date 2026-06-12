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

                when {
                    command == "setup-linux" -> setupLinuxEnvironment()
                    command.startsWith("apt ") || command.startsWith("linux ") -> runLinuxCommand(command.removePrefix("linux ").trim())
                    command.startsWith("cd ") -> handleCdCommand(command)
                    else -> executeCommand(command)
                }
            }
        }
    }

    private fun setupLinuxEnvironment() {
        Thread {
            try {
                runOnUiThread { tvOutput.append("[*] Downloading Termux-based Ubuntu environment...\n") }
                val linuxDir = File(filesDir, "linux")
                if (!linuxDir.exists()) linuxDir.mkdirs()

                // নতুন রিলায়েবল মিরর
                val tarFile = File(filesDir, "rootfs.tar.gz")
                downloadFile("https://raw.githubusercontent.com/AndronixApp/RootfsArchiver/master/Ubuntu20/ubuntu20-rootfs-arm64.tar.gz", tarFile.absolutePath)
                
                runOnUiThread { tvOutput.append("[*] Extracting (This takes a moment)...\n") }
                Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath)).waitFor()
                tarFile.delete()

                runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu ready! Try: apt update\n") }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] ${e.message}\n") }
            }
        }.start()
    }

    private fun runLinuxCommand(cmd: String) {
        Thread {
            try {
                val proot = File(filesDir, "proot")
                if (!proot.exists()) {
                     downloadFile("https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static", proot.absolutePath)
                     proot.setExecutable(true)
                }
                val pb = ProcessBuilder(proot.absolutePath, "-0", "-b", "/sdcard:/sdcard", "-r", File(filesDir, "linux").absolutePath, "/bin/sh", "-c", cmd)
                pb.redirectErrorStream(true)
                val p = pb.start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { runOnUiThread { tvOutput.append("$it\n") } } }
            } catch (e: Exception) { runOnUiThread { tvOutput.append("Err: ${e.message}\n") } }
        }.start()
    }

    private fun downloadFile(url: String, path: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.inputStream.use { input -> FileOutputStream(path).use { output -> input.copyTo(output) } }
    }

    private fun handleCdCommand(cmd: String) { /* আগের লজিক */ }
    private fun executeCommand(cmd: String) { /* আগের লজিক */ }
}
