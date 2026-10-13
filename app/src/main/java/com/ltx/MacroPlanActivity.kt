package com.ltx

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.UUID
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ltx.DEFAULT_MACRO_TASK_SOUND
import com.ltx.DEFAULT_MACRO_TEXT_HINT
import com.ltx.DEFAULT_MACRO_VIBRATE_START
import com.ltx.DEFAULT_MACRO_VIBRATE_STEP
import com.ltx.DEFAULT_MACRO_VIBRATE_STEP_DONE
import com.ltx.DEFAULT_MACRO_VIBRATE_DONE
import com.ltx.DEFAULT_MACRO_STEP_SOUND_START
import com.ltx.DEFAULT_MACRO_STEP_SOUND_STEP
import com.ltx.DEFAULT_MACRO_STEP_SOUND_DONE
import com.ltx.KEY_MACRO_TEXT_HINT
import com.ltx.KEY_MACRO_TASK_SOUND
import com.ltx.KEY_MACRO_VIBRATE_START
import com.ltx.KEY_MACRO_VIBRATE_STEP
import com.ltx.KEY_MACRO_VIBRATE_STEP_DONE
import com.ltx.KEY_MACRO_VIBRATE_DONE
import com.ltx.KEY_MACRO_STEP_SOUND_START
import com.ltx.KEY_MACRO_STEP_SOUND_STEP
import com.ltx.KEY_MACRO_STEP_SOUND_DONE
import com.ltx.PREFS_NAME
import com.ltx.databinding.ActivityMacroPlanBinding
import com.ltx.ACTION_RUN_MACRO
import com.ltx.ACTION_STOP_MACRO
import com.ltx.EXTRA_MACRO_PLAN_JSON
import com.ltx.service.AutoSlideService
import com.ltx.service.FloatingWindowService
import com.ltx.service.InspectFloatingService
import org.json.JSONArray
import org.json.JSONObject

/* 任务方案列表页：运行 / 编辑 / 复制 / 导出 / 删除 / 导入（选中单方案→底部共用栏；长按→多选批量） */
class MacroPlanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMacroPlanBinding
    private lateinit var plans: MutableList<MacroPlan>
    private lateinit var planAdapter: PlanAdapter
    private var pendingExportPlan: MacroPlan? = null
    // 新建方案返回后滚动到底部（修改返回则保持原位置）
    private var pendingScrollToBottom = false
    // 编辑方案返回后，焦点落回原任务项
    private var pendingSelectPlanId: String? = null
    companion object {
        const val EXTRA_PLAN_ID = "plan_id"
        private const val REQ_EXPORT = 1001
        private const val REQ_IMPORT = 1002
        private const val MENU_IMPORT = 1
        private const val MENU_TASK_HINT = 2
        private const val MENU_INSPECT = 3
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        plans = MacroPlanStore.loadAll(this).toMutableList()
        binding.newPlanButton.setOnClickListener {
            pendingScrollToBottom = true
            startActivity(Intent(this, MacroEditorActivity::class.java))
        }

        binding.planList.layoutManager = LinearLayoutManager(this)
        lateinit var touchHelper: ItemTouchHelper
        planAdapter = PlanAdapter(
            plans,
            onSelect = { updateBars() },
            onMultiSelect = { updateBars() },
            onStartDrag = { touchHelper.startDrag(it) }
        )
        binding.planList.adapter = planAdapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                makeMovementFlags(ItemHelper.UP or ItemHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from == to) return false
                plans.add(to, plans.removeAt(from))
                MacroPlanStore.saveAll(this@MacroPlanActivity, plans)
                planAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                // 拖动结束后整体刷新一次序号，确保全部同步
                planAdapter.notifyItemRangeChanged(0, plans.size)
            }
        })
        touchHelper.attachToRecyclerView(binding.planList)

        binding.menuButton.setOnClickListener { showOverflowMenu(it) }

        // 底部单选用操作栏
        binding.planRun.setOnClickListener { planAdapter.selectedPosition.let { p -> if (p in plans.indices) onRun(plans[p]) } }
        binding.planDetail.setOnClickListener { planAdapter.selectedPosition.let { p -> if (p in plans.indices) showPlanDetail(plans[p]) } }
        binding.planEdit.setOnClickListener { planAdapter.selectedPosition.let { p -> if (p in plans.indices) onEdit(plans[p]) } }
        binding.planCopy.setOnClickListener { planAdapter.selectedPosition.let { p -> if (p in plans.indices) onCopy(plans[p]) } }
        binding.planExport.setOnClickListener { planAdapter.selectedPosition.let { p -> if (p in plans.indices) onExport(plans[p]) } }
        binding.planDelete.setOnClickListener { planAdapter.selectedPosition.let { p -> if (p in plans.indices) onDelete(plans[p]) } }
        // 底部多选批量栏
        binding.multiSelectAll.setOnClickListener { selectAllPlans() }
        binding.multiCopy.setOnClickListener { batchCopy() }
        binding.multiDelete.setOnClickListener { batchDelete() }
        binding.multiCancel.setOnClickListener { exitMultiSelect() }
    }

    /* 右上角下拉菜单：导入 / 任务提示 / 屏幕文字检查 */
    private fun showOverflowMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, MENU_IMPORT, 0, R.string.macro_import)
        pm.menu.add(0, MENU_TASK_HINT, 1, R.string.macro_task_hint)
        pm.menu.add(0, MENU_INSPECT, 2, R.string.macro_inspect_text)
        pm.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                MENU_IMPORT -> onImport()
                MENU_TASK_HINT -> showTaskHintDialog()
                MENU_INSPECT -> toggleInspect()
            }
            true
        }
        pm.show()
    }

    /* 屏幕文字检查：与“屏幕文字检查”磁贴一致——切换悬浮“查”按钮（点击抓取节点树，长按移除） */
    private fun toggleInspect() {
        if (!isAccessibilityServicePermissionEnabled()) {
            Toast.makeText(this, R.string.tile_permission_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (InspectFloatingService.isRunning) {
            stopService(Intent(this, InspectFloatingService::class.java))
            Toast.makeText(this, "已隐藏屏幕文字检查按钮", Toast.LENGTH_SHORT).show()
        } else {
            startService(Intent(this, InspectFloatingService::class.java))
            Toast.makeText(this, R.string.macro_inspect_floating_hint, Toast.LENGTH_LONG).show()
        }
    }

    /* “任务提示”弹窗：文本提示 单勾选；音效提示 与 震动提示 左右并排，各自细分为
       步骤启动/步骤每步/步骤完成/任务结束(或任务完成) 等独立勾选；震动默认全不勾选 */
    private fun showTaskHintDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        val checks = mutableListOf<Pair<String, CheckBox>>()
        fun newCheck(label: String, key: String, def: Boolean): CheckBox =
            CheckBox(this).apply {
                text = label
                tag = key
                isChecked = prefs.getBoolean(key, def)
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
        fun column(title: String, items: List<Triple<String, String, Boolean>>): LinearLayout {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = (12 * density).toInt()
                layoutParams = lp
            }
            col.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (6 * density).toInt())
                setTextColor(ContextCompat.getColor(this@MacroPlanActivity, R.color.text_primary))
            })
            for ((label, key, def) in items) {
                val cb = newCheck(label, key, def)
                checks.add(key to cb)
                col.addView(cb)
            }
            return col
        }
        // 文本提示（每步文本提示）
        val textHint = newCheck("文本提示", KEY_MACRO_TEXT_HINT, DEFAULT_MACRO_TEXT_HINT)
        checks.add(KEY_MACRO_TEXT_HINT to textHint)
        container.addView(textHint)
        // 左右并排：音效提示 / 震动提示
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(column("音效提示", listOf(
            Triple("任务启动", KEY_MACRO_STEP_SOUND_START, DEFAULT_MACRO_STEP_SOUND_START),
            Triple("步骤启动", KEY_MACRO_STEP_SOUND_STEP, DEFAULT_MACRO_STEP_SOUND_STEP),
            Triple("步骤完成", KEY_MACRO_STEP_SOUND_DONE, DEFAULT_MACRO_STEP_SOUND_DONE),
            Triple("任务结束", KEY_MACRO_TASK_SOUND, DEFAULT_MACRO_TASK_SOUND)
        )))
        row.addView(column("震动提示", listOf(
            Triple("任务启动", KEY_MACRO_VIBRATE_START, DEFAULT_MACRO_VIBRATE_START),
            Triple("步骤启动", KEY_MACRO_VIBRATE_STEP, DEFAULT_MACRO_VIBRATE_STEP),
            Triple("步骤完成", KEY_MACRO_VIBRATE_STEP_DONE, DEFAULT_MACRO_VIBRATE_STEP_DONE),
            Triple("任务完成", KEY_MACRO_VIBRATE_DONE, DEFAULT_MACRO_VIBRATE_DONE)
        )))
        container.addView(row)

        AlertDialog.Builder(this)
            .setTitle(R.string.macro_task_hint)
            .setView(container)
            .setPositiveButton(R.string.macro_done) { _, _ ->
                checks.forEach { (key, cb) -> prefs.edit().putBoolean(key, cb.isChecked).apply() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        reloadPlans()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> data?.data?.let { exportToUri(it) }
            REQ_IMPORT -> {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                }
                data?.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) importFromUris(uris)
            }
        }
    }

    private fun onImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun onExport(plan: MacroPlan) {
        pendingExportPlan = plan
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, (plan.name.takeIf { it.isNotBlank() } ?: "方案") + ".json")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun exportToUri(uri: Uri) {
        val plan = pendingExportPlan ?: return
        pendingExportPlan = null
        runCatching {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(plan.toJson().toString().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入文件")
        }.onSuccess {
            Toast.makeText(this, getString(R.string.macro_exported, plan.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.macro_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromUris(uris: List<Uri>) {
        runCatching {
            val imported = mutableListOf<MacroPlan>()
            for (uri in uris) {
                val txt = contentResolver.openInputStream(uri)?.use { `is` ->
                    `is`.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalStateException("无法读取文件")
                imported.addAll(parsePlans(txt))
            }
            if (imported.isEmpty()) throw IllegalStateException("空方案")
            plans.addAll(imported)
            MacroPlanStore.saveAll(this, plans)
            imported
        }.onSuccess {
            reloadPlans()
            Toast.makeText(this, getString(R.string.macro_imported) + "（${it.size}）", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.macro_import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /* 支持导入单个方案对象或方案数组；导入一律换新 id 以免覆盖已有方案 */
    private fun parsePlans(txt: String): List<MacroPlan> {
        val trimmed = txt.trim()
        return if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            buildList {
                for (i in 0 until arr.length()) add(MacroPlan.fromJson(arr.getJSONObject(i)).withNewId())
            }
        } else {
            listOf(MacroPlan.fromJson(JSONObject(trimmed)).withNewId())
        }
    }

    /* 重新载入方案列表（复用同一 Adapter，保留滚动位置）；选中态复位 */
    private fun reloadPlans() {
        plans.clear()
        plans.addAll(MacroPlanStore.loadAll(this))
        planAdapter.selectedPosition = -1
        planAdapter.multiSelect = false
        planAdapter.selectedSet.clear()
        planAdapter.notifyDataSetChanged()
        binding.emptyHint.visibility = if (plans.isEmpty()) View.VISIBLE else View.GONE
        updateBars()
        // 编辑返回后，焦点落回原任务项
        pendingSelectPlanId?.let { id ->
            val idx = plans.indexOfFirst { it.id == id }
            if (idx >= 0) {
                planAdapter.selectedPosition = idx
                planAdapter.notifyItemChanged(idx)
            }
            pendingSelectPlanId = null
            updateBars()
        }
        if (pendingScrollToBottom) {
            if (plans.isNotEmpty()) binding.planList.scrollToPosition(plans.size - 1)
            pendingScrollToBottom = false
        }
    }

    private fun updateBars() {
        val single = planAdapter.selectedPosition >= 0 && !planAdapter.multiSelect
        binding.planActionBar.visibility = if (single) View.VISIBLE else View.GONE
        binding.planMultiBar.visibility = if (planAdapter.multiSelect) View.VISIBLE else View.GONE
        if (planAdapter.multiSelect) binding.planSelCount.text = "已选 ${planAdapter.selectedSet.size} 项"
    }

    private fun exitMultiSelect() {
        planAdapter.multiSelect = false
        planAdapter.selectedSet.clear()
        planAdapter.notifyDataSetChanged()
        updateBars()
    }

    /* 多选栏“全选”：一次性选中全部任务方案 */
    private fun selectAllPlans() {
        planAdapter.multiSelect = true
        planAdapter.selectedSet.clear()
        for (i in plans.indices) planAdapter.selectedSet.add(i)
        planAdapter.notifyDataSetChanged()
        updateBars()
    }

    /* 任务详情：以树形展示该任务中所有“已启用”步骤的逻辑信息（条件分支同样展开 true/false 子步骤） */
    private fun showPlanDetail(plan: MacroPlan) {
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = MacroStepTree.planTree(plan.steps)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            textSize = 13f
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        }
        val scroll = ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("${plan.name} · 步骤详情")
            .setView(scroll)
            .setPositiveButton(R.string.macro_done, null)
            .show()
    }

    private fun onRun(plan: MacroPlan) {
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_run)
            .setMessage(getString(R.string.macro_confirm_run, plan.name))
            .setPositiveButton(R.string.macro_run) { _, _ -> startRun(plan) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startRun(plan: MacroPlan) {
        val svc = AutoSlideService.getInstance()
        if (svc == null) {
            Toast.makeText(this, R.string.macro_need_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        val canGesture = (svc.getServiceInfo()?.capabilities
            ?: 0) and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        if (!canGesture) {
            Toast.makeText(this, "无障碍服务未获得“执行手势”能力，请到系统无障碍设置中关闭再重开本服务", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { startService(Intent(this, FloatingWindowService::class.java)) }
        svc.runMacro(plan)
        Toast.makeText(this, R.string.macro_started, Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun onCopy(plan: MacroPlan) {
        val copy = plan.copy(
            id = UUID.randomUUID().toString(),
            name = plan.name + " 副本",
            steps = plan.steps.map { it.copy() }
        )
        plans.add(copy)
        MacroPlanStore.saveAll(this, plans)
        reloadPlans()
        Toast.makeText(this, R.string.macro_copied, Toast.LENGTH_SHORT).show()
    }

    private fun onEdit(plan: MacroPlan) {
        pendingSelectPlanId = plan.id
        startActivity(Intent(this, MacroEditorActivity::class.java).putExtra(EXTRA_PLAN_ID, plan.id))
    }

    private fun onDelete(plan: MacroPlan) {
        AlertDialog.Builder(this).setMessage(R.string.macro_confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                plans.removeIf { it.id == plan.id }
                MacroPlanStore.saveAll(this, plans)
                reloadPlans()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun batchCopy() {
        val sel = plans.filterIndexed { i, _ -> planAdapter.selectedSet.contains(i) }
        if (sel.isEmpty()) return
        for (p in sel) {
            plans.add(p.copy(id = UUID.randomUUID().toString(), name = p.name + " 副本", steps = p.steps.map { it.copy() }))
        }
        MacroPlanStore.saveAll(this, plans)
        exitMultiSelect()
        reloadPlans()
        Toast.makeText(this, "已复制 ${sel.size} 个方案", Toast.LENGTH_SHORT).show()
    }

    private fun batchDelete() {
        val sel = plans.filterIndexed { i, _ -> planAdapter.selectedSet.contains(i) }
        if (sel.isEmpty()) return
        AlertDialog.Builder(this).setMessage("确定删除选中的 ${sel.size} 个方案？")
            .setPositiveButton(R.string.delete) { _, _ ->
                plans.removeAll(sel.toSet())
                MacroPlanStore.saveAll(this, plans)
                exitMultiSelect()
                reloadPlans()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* 导入时换新 id，避免覆盖已有同名/同 id 方案 */
    private fun MacroPlan.withNewId() = copy(id = UUID.randomUUID().toString())
}

/* ItemTouchHelper 方向常量（避免与 MotionEvent 等冲突，单独封装） */
private object ItemHelper {
    const val UP = androidx.recyclerview.widget.ItemTouchHelper.UP
    const val DOWN = androidx.recyclerview.widget.ItemTouchHelper.DOWN
}

class PlanAdapter(
    private val plans: List<MacroPlan>,
    private val onSelect: (Int) -> Unit,
    private val onMultiSelect: (Set<Int>) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<PlanAdapter.VH>() {

    var selectedPosition = -1
    var multiSelect = false
    val selectedSet = mutableSetOf<Int>()

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.planIndex)
        val name: TextView = itemView.findViewById(R.id.planName)
        val summary: TextView = itemView.findViewById(R.id.planSummary)
        val handle: TextView = itemView.findViewById(R.id.planHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.macro_plan_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = plans[pos]
        h.index.text = (pos + 1).toString()
        h.name.text = p.name
        h.summary.text = p.summary()
        val selected = if (multiSelect) selectedSet.contains(pos) else pos == selectedPosition
        h.itemView.isSelected = selected
        h.itemView.setBackgroundColor(
            if (selected) 0xFFE9E2F7.toInt() else ContextCompat.getColor(h.itemView.context, R.color.surface)
        )
        h.itemView.setOnClickListener {
            val pp = h.bindingAdapterPosition
            if (pp < 0) return@setOnClickListener
            if (multiSelect) {
                if (selectedSet.contains(pp)) selectedSet.remove(pp) else selectedSet.add(pp)
                if (selectedSet.isEmpty()) multiSelect = false
                notifyItemChanged(pp)
                onMultiSelect(selectedSet.toSet())
            } else {
                val newSel = if (selectedPosition == pp) -1 else pp
                val old = selectedPosition
                selectedPosition = newSel
                if (old >= 0 && old != newSel) notifyItemChanged(old)
                notifyItemChanged(pp)
                onSelect(newSel)
            }
        }
        h.itemView.setOnLongClickListener {
            val pp = h.bindingAdapterPosition
            if (pp < 0) return@setOnLongClickListener true
            if (!multiSelect) {
                multiSelect = true
                selectedSet.clear()
                selectedSet.add(pp)
                if (selectedPosition >= 0) {
                    val o = selectedPosition
                    selectedPosition = -1
                    notifyItemChanged(o)
                }
                notifyItemChanged(pp)
                onMultiSelect(selectedSet.toSet())
            }
            true
        }
        h.handle.setOnLongClickListener { onStartDrag(h); true }
    }

    override fun getItemCount() = plans.size
}
