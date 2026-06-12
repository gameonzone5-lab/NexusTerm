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

                val isLinuxReady = File(filesDir, "linux/usr/bin/apt").exists()

                when {
                    command == "setup-linux" -> setupLinuxEnvironment()
                    command == "permit" -> requestStoragePermission()
                    command == "clear" -> { tvOutput.text = ""; checkPermission() }
                    command.startsWith("cd ") -> handleCdCommand(command)
                    isLinuxReady -> runLinuxCommand(command)
                    else -> executeCommand(command)
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
                tvOutput.append("\n[WARNING] Full Storage Permission Not Granted!\nType 'permit' and press RUN to allow.\n")
                permissionChecked = false
            } else {
                if (!permissionChecked) {
                    tvOutput.append("\n[SUCCESS] Storage Ready! Type 'setup-linux' to install pure Termux-like Ubuntu (APT).\n")
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
                val linuxDir = File(filesDir, "linux")
                // যদি আগে থেকে ফোল্ডার থাকে, তবে আর ডাউনলোড করবে না
                if (File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu is already installed and ready!\nType: apt update\n") }
                    return@Thread
                }

                runOnUiThread { tvOutput.append("[*] Downloading Official Ubuntu Base (APT)...\n") }
                if (!linuxDir.exists()) linuxDir.mkdirs()

                val tarFile = File(filesDir, "rootfs.tar.gz")
                downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                
                runOnUiThread { tvOutput.append("[*] Extracting (Ignoring Android device node warnings)...\n") }
                val extProcess = Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath))
                extProcess.waitFor()
                
                if (File(linuxDir, "usr/bin/apt").exists()) {
                    tarFile.delete()
                    val resolvConf = File(linuxDir, "etc/resolv.conf")
                    resolvConf.parentFile.mkdirs()
                    resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    
                    runOnUiThread { tvOutput.append("[SUCCESS] Termux-like Ubuntu is ready!\nType: apt update\n") }
                } else {
                    runOnUiThread { tvOutput.append("[ERROR] Critical extraction failed. Core files missing.\n") }
                }
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
                proot.setExecutable(true, false)
                
                // টারমাক্স ট্রিক: অ্যাপের নিজস্ব টেম্পোরারি ডিরেক্টরি তৈরি করা
                val tmpDir = File(filesDir, "tmp")
                if (!tmpDir.exists()) tmpDir.mkdirs()

                val rootfs = File(filesDir, "linux").absolutePath
                
                val commandList = listOf(
                    proot.absolutePath, "-0", "--link2symlink",
                    "-b", "/sdcard:/sdcard", 
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-r", rootfs, 
                    "-w", "/root",
                    // /usr/bin/env এর বদলে সরাসরি sh ব্যবহার করা হলো এবং PATH ডাইরেক্ট বসানো হলো
                    "/bin/sh", "-c", "export PATH=/bin:/usr/bin:/sbin:/usr/sbin && export HOME=/root && export TERM=xterm && $cmd"
                )
                
                val pb = ProcessBuilder(commandList)
                
                // টারমাক্স ট্রিক: অ্যান্ড্রয়েডের এনভায়রনমেন্ট রেস্ট্রিকশন মুছে কাস্টম টেম্প পাথ যুক্ত করা
                pb.environment().remove("LD_PRELOAD")
                pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                pb.environment()["TMPDIR"] = tmpDir.absolutePath
                
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

    private fun downloadFile(urlString: String, destPath: String) {
        var url = URL(urlString)
        var conn: HttpURLConnection
        var redirect: Boolean
        
        do {
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                redirect = true
                url = URL(conn.getHeaderField("Location"))
            } else {
                redirect = false
            }
        } while (redirect)

        if (conn.responseCode !in 200..299) {
            throw Exception("HTTP Download Error ${conn.responseCode} for URL: $urlString")
        }

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
