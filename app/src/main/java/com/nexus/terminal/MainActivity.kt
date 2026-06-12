package com.nexus.terminal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnRun = findViewById(R.id.btnRun)

        btnRun.setOnClickListener {
            val command = etInput.text.toString().trim()
            if (command.isNotEmpty()) {
                tvOutput.append("\n$ $command\n")
                etInput.text.clear()
                executeCommand(command)
            }
        }
    }

    private fun executeCommand(command: String) {
        // মেইন থ্রেড (UI) যেন ফ্রিজ না হয়, তাই ব্যাকগ্রাউন্ড থ্রেডে কমান্ড রান করছি
        Thread {
            try {
                // অ্যান্ড্রয়েডের বিল্ট-ইন শেল ব্যবহার করে কমান্ড রান করা
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                val output = StringBuilder()
                var line: String?

                // সফল আউটপুট পড়া
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                // যদি কোনো এরর হয়, সেটা পড়া
                while (errorReader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                process.waitFor()

                // রেজাল্টটি আবার মেইন স্ক্রিনে দেখানো
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
