package com.nexus.terminal

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    
    private var currentDirectory: String = ""
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        currentDirectory = filesDir.absolutePath
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusTerm::Phoenix")

        // === RESTORING THE BEAUTIFUL HACKER UI ===
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C0C0C")) // Deep Hacker Black
        }

        tvOutput = TextView(this).apply {
            setTextColor(Color.parseColor("#00FF00")) // Matrix Green
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(tvOutput)
        }

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(8, 8, 8, 8)
        }

        etInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Enter command..."
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.TRANSPARENT)
        }

        btnRun = Button(this).apply {
            text = "RUN"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }

        inputLayout.addView(etInput)
        inputLayout.addView(btnRun)
        
        mainLayout.addView(scrollView)
        mainLayout.addView(inputLayout)
        
        setContentView(mainLayout)

        tvOutput.append("NexusTerm v12-PHOENIX (System Restored)\n")
        tvOutput.append("UI Restored. Auto-Engine Activated.\n")
        tvOutput.append("Type 'setup-linux' to begin full automated deployment.\n")

        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                when (command) {
                    "setup-linux" -> startAutomatedSetup()
                    "health-check" -> runHealthCheck()
                    "clear" -> tvOutput.text = "NexusTerm Ready.\n"
                    else -> {
                        if (File(filesDir, "linux/bin/sh").exists()) {
                            runLinuxCommand(command)
                        } else {
                            tvOutput.append("[ERROR] Ubuntu not installed. Run 'setup-linux'.\n")
                        }
                    }
                }
            }
        }
    }

    private fun startAutomatedSetup() {
        thread {
            wakeLock?.acquire(30 * 60 * 1000L) // 30 mins wakelock
            try {
                val prootApk = File(filesDir, "userland.apk")
                val ubuntuTar = File(filesDir, "ubuntu.tar.gz")
                val busyboxBin = File(filesDir, "busybox")
                val linuxDir = File(filesDir, "linux")

                // Step 1: Download UserLAnd APK for pure dynamic PRoot
                if (!File(filesDir, "proot").exists()) {
                    updateUI("[*] Downloading PRoot Core Engine...")
                    downloadFile("https://github.com/CypherpunkArmory/UserLAnd/releases/download/v2.8.3/app-release.apk", prootApk)
                    updateUI("[*] Extracting Linker bypass libraries...")
                    extractEngineFromApk(prootApk)
                }

                // Step 2: Download Busybox for safe tar extraction
                if (!busyboxBin.exists() || busyboxBin.length() < 500000) {
                    updateUI("[*] Downloading Safe Extractor (Busybox)...")
                    downloadFile("https://busybox.net/downloads/binaries/1.31.0-defconfig-multiarch-musl/busybox-armv8l", busyboxBin)
                    busyboxBin.setExecutable(true, false)
                }

                // Step 3: Download Ubuntu
                if (!ubuntuTar.exists() || ubuntuTar.length() < 25000000) {
                    updateUI("[*] Downloading Ubuntu 22.04 RootFS (~28MB)...")
                    downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", ubuntuTar)
                }

                // Step 4: PRoot Wrapped Extraction (Bypasses Hardlink block)
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    if (!linuxDir.exists()) linuxDir.mkdirs()
                    File(filesDir, "tmp").mkdirs()

                    updateUI("[*] Injecting Ubuntu via PRoot Magic (Fixing Hardlinks)...")
                    
                    val pb = ProcessBuilder(
                        File(filesDir, "proot").absolutePath,
                        "--link2symlink", "-0",
                        busyboxBin.absolutePath, "tar", "-xzpf", ubuntuTar.absolutePath, "-C", linuxDir.absolutePath
                    )
                    pb.environment()["PROOT_LOADER"] = File(filesDir, "proot-loader.so").absolutePath
                    pb.environment()["PROOT_LOADER_64"] = File(filesDir, "proot-loader64.so").absolutePath
                    pb.environment()["PROOT_NO_SECCOMP"] = "1"
                    pb.environment()["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
                    
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    
                    val reader = BufferedReader(InputStreamReader(p.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // Suppress verbose tar output, only show errors
                        if (line!!.contains("error", true) || line!!.contains("denied", true)) {
                            updateUI("[Log] $line")
                        }
                    }
                    val exitCode = p.waitFor()

                    if (exitCode == 0 && File(linuxDir, "bin/sh").exists()) {
                        File(linuxDir, "etc/resolv.conf").apply {
                            parentFile.mkdirs()
                            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                        }
                        updateUI("[SUCCESS] Ubuntu Arm64 is successfully installed! Type 'health-check'.")
                        ubuntuTar.delete()
                        prootApk.delete()
                    } else {
                        updateUI("[ERROR] Extraction failed. Exit Code: $exitCode")
                    }
                } else {
                    updateUI("[SUCCESS] Ubuntu is already installed.")
                }

            } catch (e: Exception) {
                updateUI("[ERROR] Setup crashed: ${e.message}")
            } finally {
                wakeLock?.release()
            }
        }
    }

    private fun runHealthCheck() {
        thread {
            updateUI("=== RUNNING SYSTEM CHECK ===")
            val cmd = "echo '[PASS] Shell Active!' && apt --version || echo '[FAIL] APT broken'"
            runLinuxCommand(cmd)
        }
    }

    private fun runLinuxCommand(cmd: String) {
        thread {
            try {
                val linuxDir = File(filesDir, "linux")
                val runScript = File(filesDir, "cmd.sh")
                runScript.writeText("#!/system/bin/sh\n" +
                    "export PROOT_LOADER=${File(filesDir, "proot-loader.so").absolutePath}\n" +
                    "export PROOT_LOADER_64=${File(filesDir, "proot-loader64.so").absolutePath}\n" +
                    "export PROOT_NO_SECCOMP=1\n" +
                    "export PROOT_TMP_DIR=${File(filesDir, "tmp").absolutePath}\n" +
                    "export TMPDIR=${File(filesDir, "tmp").absolutePath}\n" +
                    "unset LD_PRELOAD\n" +
                    "${File(filesDir, "proot").absolutePath} --link2symlink -0 -r ${linuxDir.absolutePath} " +
                    "-b /dev -b /proc -b /sys -b /sdcard " +
                    "-w /root " +
                    "/usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm " +
                    "/bin/sh -c \"$cmd\"\n"
                )
                runScript.setExecutable(true)

                val pb = ProcessBuilder(runScript.absolutePath)
                pb.redirectErrorStream(true)
                val p = pb.start()
                
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    updateUI(line!!)
                }
                p.waitFor()
                runScript.delete()
            } catch (e: Exception) {
                updateUI("Execution Error: ${e.message}")
            }
        }
    }

    private fun extractEngineFromApk(apkFile: File) {
        ZipFile(apkFile).use { zip ->
            val extractMap = mapOf(
                "lib/arm64-v8a/libuserland-exec.so" to "proot",
                "lib/arm64-v8a/libuserland-exec-loader.so" to "proot-loader.so"
            )
            for ((zipPath, destName) in extractMap) {
                val entry = zip.getEntry(zipPath) ?: continue
                zip.getInputStream(entry).use { input ->
                    val dest = File(filesDir, destName)
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                    dest.setExecutable(true, false)
                }
            }
            // Userland uses the same loader for 32 and 64
            File(filesDir, "proot-loader.so").copyTo(File(filesDir, "proot-loader64.so"), overwrite = true)
            File(filesDir, "proot-loader64.so").setExecutable(true, false)
        }
    }

    private fun downloadFile(urlString: String, destPath: File) {
        var url = URL(urlString)
        var conn: HttpURLConnection
        var redirect: Boolean
        var redirectCount = 0
        do {
            conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                redirect = true
                url = URL(conn.getHeaderField("Location"))
                redirectCount++
            } else { redirect = false }
        } while (redirect && redirectCount < 5)

        if (conn.responseCode in 200..299) {
            conn.inputStream.use { input -> FileOutputStream(destPath).use { output -> input.copyTo(output) } }
        } else {
            throw Exception("HTTP Error ${conn.responseCode}")
        }
    }

    private fun updateUI(text: String) {
        runOnUiThread { 
            tvOutput.append("$text\n") 
            val parent = tvOutput.parent as? ScrollView
            parent?.post { parent.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}
