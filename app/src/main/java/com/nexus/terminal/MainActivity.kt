package com.nexus.terminal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    
    // টার্মিনালের বর্তমান ফোল্ডার ট্র্যাক করার জন্য
    private var currentDirectory: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnRun = findViewById(R.id.btnRun)

        // অ্যাপ খোলার সময় ডিফল্ট ডিরেক্টরি সেট করা (অ্যাপের নিজস্ব সেফ স্টোরেজ)
        currentDirectory = filesDir.absolutePath

        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                // টার্মিনালের মতো পাথ এবং প্রম্পট দেখানো
                tvOutput.append("\n[$currentDirectory]$ $command\n")
                etInput.text.clear()

                // 'cd' কমান্ড আলাদাভাবে হ্যান্ডেল করা
                if (command.startsWith("cd ")) {
                    handleCdCommand(command)
                } else if (command == "clear") {
                    tvOutput.text = "NexusTerm v1.0\nReady...\n"
                } else {
                    executeCommand(command)
                }
            }
        }
    }

    private fun handleCdCommand(command: String) {
        val newDir = command.substring(3).trim()
        val targetFile = if (newDir.startsWith("/")) File(newDir) else File(currentDirectory, newDir)
        
        if (targetFile.exists() && targetFile.isDirectory) {
            currentDirectory = targetFile.canonicalPath // ডিরেক্টরি আপডেট করা
        } else {
            tvOutput.append("cd: $newDir: No such file or directory\n")
        }
    }

    private fun executeCommand(command: String) {
        Thread {
            try {
                // কমান্ড রান করার সময় বর্তমান ডিরেক্টরি বলে দেওয়া
                val workingDir = File(currentDirectory)
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command), null, workingDir)
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                val output = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                while (errorReader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                process.waitFor()

                runOnUiThread {
                    if (output.isNotEmpty()) {
                        tvOutput.append(output.toString())
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvOutput.append("Error: ${e.message}\n")
                }
            }
        }.start()
    }
}
