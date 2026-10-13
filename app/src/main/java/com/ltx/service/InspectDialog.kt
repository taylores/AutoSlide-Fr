package com.ltx.service

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.ltx.R

/** 用系统级（TYPE_APPLICATION_OVERLAY）对话框展示屏幕文字检查结果，不依赖任何 Activity */
object InspectDialog {
    fun show(context: Context, content: String) {
        val ctx = ContextThemeWrapper(context, R.style.Theme_AutoSlide)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val searchEt = EditText(ctx).apply {
            hint = "搜索文字"
            setSingleLine()
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        val prevBtn = Button(ctx).apply { text = "上一个"; textSize = 13f }
        val nextBtn = Button(ctx).apply { text = "下一个"; textSize = 13f }
        val countTv = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        val navRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            addView(prevBtn)
            addView(countTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextBtn)
        }

        val tv = TextView(ctx).apply {
            this.text = content
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTextIsSelectable(true) // 不可编辑但可选中复制
            gravity = Gravity.START
        }
        val scroll = ScrollView(ctx).apply { addView(tv) }

        root.addView(searchEt)
        root.addView(navRow)
        root.addView(scroll)

        var keyword = ""
        var matchIndices = listOf<Int>()
        var current = -1

        fun applyHighlight() {
            if (keyword.isEmpty()) {
                tv.text = content
                matchIndices = emptyList()
                current = -1
                countTv.text = ""
                return
            }
            val sp = SpannableString(content)
            val found = mutableListOf<Int>()
            var idx = content.indexOf(keyword, ignoreCase = true)
            while (idx >= 0) {
                found.add(idx)
                sp.setSpan(
                    BackgroundColorSpan(Color.YELLOW),
                    idx, idx + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = content.indexOf(keyword, idx + keyword.length, ignoreCase = true)
            }
            // 当前结果用青色高亮，便于定位
            if (current in found.indices) {
                val c = found[current]
                sp.setSpan(
                    BackgroundColorSpan(Color.CYAN),
                    c, c + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tv.text = sp
            matchIndices = found
            countTv.text = "一共 ${found.size} 个目标结果"
        }

        fun gotoMatch(dir: Int) {
            if (matchIndices.isEmpty()) return
            current = when {
                current < 0 -> if (dir > 0) 0 else matchIndices.lastIndex
                else -> (current + dir + matchIndices.size) % matchIndices.size
            }
            applyHighlight()
            // 滚动到当前结果所在行
            tv.post {
                val layout = tv.layout ?: return@post
                val line = layout.getLineForOffset(matchIndices[current])
                val y = layout.getLineTop(line)
                scroll.scrollTo(0, y)
            }
        }

        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                keyword = s?.toString().orEmpty()
                current = -1
                applyHighlight()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        prevBtn.setOnClickListener { gotoMatch(-1) }
        nextBtn.setOnClickListener { gotoMatch(1) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.macro_inspect_text)
            .setView(root)
            .setPositiveButton(R.string.macro_copy) { _, _ -> copy(context, content) }
            .setNegativeButton(android.R.string.ok, null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("screen_text", text))
        Toast.makeText(context, R.string.macro_copied, Toast.LENGTH_SHORT).show()
    }
}
