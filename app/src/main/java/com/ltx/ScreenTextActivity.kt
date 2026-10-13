package com.ltx

import android.content.ClipData
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.ltx.service.AutoSlideService

/**
 * 屏幕文字检查：抓取当前前台窗口的节点树文本并展示，支持搜索高亮与复制。
 * 对应需求3（在节点树信息框中加入搜索框，并将命中结果用高亮底色显示）。
 */
class ScreenTextActivity : AppCompatActivity() {
    private lateinit var contentTv: TextView
    private lateinit var searchEt: EditText
    private var fullText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_text)
        contentTv = findViewById(R.id.contentTextView)
        searchEt = findViewById(R.id.searchEditText)
        val copyBtn = findViewById<Button>(R.id.copyButton)

        fullText = runCatching {
            AutoSlideService.getInstance()?.collectScreenText() ?: "无障碍服务未运行，请在系统设置中重新开启 AutoSlide 无障碍权限"
        }.getOrDefault("无障碍服务未运行")
        contentTv.text = fullText

        searchEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                highlight(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("screen_text", fullText))
            Toast.makeText(this, R.string.macro_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun highlight(keyword: String) {
        if (keyword.isEmpty()) {
            contentTv.text = fullText
            return
        }
        val spannable = SpannableString(fullText)
        var idx = fullText.indexOf(keyword, ignoreCase = true)
        while (idx >= 0) {
            val end = idx + keyword.length
            spannable.setSpan(
                BackgroundColorSpan(Color.YELLOW),
                idx,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            idx = fullText.indexOf(keyword, idx + keyword.length, ignoreCase = true)
        }
        contentTv.text = spannable
    }
}
