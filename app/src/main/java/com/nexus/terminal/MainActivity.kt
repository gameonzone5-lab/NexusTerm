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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusTerm::GodModeEngine")

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
                        } else if (File(filesDir, "linux/bin/sh").exists()) {
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
        tvOutput.append("NexusTerm v10.0-GOD-MODE (Pure Static Isolation)\n")
        tvOutput.append("Type 'setup-linux' to deploy Ubuntu safely.\n")
        tvOutput.append("Type 'health-check' to verify execution.\n")
    }

    private fun printSystemInfo() {
        tvOutput.append("=== SYSTEM INFO ===\n")
        tvOutput.append("-> Android: API ${Build.VERSION.SDK_INT}\n")
        tvOutput.append("-> ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        tvOutput.append("===================\n")
    }

    private fun checkSymlinks() {
        runOnUiThread { tvOutput.append("=== CHECKING LINUX CORE FILES ===\n") }
        executeCommand("ls -la ${filesDir.absolutePath}/linux/bin/sh")
        executeCommand("ls -la ${filesDir.absolutePath}/linux/usr/bin/apt")
        runOnUiThread { tvOutput.append("=================================\n") }
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
                val pb = ProcessBuilder(prootBinary.absolutePath, "--link2symlink", "-0", "-r", rootfs, "/bin/sh", "-c", "echo OK")
                pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                pb.redirectErrorStream(true)
                val p2 = pb.start()
                val out2 = BufferedReader(InputStreamReader(p2.inputStream)).readText()
                if (p2.waitFor() == 0 && out2.contains("OK")) {
                    runOnUiThread { tvOutput.append("[PASS] Phase 2: RootFS mounted and /bin/sh is working!\n") }
                } else {
                    runOnUiThread { tvOutput.append("[FAIL] Phase 2: /bin/sh broken.\nOutput: $out2\n") }
                }
            } catch (e: Exception) {}

            try {
                val pb = ProcessBuilder(prootBinary.absolutePath, "--link2symlink", "-0", "-r", rootfs, "/usr/bin/apt", "--version")
                pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                pb.redirectErrorStream(true)
                val p3 = pb.start()
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
            File(filesDir, "rootfs.tar.gz").delete()
            File(filesDir, "tmp").deleteRecursively()
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
                
                val tmpDir = File(filesDir, "tmp")
                if (!tmpDir.exists()) tmpDir.mkdirs()
                
                val tarFile = File(filesDir, "rootfs.tar.gz")
                if (!tarFile.exists() || tarFile.length() < 25_000_000) {
                    tarFile.delete()
                    runOnUiThread { tvOutput.append("[*] Downloading Ubuntu RootFS (~28MB). Please wait...\n") }
                    downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                }

                // THE MASTER FIX: Downloading Official Static BusyBox
                val busybox = File(filesDir, "busybox")
                if (!busybox.exists() || busybox.length() < 500_000) {
                    busybox.delete()
                    runOnUiThread { tvOutput.append("[*] Downloading Static BusyBox Engine...\n") }
                    downloadFile("https://busybox.net/downloads/binaries/1.31.0-defconfig-multiarch-musl/busybox-armv8l", busybox.absolutePath)
                    busybox.setExecutable(true, false)
                }

                val rootFsSizeKB = tarFile.length() / 1024
                runOnUiThread { tvOutput.append("[*] Verification: RootFS=${rootFsSizeKB}KB, Engine=${busybox.length()/1024}KB\n") }
                
                if (rootFsSizeKB < 25000) {
                    runOnUiThread { tvOutput.append("[ERROR] Network Interrupted! File is corrupted. Type 'repair-linux' and try again.\n") }
                    return@thread
                }
                
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[*] Faking root privileges with Pure Static Isolation...\n") }
                    
                    val prootBinary = File(filesDir, "proot")
                    
                    // The Ultimate Command: Static PRoot runs Static BusyBox tar with Link interception
                    val pb = ProcessBuilder(
                        prootBinary.absolutePath, 
                        "--link2symlink", 
                        "-0", 
                        busybox.absolutePath, // <-- Static BusyBox (No dynamic linker crashes)
                        "tar", 
                        "-xzf", tarFile.absolutePath, 
                        "-C", linuxDir.absolutePath
                    )
                    
                    pb.environment()["PROOT_NO_SECCOMP"] = "1"
                    pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    pb.environment()["TMPDIR"] = tmpDir.absolutePath
                    
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
                    
                    if (exitCode == 0 && File(linuxDir, "bin/sh").exists()) {
                        tarFile.delete()
                        File(linuxDir, "etc/resolv.conf").apply {
                            parentFile.mkdirs()
                            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                        }
                        runOnUiThread { tvOutput.append("[SUCCESS] Setup Complete! Type 'health-check'.\n") }
                    } else {
                        runOnUiThread { tvOutput.append("[ERROR] Extraction failed (Exit Code $exitCode).\n") }
                    }
                } else {
                    runOnUiThread { tvOutput.append("[SUCCESS] Environment already set up.\n") }
                }

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
                    "${prootBinary.absolutePath} --link2symlink -0 -r $rootfs " +
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
        var redirectCount = 0
        do {
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            if (status in listOf(HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                redirect = true
                url = URL(conn.getHeaderField("Location"))
                redirectCount++
            } else { 
                redirect = false 
            }
        } while (redirect && redirectCount < 5)

        if (conn.responseCode in 200..299) {
            conn.inputStream.use { input -> FileOutputStream(destPath).use { output -> 
                val data = ByteArray(8192)
                var count: Int
                while (input.read(data).also { count = it } != -1) { output.write(data, 0, count) }
            }}
        } else {
            throw Exception("HTTP Error ${conn.responseCode}: ${conn.responseMessage}")
        }
    }
}
