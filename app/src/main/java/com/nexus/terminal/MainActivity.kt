package com.nexus.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
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
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    
    private var currentDirectory: String = ""
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnRun = findViewById(R.id.btnRun)

        currentDirectory = filesDir.absolutePath

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusTerm::CoreEngine")

        printWelcomeMessage()

        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                when (command) {
                    "setup-linux" -> setupLinuxEnvironment()
                    "health-check" -> runHealthCheck()
                    "repair-linux" -> repairLinux()
                    "system-info" -> printSystemInfo()
                    "clear" -> { tvOutput.text = ""; printWelcomeMessage() }
                    "permit" -> requestStoragePermission()
                    else -> {
                        if (command.startsWith("cd ")) {
                            handleCdCommand(command)
                        } else if (File(filesDir, "linux/usr/bin/apt").exists()) {
                            runLinuxCommand(command)
                        } else {
                            tvOutput.append("[SYSTEM] Environment not ready. Please run 'setup-linux' first.\n")
                        }
                    }
                }
            }
        }
    }

    private fun printWelcomeMessage() {
        tvOutput.append("NexusTerm v2.0-PRO (Production Build)\n")
        tvOutput.append("Type 'system-info' to check compatibility.\n")
        tvOutput.append("Type 'health-check' to verify environment.\n")
        tvOutput.append("Type 'setup-linux' to install Ubuntu Engine.\n")
    }

    // ১. Smart Compatibility Layer (System Info)
    private fun printSystemInfo() {
        tvOutput.append("=== SYSTEM COMPATIBILITY LAYER ===\n")
        tvOutput.append("-> Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        tvOutput.append("-> Architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        val storageCheck = if (Environment.isExternalStorageManager()) "GRANTED" else "DENIED (Type 'permit')"
        tvOutput.append("-> Storage Access: $storageCheck\n")
        val proot = File(filesDir, "proot")
        tvOutput.append("-> Engine (PRoot): ${if (proot.exists()) "INSTALLED (${proot.length()/1024} KB)" else "MISSING"}\n")
        tvOutput.append("==================================\n")
    }

    // ২. Health Check System
    private fun runHealthCheck() {
        thread {
            runOnUiThread { tvOutput.append("=== RUNNING HEALTH CHECK ===\n") }
            
            // RootFS Check
            val linuxDir = File(filesDir, "linux")
            val aptFile = File(linuxDir, "usr/bin/apt")
            if (aptFile.exists() && aptFile.length() > 0) {
                runOnUiThread { tvOutput.append("[PASS] RootFS & APT verified.\n") }
            } else {
                runOnUiThread { tvOutput.append("[FAIL] RootFS corrupted or missing.\n") }
            }

            // Engine Check
            val prootBinary = File(filesDir, "proot")
            if (prootBinary.exists() && prootBinary.canExecute()) {
                runOnUiThread { tvOutput.append("[PASS] PRoot Engine executable.\n") }
            } else {
                runOnUiThread { tvOutput.append("[FAIL] PRoot Engine issue.\n") }
            }

            // DNS & Network Check (ping 8.8.8.8)
            try {
                val p = Runtime.getRuntime().exec("ping -c 1 8.8.8.8")
                if (p.waitFor() == 0) {
                    runOnUiThread { tvOutput.append("[PASS] Network reachability OK.\n") }
                } else {
                    runOnUiThread { tvOutput.append("[FAIL] No Internet Connection.\n") }
                }
            } catch (e: Exception) {
                 runOnUiThread { tvOutput.append("[FAIL] Network ping failed.\n") }
            }
            
            runOnUiThread { tvOutput.append("If any test failed, type 'repair-linux'.\n============================\n") }
        }
    }

    // ৩. Auto Repair
    private fun repairLinux() {
        thread {
            runOnUiThread { tvOutput.append("[*] Initiating Auto-Repair Sequence...\n") }
            try {
                val linuxDir = File(filesDir, "linux")
                if (linuxDir.exists()) {
                    runOnUiThread { tvOutput.append("[*] Purging corrupted RootFS...\n") }
                    linuxDir.deleteRecursively()
                }
                
                val prootBinary = File(filesDir, "proot")
                if (prootBinary.exists()) prootBinary.delete()
                
                runOnUiThread { tvOutput.append("[SUCCESS] Environment cleaned. Now running 'setup-linux'...\n") }
                setupLinuxEnvironment()
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Repair failed: ${e.message}\n") }
            }
        }
    }

    // ৪. One-Command Setup
    private fun setupLinuxEnvironment() {
        thread {
            wakeLock?.acquire(20 * 60 * 1000L)
            try {
                val linuxDir = File(filesDir, "linux")
                if (!linuxDir.exists()) linuxDir.mkdirs()
                
                val tarFile = File(filesDir, "rootfs.tar.gz")
                if (!tarFile.exists() || tarFile.length() < 1000000) {
                    runOnUiThread { tvOutput.append("[*] Downloading Ubuntu RootFS...\n") }
                    downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                }
                
                val prootBinary = File(filesDir, "proot")
                if (!prootBinary.exists()) {
                    runOnUiThread { tvOutput.append("[*] Installing PRoot Engine...\n") }
                    assets.open("proot").use { input ->
                        FileOutputStream(prootBinary).use { output -> input.copyTo(output) }
                    }
                    prootBinary.setExecutable(true, false)
                }
                
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[*] Extracting System Architecture...\n") }
                    val p = Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath))
                    p.waitFor()
                    tarFile.delete()
                }

                // Configuration
                File(linuxDir, "etc/resolv.conf").apply {
                    parentFile.mkdirs()
                    writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                }
                File(linuxDir, "etc/apt/apt.conf.d/99nexus").apply {
                    parentFile.mkdirs()
                    writeText("APT::Sandbox::User \"root\";\n")
                }

                runOnUiThread { tvOutput.append("[SUCCESS] Setup Complete! Type 'health-check' to verify.\n") }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    // ৫. Better Launcher & Exit Code Debugging
    private fun runLinuxCommand(cmd: String) {
        thread {
            wakeLock?.acquire(15 * 60 * 1000L)
            try {
                val prootBinary = File(filesDir, "proot")
                val rootfs = File(filesDir, "linux").absolutePath
                val tmpDir = File(filesDir, "tmp").apply { mkdirs() }

                val runScript = File(filesDir, "run_cmd.sh")
                runScript.writeText("#!/system/bin/sh\n" +
                    "export PROOT_NO_SECCOMP=1\n" +
                    "export PROOT_TMP_DIR=${tmpDir.absolutePath}\n" +
                    "export TMPDIR=${tmpDir.absolutePath}\n" +
                    "unset LD_PRELOAD\n" +
                    "${prootBinary.absolutePath} -0 -r $rootfs " +
                    "-b /dev -b /proc -b /sys -b /sdcard " +
                    "-b ${filesDir.absolutePath}:${filesDir.absolutePath} " +
                    "-w /root " +
                    "/usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm " +
                    "/bin/sh -c \"$cmd\"\n"
                )
                runScript.setExecutable(true)

                val pb = ProcessBuilder(runScript.absolutePath)
                pb.redirectErrorStream(true)
                val p = pb.start()
                
                val reader = InputStreamReader(p.inputStream)
                val buffer = CharArray(1024)
                var readCount: Int
                while (reader.read(buffer).also { readCount = it } != -1) {
                    val outputChunk = String(buffer, 0, readCount)
                    runOnUiThread { 
                        tvOutput.append(outputChunk) 
                        val parent = tvOutput.parent
                        if (parent is ScrollView) parent.post { parent.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }
                
                val exitCode = p.waitFor()
                runScript.delete() 
                
                runOnUiThread { 
                    if (exitCode != 0) {
                        // Detailed Exit Code Debugging
                        tvOutput.append("\n[SYSTEM ALERT] Ubuntu execution failed (Code $exitCode)\n")
                        tvOutput.append("Possible Reasons:\n")
                        tvOutput.append("- Architecture mismatch (ARM64 binary on non-ARM device)\n")
                        tvOutput.append("- Corrupted RootFS extraction\n")
                        tvOutput.append("- Missing symbolic links in native storage\n")
                        tvOutput.append("\nSuggested Action: Type 'health-check' or 'repair-linux'\n")
                    } else {
                        tvOutput.append("\n") 
                    }
                }
            } catch (e: Exception) { 
                runOnUiThread { tvOutput.append("Execution Error: ${e.message}\n") } 
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
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

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") })
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun downloadFile(urlString: String, destPath: String) {
        var url = URL(urlString)
        var conn: HttpURLConnection
        var redirect: Boolean
        do {
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = false
            if (conn.responseCode in listOf(301, 302, 303)) {
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
}
