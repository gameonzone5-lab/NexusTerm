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

                // আসল চেক: উবুন্টুর apt ফাইলটি ফিজিক্যালি আছে কি না
                val isLinuxReady = File(filesDir, "linux/usr/bin/apt").exists()

                when {
                    command == "permit" -> requestStoragePermission()
                    command == "clear" -> { tvOutput.text = ""; checkPermission() }
                    command.startsWith("cd ") -> handleCdCommand(command)
                    command == "setup-linux" -> setupLinuxEnvironment()
                    // যদি লিনাক্স না থাকে আর ইউজার apt রান করতে চায়, তাহলে আটকে দেওয়া হবে
                    (command.startsWith("apt ") || command.startsWith("linux ")) && !isLinuxReady -> {
                        tvOutput.append("[ERROR] Ubuntu Base is missing or corrupted!\nPlease type 'setup-linux' to install it first.\n")
                    }
                    // লিনাক্স রেডি থাকলে PRoot এর মাধ্যমে রান হবে
                    isLinuxReady -> runLinuxCommand(command)
                    // অন্য সব সাধারণ কমান্ড অ্যান্ড্রয়েডে রান হবে
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
                tvOutput.append("\n[WARNING] Full Storage Permission Required!\nType 'permit' and press RUN to allow.\n")
                permissionChecked = false
            } else {
                if (!permissionChecked) {
                    tvOutput.append("\n[SUCCESS] Environment Ready! Type 'setup-linux' to begin.\n")
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
                
                // আগের কোনো নষ্ট ফাইল থাকলে মুছে ফেলবে
                if (linuxDir.exists() && !File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[*] Cleaning corrupted files...\n") }
                    linuxDir.deleteRecursively()
                }

                if (File(linuxDir, "usr/bin/apt").exists()) {
                    runOnUiThread { tvOutput.append("[SUCCESS] Ubuntu is already perfectly installed!\nType: apt update\n") }
                    return@Thread
                }

                if (!linuxDir.exists()) linuxDir.mkdirs()

                runOnUiThread { tvOutput.append("[*] Downloading Official Ubuntu Base (~25MB). Please wait...\n") }
                val tarFile = File(filesDir, "rootfs.tar.gz")
                downloadFile("https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", tarFile.absolutePath)
                
                runOnUiThread { tvOutput.append("[*] Extracting RootFS (Ignoring Android symlink errors)...\n") }
                val extProcess = Runtime.getRuntime().exec(arrayOf("tar", "-xf", tarFile.absolutePath, "-C", linuxDir.absolutePath))
                extProcess.waitFor()
                
                // এক্সট্র্যাকশন সাকসেসফুল কি না তার আসল চেক
                if (File(linuxDir, "usr/bin/apt").exists()) {
                    tarFile.delete()
                    val resolvConf = File(linuxDir, "etc/resolv.conf")
                    resolvConf.parentFile.mkdirs()
                    resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    
                    runOnUiThread { tvOutput.append("[SUCCESS] Pure Termux-like Ubuntu is Ready!\nType: apt update\n") }
                } else {
                    runOnUiThread { tvOutput.append("[ERROR] Critical extraction failed. Try again.\n") }
                }
            } catch (e: Exception) {
                runOnUiThread { tvOutput.append("[ERROR] Setup failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun runLinuxCommand(cmd: String) {
        Thread {
            try {
                // UserLAnd Architecture: Native Library Loader
                val nativeDir = applicationInfo.nativeLibraryDir
                var prootBinary = File(nativeDir, "libproot.so")
                
                // Fallback: যদি প্লে-স্টোর পলিসি লাইব্রেরি প্যাক করতে ফেইল করে, তাহলে লোকালি PRoot নামিয়ে নেবে
                if (!prootBinary.exists()) {
                    prootBinary = File(filesDir, "proot")
                    if (!prootBinary.exists() || prootBinary.length() < 500000) {
                        downloadFile("https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static", prootBinary.absolutePath)
                    }
                    prootBinary.setExecutable(true, false)
                }

                val rootfs = File(filesDir, "linux").absolutePath
                val tmpDir = File(filesDir, "tmp")
                if (!tmpDir.exists()) tmpDir.mkdirs()

                val commandList = listOf(
                    prootBinary.absolutePath, "-0", "--link2symlink",
                    "-b", "/sdcard:/sdcard", 
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-r", rootfs, 
                    "-w", "/root",
                    "/bin/sh", "-c", "export PATH=/bin:/usr/bin:/sbin:/usr/sbin && export HOME=/root && export TERM=xterm && export DEBIAN_FRONTEND=noninteractive && export TMPDIR=/tmp && $cmd"
                )
                
                val pb = ProcessBuilder(commandList)
                pb.environment().remove("LD_PRELOAD")
                pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                
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
            } else {
                redirect = false
            }
        } while (redirect)

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
