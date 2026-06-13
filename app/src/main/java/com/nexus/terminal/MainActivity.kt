package com.nexus.terminal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
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
                    else -> {
                        if (command.startsWith("apt ") || command.startsWith("linux ")) {
                            tvOutput.append("[ERROR] Ubuntu Base is missing. Run 'setup-linux' first.\n")
                        } else {
                            executeCommand(command)
                        }
                    }
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
                tvOutput.append("\n[WARNING] Storage Permission Required! Type 'permit' and press RUN.\n")
                permissionChecked = false
            } else {
                if (!permissionChecked) {
                    tvOutput.append("\n[SUCCESS] Ready! Type 'setup-linux' to install pure Ubuntu.\n")
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
                
                runOnUiThread { tvOutput.append("[*] Preparing Ubuntu Base...\n") }
                if (!linuxDir.exists()) linuxDir.mkdirs()

                // শুধুমাত্র যদি apt না থাকে তবেই ডাউনলোড করবে
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    val tarFile = File(filesDir, "rootfs.tar.gz")
                    downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                    
                    runOnUiThread { tvOutput.append("[*] Extracting RootFS (This takes a moment)...\n") }
                    val extProcess = Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath))
                    extProcess.waitFor()
                    tarFile.delete()
                }

                if (File(linuxDir, "usr/bin/apt").exists()) {
                    // ফিক্স ১: DNS Resolution Fix
                    val resolvConf = File(linuxDir, "etc/resolv.conf")
                    resolvConf.parentFile.mkdirs()
                    resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    
                    // ফিক্স ২: Android Network Block Fix (APT Sandbox Bypass)
                    val aptConf = File(linuxDir, "etc/apt/apt.conf.d/99nexus")
                    aptConf.parentFile.mkdirs()
                    aptConf.writeText("APT::Sandbox::User \"root\";\n")

                    runOnUiThread { tvOutput.append("[SUCCESS] Termux-like Ubuntu is Ready!\nType: apt update\n") }
                } else {
                    runOnUiThread { tvOutput.append("[ERROR] Critical extraction failed.\n") }
                }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun runLinuxCommand(cmd: String) {
        Thread {
            try {
                val prootBinary = File(filesDir, "proot")
                if (!prootBinary.exists() || prootBinary.length() < 500000) {
                     assets.open("proot").use { input ->
                         FileOutputStream(prootBinary).use { output ->
                             input.copyTo(output)
                         }
                     }
                }
                prootBinary.setExecutable(true, false)

                val rootfs = File(filesDir, "linux").absolutePath
                val tmpDir = File(filesDir, "tmp")
                tmpDir.mkdirs()

                val appDataDir = filesDir.parentFile?.absolutePath ?: "/data/user/0/$packageName"

                val commandList = listOf(
                    prootBinary.absolutePath, "--link2symlink", "-0",
                    "-r", rootfs, 
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/sdcard",
                    "-b", "${filesDir.absolutePath}:${filesDir.absolutePath}", 
                    "-w", "/root",
                    "/bin/sh", "-c", cmd
                )
                
                val pb = ProcessBuilder(commandList)
                val env = pb.environment()
                env.clear()
                env["HOME"] = "/root"
                env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                env["TERM"] = "xterm"
                env["PROOT_TMP_DIR"] = tmpDir.absolutePath
                env["TMPDIR"] = tmpDir.absolutePath
                
                pb.redirectErrorStream(true)
                val p = pb.start()
                
                // ফিক্স ৩: রিয়েল-টাইম টার্মিনাল রিডিং (আর কোনো হ্যাং বা ফ্রিজ হবে না)
                val reader = InputStreamReader(p.inputStream)
                val buffer = CharArray(1024)
                var readCount: Int
                while (reader.read(buffer).also { readCount = it } != -1) {
                    val outputChunk = String(buffer, 0, readCount)
                    runOnUiThread { 
                        tvOutput.append(outputChunk) 
                        // Auto-scroll logic
                        val parent = tvOutput.parent
                        if (parent is ScrollView) {
                            parent.post { parent.fullScroll(android.view.View.FOCUS_DOWN) }
                        }
                    }
                }
                p.waitFor()
                runOnUiThread { tvOutput.append("\n") }
                
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
            if (status in listOf(HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_SEE_OTHER)) {
                redirect = true
                url = URL(conn.getHeaderField("Location"))
            } else { redirect = false }
        } while (redirect)

        conn.inputStream.use { input -> 
            FileOutputStream(destPath).use { output -> 
                val data = ByteArray(4096)
                var count: Int
                while (input.read(data).also { count = it } != -1) { output.write(data, 0, count) }
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
            } catch (e: Exception) { runOnUiThread { tvOutput.append("Error: ${e.message}\n") } }
        }.start()
    }
}
