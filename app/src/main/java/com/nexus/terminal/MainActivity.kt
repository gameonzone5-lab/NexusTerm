package com.nexus.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
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

class MainActivity : ComponentActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    
    private var currentDirectory: String = ""
    private var permissionChecked: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnRun = findViewById(R.id.btnRun)

        currentDirectory = filesDir.absolutePath

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusTerm::GodModeExecution")

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
                            tvOutput.append("[ERROR] System unarmed. Run 'setup-linux' first.\n")
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

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun checkPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                tvOutput.append("\n[WARNING] Storage Permission Required! Type 'permit' and press RUN.\n")
                permissionChecked = false
            } else {
                if (!permissionChecked) {
                    tvOutput.append("\n[SUCCESS] Matrix Ready! Type 'setup-linux' to deploy Ubuntu.\n")
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
            wakeLock?.acquire(20 * 60 * 1000L)
            try {
                val linuxDir = File(filesDir, "linux")
                
                runOnUiThread { tvOutput.append("[*] Bypassing Android Restrictions. Initiating God-Mode Extraction...\n") }
                
                if (!File(linuxDir, "usr/bin/apt").exists()) {
                    linuxDir.deleteRecursively()
                    linuxDir.mkdirs()
                    
                    val tarFile = File(filesDir, "rootfs.tar.gz")
                    if (!tarFile.exists()) {
                        downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                    }
                    
                    runOnUiThread { tvOutput.append("[*] Forcing Native Extraction (Screen-off Safe). Please wait...\n") }
                    
                    val extractScript = File(filesDir, "extract.sh")
                    extractScript.writeText("#!/system/bin/sh\ntar -xf ${tarFile.absolutePath} -C ${linuxDir.absolutePath} > /dev/null 2>&1\nexit 0\n")
                    extractScript.setExecutable(true)
                    
                    val p = Runtime.getRuntime().exec(arrayOf(extractScript.absolutePath))
                    p.waitFor()
                    tarFile.delete()
                    extractScript.delete()
                }

                if (File(linuxDir, "usr/bin/apt").exists()) {
                    val resolvConf = File(linuxDir, "etc/resolv.conf")
                    resolvConf.parentFile.mkdirs()
                    resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    
                    val aptConf = File(linuxDir, "etc/apt/apt.conf.d/99nexus")
                    aptConf.parentFile.mkdirs()
                    aptConf.writeText("APT::Sandbox::User \"root\";\n")

                    runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu Engine Armed and Synced! Type: apt update\n") }
                } else {
                    runOnUiThread { tvOutput.append("[ERROR] System Deployment Failed.\n") }
                }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }.start()
    }

    private fun runLinuxCommand(cmd: String) {
        Thread {
            wakeLock?.acquire(15 * 60 * 1000L)
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

                // THE GOD-MODE SCRIPT: No --link2symlink, No Java Environment interference
                val runScript = File(filesDir, "run_cmd.sh")
                runScript.writeText("#!/system/bin/sh\n" +
                    "export PROOT_NO_SECCOMP=1\n" +
                    "export PROOT_NO_SYSVIPC=1\n" +
                    "export PROOT_TMP_DIR=${tmpDir.absolutePath}\n" +
                    "export TMPDIR=${tmpDir.absolutePath}\n" +
                    "unset LD_PRELOAD\n" +
                    "${prootBinary.absolutePath} -0 -r $rootfs " +
                    "-b /dev -b /proc -b /sys -b /sdcard " +
                    "-b ${filesDir.absolutePath}:${filesDir.absolutePath} " +
                    "-w /root " +
                    "/usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm LANG=C.UTF-8 " +
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
                        if (parent is ScrollView) {
                            parent.post { parent.fullScroll(android.view.View.FOCUS_DOWN) }
                        }
                    }
                }
                
                val exitCode = p.waitFor()
                runScript.delete() 
                
                runOnUiThread { 
                    if (exitCode != 0) tvOutput.append("[Process exited with code $exitCode]\n") 
                    else tvOutput.append("\n") 
                }
                
            } catch (e: Exception) { 
                runOnUiThread { tvOutput.append("Execution Error: ${e.message}\n") } 
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
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
