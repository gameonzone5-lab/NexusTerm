package com.nexus.terminal

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.ScrollView
// ফিক্স: AppCompatActivity এর বদলে ComponentActivity ব্যবহার করা হচ্ছে
import androidx.activity.ComponentActivity
import java.io.*
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val TAG = "NexusTermMain"
    private lateinit var statusView: TextView
    private val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(32, 32, 32, 32)
        }
        val scrollView = ScrollView(this).apply {
            addView(statusView)
        }
        setContentView(scrollView)

        updateStatus("NexusTerm v11.1 (Crash Fixed) চালু হচ্ছে...")

        thread {
            try {
                val engineManager = NativeEngineManager(this@MainActivity)
                val downloader = RootFSDownloader(this@MainActivity)

                updateStatus("\n[1/4] PRoot ইঞ্জিন ডিপ্লয় করা হচ্ছে...")
                engineManager.installEngine()

                val rootfsFile = File(filesDir, "ubuntu-rootfs.tar.gz")
                if (!rootfsFile.exists() || rootfsFile.length() < 25_000_000) {
                    updateStatus("\n[2/4] Ubuntu RootFS ডাউনলোড হচ্ছে (অপেক্ষা করুন)...")
                    if (!downloader.downloadRootFS(ROOTFS_URL, rootfsFile)) {
                        throw Exception("RootFS ডাউনলোড ব্যর্থ হয়েছে!")
                    }
                } else {
                    updateStatus("\n[2/4] RootFS আগে থেকেই স্টোরেজে রেডি আছে।")
                }

                val rootfsDir = File(filesDir, "ubuntu")
                if (!rootfsDir.exists() || rootfsDir.listFiles()?.isEmpty() == true) {
                    updateStatus("\n[3/4] PRoot-এর মাধ্যমে RootFS এক্সট্র্যাক্ট করা হচ্ছে (Symlink Magic)...")
                    extractRootFSViaPRoot(engineManager, rootfsFile, rootfsDir)
                } else {
                    updateStatus("\n[3/4] RootFS আগে থেকেই এক্সট্র্যাক্ট করা আছে।")
                }

                updateStatus("\n[4/4] Ubuntu Shell-এ কানেক্ট করা হচ্ছে...")
                launchUbuntuShell(engineManager, rootfsDir)

            } catch (e: Exception) {
                Log.e(TAG, "Fatal Error: ${e.message}")
                updateStatus("\n[ERROR] ${e.message}\nদয়া করে অ্যাপ ক্লিয়ার ডেটা (Clear Data) করে আবার ট্রাই করুন।")
            }
        }
    }

    private fun extractRootFSViaPRoot(engine: NativeEngineManager, archive: File, target: File) {
        if (!target.exists()) target.mkdirs()
        val cmd = "${engine.prootBinary.absolutePath} -0 -r ${target.absolutePath} --link2symlink /system/bin/sh -c 'tar -xzf ${archive.absolutePath} -C ${target.absolutePath}'"
        executeProcess(engine.getExecutionEnv(), cmd, "Extraction")
    }

    private fun launchUbuntuShell(engine: NativeEngineManager, rootfsDir: File) {
        val cmd = "${engine.prootBinary.absolutePath} -0 -r ${rootfsDir.absolutePath} /bin/sh -c 'echo \"\n[SUCCESS] UBUNTU ARM64 IS RUNNING NATIVELY!\" && cat /etc/os-release'"
        executeProcess(engine.getExecutionEnv(), cmd, "Shell")
    }

    private fun executeProcess(env: Map<String, String>, command: String, label: String) {
        Log.d(TAG, "Executing $label: $command")
        val pb = ProcessBuilder("sh", "-c", command)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            Log.d(TAG, "[$label] $line")
            updateStatus("[$label] $line")
        }
        process.waitFor()

        if (process.exitValue() != 0) {
            throw Exception("$label ফেইল করেছে! Exit Code: ${process.exitValue()}")
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { 
            statusView.append("\n$text") 
            val parent = statusView.parent as? ScrollView
            parent?.post { parent.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}
