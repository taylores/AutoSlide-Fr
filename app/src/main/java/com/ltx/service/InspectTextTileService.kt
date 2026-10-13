package com.ltx.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ltx.MainActivity
import com.ltx.R
import com.ltx.isAccessibilityServicePermissionEnabled
import android.view.Gravity
import android.view.WindowManager
import android.widget.ScrollView

/**
 * “屏幕文字检查”快捷磁贴：仅负责启动悬浮“查”按钮服务（不切换前台 App），
 * 由悬浮按钮在目标 App 仍为前台时抓取文字。
 */
class InspectTextTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (!isAccessibilityServicePermissionEnabled()) {
            Toast.makeText(this, R.string.tile_permission_toast, Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
            return
        }
        try {
            // 再次点击磁贴：若按钮已显示则隐藏，否则显示（切换）
            if (InspectFloatingService.isRunning) {
                stopService(Intent(this, InspectFloatingService::class.java))
                Toast.makeText(this, "已隐藏屏幕文字检查按钮", Toast.LENGTH_SHORT).show()
            } else {
                startService(Intent(this, InspectFloatingService::class.java))
                Toast.makeText(this, R.string.macro_inspect_floating_hint, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "操作失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
