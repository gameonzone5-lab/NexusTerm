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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusTerm::BusyBoxEngine")

        printWelcomeMessage()

        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                when (command) {
                    "setup-linux" -> setupLinuxEnvironment()
                    "health-check" -> runHealthCheck()
                    "check-symlinks" -> checkSymlinks()
                    "repair-linux" -> repairLinux()
                    "system-info" -> printSystemInfo()
                    "clear" -> { tvOutput.text = ""; printWelcomeMessage() }
                    "permit" -> requestStoragePermission()
                    else -> {
                        if (command.startsWith("cd ")) {
                            handleCdCommand(command)
                        } else if (File(filesDir, "linux").exists()) {
                            runLinuxCommand(command)
                        } else {
                            tvOutput.append("[SYSTEM] RootFS missing. Run 'setup-linux' first.\n")
                        }
                    }
                }
            }
        }
    }

    private fun printWelcomeMessage() {
        tvOutput.append("NexusTerm v6.1-BUSYBOX (Symlink Protected)\n")
        tvOutput.append("Type 'setup-linux' to extract Ubuntu safely.\n")
        tvOutput.append("Type 'check-symlinks' to verify Android tar didn't break files.\n")
        tvOutput.append("Type 'health-check' to verify RootFS execution.\n")
    }

    private fun printSystemInfo() {
        tvOutput.append("=== SYSTEM INFO ===\n")
        tvOutput.append("-> Android: API ${Build.VERSION.SDK_INT}\n")
        tvOutput.append("-> ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        tvOutput.append("===================\n")
    }

    private fun checkSymlinks() {
        runOnUiThread { tvOutput.append("=== CHECKING LINUX SYMLINKS ===\n") }
        executeCommand("ls -la ${filesDir.absolutePath}/linux/bin/sh")
        executeCommand("ls -la ${filesDir.absolutePath}/linux/usr/bin/apt")
        runOnUiThread { tvOutput.append("===============================\n") }
    }

    private fun extractStaticEngine() {
        val prootBinary = File(filesDir, "proot")
        if (!prootBinary.exists() || prootBinary.length() < 500000) {
            runOnUiThread { tvOutput.append("[*] Extracting Static PRoot Engine...\n") }
            try {
                assets.open("proot").use { input ->
                    FileOutputStream(prootBinary).use { output -> input.copyTo(output) }
                }
                prootBinary.setExecutable(true, false)
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Engine extraction failed: ${e.message}\n") }
            }
        }
    }

    private fun runHealthCheck() {
        thread {
            runOnUiThread { tvOutput.append("=== RUNNING DEEP HEALTH CHECK ===\n") }
            
            val prootBinary = File(filesDir, "proot")
            val rootfs = File(filesDir, "linux").absolutePath
            val tmpDir = File(filesDir, "tmp").apply { mkdirs() }

            if (!prootBinary.exists()) {
                runOnUiThread { tvOutput.append("[FAIL] PRoot missing.\n") }
                return@thread
            }

            try {
                val p1 = ProcessBuilder(prootBinary.absolutePath, "--version").redirectErrorStream(true).start()
                if (p1.waitFor() == 0) runOnUiThread { tvOutput.append("[PASS] Phase 1: PRoot executes natively.\n") }
                else runOnUiThread { tvOutput.append("[FAIL] Phase 1: PRoot execution failed.\n") }
            } catch (e: Exception) {}

            try {
                val p2 = ProcessBuilder(prootBinary.absolutePath, "-0", "-r", rootfs, "/bin/sh", "-c", "echo OK")
                    .redirectErrorStream(true).start()
                val out2 = BufferedReader(InputStreamReader(p2.inputStream)).readText()
                if (p2.waitFor() == 0 && out2.contains("OK")) {
                    runOnUiThread { tvOutput.append("[PASS] Phase 2: RootFS mounted and /bin/sh is working!\n") }
                } else {
                    runOnUiThread { tvOutput.append("[FAIL] Phase 2: /bin/sh broken.\nOutput: $out2\n") }
                }
            } catch (e: Exception) {}

            try {
                val p3 = ProcessBuilder(prootBinary.absolutePath, "-0", "-r", rootfs, "/usr/bin/apt", "--version")
                    .redirectErrorStream(true).start()
                val out3 = BufferedReader(InputStreamReader(p3.inputStream)).readText()
                if (p3.waitFor() == 0 && out3.contains("apt")) {
                    runOnUiThread { tvOutput.append("[PASS] Phase 3: APT Package Manager responds!\n") }
                } else {
                    runOnUiThread { tvOutput.append("[FAIL] Phase 3: APT broken.\nOutput: $out3\n") }
                }
            } catch (e: Exception) {}

            runOnUiThread { tvOutput.append("=================================\n") }
        }
    }

    private fun repairLinux() {
        thread {
            runOnUiThread { tvOutput.append("[*] Purging corrupted RootFS...\n") }
            File(filesDir, "linux").deleteRecursively()
            File(filesDir, "busybox").delete()
            runOnUiThread { tvOutput.append("[SUCCESS] Environment cleaned. Run 'setup-linux'.\n") }
        }
    }

    private fun setupLinuxEnvironment() {
        thread {
            wakeLock?.acquire(20 * 60 * 1000L)
            try {
                extractStaticEngine()
                
                val linuxDir = File(filesDir, "linux")
                if (!linuxDir.exists()) linuxDir.mkdirs()
                
                val tarFile = File(filesDir, "rootfs.tar.gz")
                if (!tarFile.exists() || tarFile.length() < 1000000) {
                    runOnUiThread { tvOutput.append("[*] Downloading Ubuntu RootFS...\n") }
                    downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                }

                val busybox = File(filesDir, "busybox")
                if (!busybox.exists()) {
                    runOnUiThread { tvOutput.append("[*] Downloading Linux BusyBox for safe extraction...\n") }
                    downloadFile("https://busybox.net/downloads/binaries/1.35.0-aarch64-linux-musl/busybox", busybox.absolutePath)
                    busybox.setExecutable(true, false)
                }
                
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[*] Extracting RootFS using BusyBox (Protecting Symlinks)...\n") }
                    val pb = ProcessBuilder(busybox.absolutePath, "tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath)
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                    p.waitFor()
                    if (out.isNotEmpty()) runOnUiThread { tvOutput.append("[BusyBox Log] $out\n") }
                    tarFile.delete()
                }

                File(linuxDir, "etc/resolv.conf").apply {
                    parentFile.mkdirs()
                    writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                }

                runOnUiThread { tvOutput.append("[SUCCESS] Setup Complete! Type 'check-symlinks' then 'health-check'.\n") }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

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
                    if (exitCode != 0) tvOutput.append("\n[SYSTEM ALERT] Command failed (Exit Code $exitCode).\n") 
                    else tvOutput.append("\n") 
                }
            } catch (e: Exception) { 
                runOnUiThread { tvOutput.append("Execution Error: ${e.message}\n") } 
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    // এই সেই ফাংশনটি যা আমি আগের বার দিতে ভুলে গিয়েছিলাম!
    private fun executeCommand(command: String) {
        thread {
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
        }
    }

    private fun handleCdCommand(command: String) {
        val newDir = command.removePrefix("cd").trim()
        if (newDir.isEmpty() || newDir == "~") {
            currentDirectory = filesDir.absolutePath
            return
        }
        var targetPath = newDir
        if (newDir == "/sdcard" || newDir.startsWith("/sdcard/")) targetPath = newDir.replace("/sdcard", Environment.getExternalStorageDirectory().absolutePath)
        val targetFile = if (targetPath.startsWith("/")) File(targetPath) else File(currentDirectory, targetPath)
        if (targetFile.exists() && targetFile.isDirectory) currentDirectory = targetFile.canonicalPath
        else tvOutput.append("cd: $newDir: Directory not found\n")
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") }) } 
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
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

        conn.inputStream.use { input -> FileOutputStream(destPath).use { output -> 
            val data = ByteArray(4096)
            var count: Int
            while (input.read(data).also { count = it } != -1) { output.write(data, 0, count) }
        }}
    }
}
