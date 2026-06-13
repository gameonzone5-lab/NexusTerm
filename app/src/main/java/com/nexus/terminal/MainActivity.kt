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

        // চ্যাটজিপিটি নির্দেশিত ৩ নম্বর ধাপ: অ্যাপের শুরুতেই নেটিভ ডিরেক্টরি লাইভ লগ করা
        logNativeLibraryDirectory()
        
        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                val isLinuxReady = File(filesDir, "linux/usr/bin/apt").exists()

                when {
                    command == "setup-linux" -> setupLinuxEnvironment()
                    command == "permit" -> requestStoragePermission()
                    command == "clear" -> { tvOutput.text = ""; logNativeLibraryDirectory(); checkPermission() }
                    command.startsWith("cd ") -> handleCdCommand(command)
                    isLinuxReady -> runLinuxCommand(command)
                    else -> executeCommand(command)
                }
            }
        }
    }

    private fun logNativeLibraryDirectory() {
        tvOutput.append("=== SYSTEM NATIVE LOG ===\n")
        tvOutput.append("NativeDir: ${applicationInfo.nativeLibraryDir}\n")
        val nativeDirFile = File(applicationInfo.nativeLibraryDir)
        val files = nativeDirFile.listFiles()
        if (files.isNullOrEmpty()) {
            tvOutput.append("[CRITICAL] nativeLibraryDir is COMPLETELY EMPTY!\n")
        } else {
            files.forEach { tvOutput.append(" -> Found: ${it.name} (${it.length()} bytes)\n") }
        }
        tvOutput.append("=========================\n")
    }

    override fun onResume() {
        super.onResume()
        if (!permissionChecked) checkPermission()
    }

    private fun checkPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                tvOutput.append("\n[WARNING] Storage Permission Required! Type 'permit' and RUN.\n")
                permissionChecked = false
            } else {
                if (!permissionChecked) {
                    tvOutput.append("\n[READY] Type 'setup-linux' if not installed.\n")
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
                if (File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu is installed!\n") }
                    return@Thread
                }
                runOnUiThread { tvOutput.append("[*] Downloading Ubuntu Base...\n") }
                if (!linuxDir.exists()) linuxDir.mkdirs()

                val tarFile = File(filesDir, "rootfs.tar.gz")
                downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                
                runOnUiThread { tvOutput.append("[*] Extracting RootFS...\n") }
                val extProcess = Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath))
                extProcess.waitFor()
                
                if (File(linuxDir, "usr/bin/apt").exists()) {
                    tarFile.delete()
                    val resolvConf = File(linuxDir, "etc/resolv.conf")
                    resolvConf.parentFile.mkdirs()
                    resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu Ready! Run your command.\n") }
                } else {
                    runOnUiThread { tvOutput.append("[ERROR] Extraction failed.\n") }
                }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] ${e.message}\n") }
            }
        }.start()
    }

    private fun runLinuxCommand(cmd: String) {
        Thread {
            try {
                val nativeLibraryDir = applicationInfo.nativeLibraryDir
                val prootBinary = File(nativeLibraryDir, "libproot.so")
                val loader64 = File(nativeLibraryDir, "libproot-loader64.so")
                val loader32 = File(nativeLibraryDir, "libproot-loader.so")
                
                if (!prootBinary.exists()) {
                    runOnUiThread { tvOutput.append("[ERROR] libproot.so missing from system native dir!\n") }
                    return@Thread
                }

                val rootfs = File(filesDir, "linux")
                val tmpDir = File(filesDir, "tmp")
                tmpDir.mkdirs()
                val realTmpPath = tmpDir.canonicalPath 
                val appDataDir = filesDir.parentFile?.absolutePath ?: "/data/user/0/$packageName"

                val commandList = listOf(
                    prootBinary.absolutePath, "-0", "--link2symlink",
                    "-b", "/sdcard:/sdcard", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", "$appDataDir:$appDataDir", 
                    "-r", rootfs.absolutePath, "-w", "/root",
                    "/bin/sh", "-c", "export PATH=/bin:/usr/bin:/sbin:/usr/sbin && export HOME=/root && export TERM=xterm && export DEBIAN_FRONTEND=noninteractive && export TMPDIR=$realTmpPath && $cmd"
                )
                
                val pb = ProcessBuilder(commandList)
                pb.environment().remove("LD_PRELOAD")
                pb.environment()["PROOT_TMP_DIR"] = realTmpPath
                
                if (loader64.exists()) pb.environment()["PROOT_LOADER_64"] = loader64.absolutePath
                if (loader32.exists()) pb.environment()["PROOT_LOADER"] = loader32.absolutePath
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                
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
            } catch (e: Exception) { runOnUiThread { tvOutput.append("Error: ${it.message}\n") } }
        }.start()
    }
}
