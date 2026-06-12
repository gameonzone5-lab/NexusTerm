package com.nexus.terminal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity

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

        // রান বাটনে ক্লিক করলে ইনপুট ফিল্ডের লেখা আউটপুট স্ক্রিনে দেখাবে
        btnRun.setOnClickListener {
            val command = etInput.text.toString()
            if (command.isNotEmpty()) {
                tvOutput.append("\n$ $command\n")
                etInput.text.clear() // ইনপুট ফিল্ড ক্লিয়ার করা
            }
        }
    }
}
