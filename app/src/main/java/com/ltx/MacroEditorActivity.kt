package com.ltx

import android.app.AlertDialog
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ltx.databinding.ActivityMacroEditorBinding
import com.ltx.databinding.DialogStepEditorBinding
import com.ltx.databinding.DialogSubStepEditorBinding
import com.ltx.MACRO_PAUSE_FIXED
import com.ltx.MACRO_PAUSE_NONE
import com.ltx.MACRO_PAUSE_RANDOM
import java.util.UUID

/* 方案编辑页：编辑名称 / 重复次数 / 步骤列表 */
class MacroEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMacroEditorBinding
    private var planId: String? = null
    private lateinit var current: MacroPlan
    private val steps = mutableListOf<MacroStep>()
    // 步骤列表复用同一个 Adapter 实例，仅用 notify* 局部刷新，避免每次重建导致滚动/焦点复位
    private lateinit var stepAdapter: StepAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        planId = intent.getStringExtra(MacroPlanActivity.EXTRA_PLAN_ID)
        val existing = planId?.let { id -> MacroPlanStore.loadAll(this).firstOrNull { it.id == id } }
        current = existing ?: MacroPlan(id = UUID.randomUUID().toString(), name = "", repeatCount = 1, steps = emptyList())

        binding.planName.setText(current.name)
        binding.repeatCount.setText(current.repeatCount.toString())
        steps.addAll(current.steps)

        binding.stepList.layoutManager = LinearLayoutManager(this)
        // 主列表拖动辅助（手柄触发）；先声明，供 adapter 的 onStartDrag 引用
        lateinit var touchHelper: ItemTouchHelper
        stepAdapter = StepAdapter(
            steps, ::onStepToggle, { p -> updateStepBar(p) },
            onShowDetail = { showBranchDetail(it) },
            onMultiSelect = { updateStepBar(stepAdapter.selectedPosition) },
            onStartDrag = { touchHelper.startDrag(it) }
        )
        binding.stepList.adapter = stepAdapter
        // 手指拖动排序（长按手柄触发，与 ↑/↓ 按钮并存）；拖动时不重建 Adapter，保持当前位置
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from == to) return false
                steps.add(to, steps.removeAt(from))
                fixSelAfterMove(stepAdapter, from, to)
                stepAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                // 拖动结束后整体刷新序号，确保全部同步
                stepAdapter.notifyItemRangeChanged(0, steps.size)
            }
        })
        touchHelper.attachToRecyclerView(binding.stepList)
        refreshSteps()
        binding.addStepButton.setOnClickListener {
            showStepDialog(null, steps) { refreshSteps(); binding.stepList.scrollToPosition(steps.size - 1) }
        }
        binding.saveButton.setOnClickListener { save() }

        // 选中步骤后出现的公用操作栏（详情/排序/编辑/复制/复刻/删除）
        binding.stepDetailBtn.setOnClickListener { val p = stepAdapter.selectedPosition; if (p >= 0 && isBranch(steps[p])) showBranchDetail(steps[p]) }
        binding.stepUpBtn.setOnClickListener { onStepUp(stepAdapter.selectedPosition) }
        binding.stepDownBtn.setOnClickListener { onStepDown(stepAdapter.selectedPosition) }
        binding.stepEditBtn.setOnClickListener { onStepEdit(stepAdapter.selectedPosition) }
        binding.stepCopyBtn.setOnClickListener {
            if (stepAdapter.multiSelect) batchCopySteps() else onStepCopy(stepAdapter.selectedPosition)
        }
        binding.stepReplicaBtn.setOnClickListener { onStepReplica() }
        binding.stepDeleteBtn.setOnClickListener { onStepDelete(stepAdapter.selectedPosition) }
        binding.stepSelectAllBtn.setOnClickListener { toggleSelectAll(stepAdapter, steps.size) { updateStepBar(stepAdapter.selectedPosition) } }
        binding.stepEmbedBtn.setOnClickListener { embedReusedSteps() }

        // 有未保存修改时，按返回键先询问保存/放弃
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasChanges()) showSaveConfirm() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    private fun hasChanges(): Boolean {
        val name = binding.planName.text.toString().trim()
        val rep = binding.repeatCount.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        return name != current.name || rep != current.repeatCount || steps != current.steps
    }

    private fun showSaveConfirm() {
        AlertDialog.Builder(this)
            .setTitle("未保存修改")
            .setMessage("有未保存的步骤修改，是否保存后再退出？")
            .setPositiveButton("保存") { _, _ -> save() }
            .setNegativeButton("不保存") { _, _ -> finish() }
            .setNeutralButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        CoordinatePicker.stop(this)
        super.onDestroy()
    }

    /* 拾取完成后把本任务栈重新提到前台，回到方案编辑页 */
    private fun bringEditorToFront() {
        val intent = Intent(this, MacroEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    private fun refreshSteps() {
        binding.stepTitle.text = "步骤（${steps.count { it.enabled }}/${steps.size}）"
        if (::stepAdapter.isInitialized) stepAdapter.notifyDataSetChanged()
    }

    private fun save() {
        val name = binding.planName.text.toString().trim()
        if (name.isEmpty()) { toast(R.string.macro_name_empty); return }
        if (steps.isEmpty()) { toast(R.string.macro_no_step); return }
        val rep = binding.repeatCount.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        val plan = current.copy(name = name, repeatCount = rep, steps = steps.toList())
        val all = MacroPlanStore.loadAll(this).toMutableList()
        val idx = all.indexOfFirst { it.id == plan.id }
        if (idx >= 0) all[idx] = plan else all.add(plan)
        MacroPlanStore.saveAll(this, all)
        finish()
    }

    private fun showStepDialog(existing: MacroStep?, list: MutableList<MacroStep>, onChanged: () -> Unit) {
        val dlg = DialogStepEditorBinding.inflate(layoutInflater)
        val types = arrayOf("slide", "back", "click_text", "click_position", "delay", "wait_text", "branch", "branch_if", "drag")
        val typeLabels = arrayOf(
            getString(R.string.step_slide), getString(R.string.step_back), getString(R.string.step_click_text),
            getString(R.string.step_click_position), getString(R.string.step_delay), getString(R.string.step_wait_text),
            getString(R.string.step_branch), getString(R.string.step_branch_if), getString(R.string.step_drag)
        )
        val dirs = arrayOf(DIRECTION_DOWN, DIRECTION_UP, DIRECTION_LEFT, DIRECTION_RIGHT, DIRECTION_UP_DOWN)
        val dirLabels = arrayOf("下", "上", "左", "右", "上下")
        val step = existing ?: MacroStep(type = MacroActionType.CLICK_TEXT, whitelist = emptyList())
        // 条件类型选项随动作类型变化：仅“条件单支”多一个“恒成立”
        fun modesForType(t: String) = if (t == "branch") arrayOf("appear", "disappear", "always") else arrayOf("appear", "disappear")
        fun modeLabelsForType(t: String) = if (t == "branch") arrayOf("等待出现", "等待消失", "恒成立") else arrayOf("等待出现", "等待消失")
        var modes = modesForType(step.type.key)
        var modeLabels = modeLabelsForType(step.type.key)
        val pauses = arrayOf(MACRO_PAUSE_NONE, MACRO_PAUSE_FIXED, MACRO_PAUSE_RANDOM)
        val pauseLabels = arrayOf(
            getString(R.string.macro_pause_none), getString(R.string.macro_pause_fixed), getString(R.string.macro_pause_random)
        )

        // 安全获取 Spinner 选中下标，避免 selectedItemPosition==-1 触发数组越界崩溃
        fun sp(spinner: AdapterView<*>, size: Int): Int {
            val p = spinner.selectedItemPosition
            return if (p in 0 until size) p else 0
        }

        fun <T> spinner(adapter: ArrayAdapter<T>) = adapter.also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

        dlg.stepType.adapter = spinner(ArrayAdapter(this, R.layout.item_spinner_closed, typeLabels))
        dlg.stepDirection.adapter = spinner(ArrayAdapter(this, R.layout.item_spinner_closed, dirLabels))
        dlg.stepWaitMode.adapter = spinner(ArrayAdapter(this, R.layout.item_spinner_closed, modeLabels))
        dlg.stepPauseMode.adapter = spinner(ArrayAdapter(this, R.layout.item_spinner_closed, pauseLabels))

        val subSteps = step.subSteps.toMutableList()
        val falseSubSteps = step.falseSubSteps.toMutableList()
        dlg.stepType.setSelection(types.indexOf(step.type.key).coerceAtLeast(0))
        dlg.stepDirection.setSelection(dirs.indexOf(step.direction).coerceAtLeast(0))
        dlg.stepWaitMode.setSelection(modes.indexOf(step.waitMode).coerceAtLeast(0))
        dlg.stepPauseMode.setSelection(pauses.indexOf(step.pauseMode).coerceAtLeast(0))
        dlg.stepName.setText(step.name)
        dlg.stepDuration.setText(secToText(step.durationSec))
        dlg.stepPauseTime.setText(step.pauseTime.toString())
        dlg.stepMinPause.setText(step.minPauseTime.toString())
        dlg.stepMaxPause.setText(step.maxPauseTime.toString())
        dlg.stepWhitelist.setText(step.whitelist.joinToString(","))
        dlg.stepBlacklist.setText(step.blacklist.joinToString(","))
        dlg.stepMaxClicks.setText(step.maxClicks.toString())
        dlg.stepPosX.setText("%.1f".format(step.posX))
        dlg.stepPosY.setText("%.1f".format(step.posY))
        dlg.dragStartX.setText("%.1f".format(step.dragStartX))
        dlg.dragStartY.setText("%.1f".format(step.dragStartY))
        dlg.dragEndX.setText("%.1f".format(step.dragEndX))
        dlg.dragEndY.setText("%.1f".format(step.dragEndY))
        dlg.dragDuration.setText(secToText(step.dragDurationMs / 1000f))
        dlg.stepDelay.setText(secToText(step.delaySec))
        dlg.stepWaitText.setText(step.waitText)
        dlg.stepTimeout.setText(secToText(step.timeoutSec))
        dlg.stepExclude.setText(step.excludeText)
        dlg.stepLoop.setText(step.loopCount.toString())
        dlg.stepPosClicks.setText(step.maxClicks.toString())
        dlg.stepBackLoop.setText(step.loopCount.toString())
        dlg.stepBackDelay.setText(secToText(step.loopDelaySec))
        dlg.stepDragLoop.setText(step.loopCount.toString())
        dlg.stepDragDelay.setText(secToText(step.loopDelaySec))
        // true/false 子步骤数量直接体现在按钮文本上（无需额外结果文本）
        fun refreshSubLabel() {
            dlg.stepEditSub.text = "编辑 true 子步骤" + if (subSteps.isNotEmpty()) "(${subSteps.size}个)" else ""
        }
        fun refreshFalseLabel() {
            dlg.stepEditSubFalse.text = "编辑 false 子步骤" + if (falseSubSteps.isNotEmpty()) "(${falseSubSteps.size}个)" else ""
        }
        refreshSubLabel()
        refreshFalseLabel()
        val branchLabel = if (step.type == MacroActionType.BRANCH) "条件单支" else "条件分支"
        dlg.stepEditSub.setOnClickListener { showSubStepEditor(subSteps, ::refreshSubLabel, branchLabel) }
        dlg.stepEditSubFalse.setOnClickListener { showSubStepEditor(falseSubSteps, ::refreshFalseLabel, branchLabel) }

        // 统一控制“延迟等待 / 条件类型 / 循环次数 / 等待文字 / 排除文字 / 超时”的显隐：
        // - 延迟等待：仅“延迟”类型显示；条件分支不再显示（去掉多余标题）
        // - 条件类型 / 循环次数：仅条件分支（条件单支/条件分支）显示，条件等待不显示
        // - 等待文字 / 排除文字 / 超时：条件分支且“恒成立”时整体隐藏
        fun updateWaitDetailVisibility() {
            val t = types[sp(dlg.stepType, types.size)]
            val isWait = t == "wait_text"
            val isBranch = t == "branch" || t == "branch_if"
            val always = isBranch && modes[sp(dlg.stepWaitMode, modes.size)] == "always"
            // 条件类型 / 循环次数：仅条件分支（条件单支/条件分支）显示，条件等待不显示
            dlg.waitModeLabel.visibility = if (isBranch) View.VISIBLE else View.GONE
            dlg.stepWaitMode.visibility = if (isBranch) View.VISIBLE else View.GONE
            dlg.boxLoop.visibility = if (isBranch) View.VISIBLE else View.GONE
            // 等待文字 / 排除文字 / 超时：条件分支且“恒成立”时整体隐藏
            val detailVis = if (isBranch && always) View.GONE else View.VISIBLE
            dlg.boxWaitText.visibility = detailVis
            dlg.boxExclude.visibility = detailVis
            dlg.boxTimeout.visibility = detailVis
            // 延迟等待：仅“延迟”类型显示（条件分支不再显示该组）
            dlg.groupDelay.visibility = if (t == "delay") View.VISIBLE else View.GONE
        }

        // 后退：循环次数为 1 时，间隔延迟固定为 0 且不可编辑
        fun updateBackDelayState() {
            val loop = dlg.stepBackLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
            val disable = loop <= 1
            dlg.stepBackDelay.isEnabled = !disable
            if (disable) dlg.stepBackDelay.setText("0")
        }

        fun updatePauseGroups() {
            val pm = pauses[sp(dlg.stepPauseMode, pauses.size)]
            dlg.stepPauseTime.visibility = if (pm == MACRO_PAUSE_FIXED) View.VISIBLE else View.GONE
            dlg.groupPauseRandom.visibility = if (pm == MACRO_PAUSE_RANDOM) View.VISIBLE else View.GONE
        }

        fun updateGroups() {
            val t = types[sp(dlg.stepType, types.size)]
            // 条件类型（等待条件）选项随动作类型变化：仅“条件单支”多一个“恒成立”
            modes = modesForType(t)
            modeLabels = modeLabelsForType(t)
            dlg.stepWaitMode.adapter = spinner(ArrayAdapter(this, R.layout.item_spinner_closed, modeLabels))
            val validMode = if (modes.contains(step.waitMode)) step.waitMode else "appear"
            step.waitMode = validMode
            dlg.stepWaitMode.setSelection(modes.indexOf(validMode).coerceAtLeast(0))

            dlg.groupSlide.visibility = if (t == "slide") View.VISIBLE else View.GONE
            dlg.groupClick.visibility = if (t == "click_text") View.VISIBLE else View.GONE
            dlg.groupPosition.visibility = if (t == "click_position") View.VISIBLE else View.GONE
            dlg.stepDelay.visibility = if (t == "delay") View.VISIBLE else View.GONE
            val isWait = t == "wait_text"
            val isBranch = t == "branch" || t == "branch_if"
            dlg.groupWait.visibility = if (isWait || isBranch) View.VISIBLE else View.GONE
            dlg.groupDrag.visibility = if (t == "drag") View.VISIBLE else View.GONE
            dlg.groupBack.visibility = if (t == "back") View.VISIBLE else View.GONE
            dlg.groupBranch.visibility = if (isBranch) View.VISIBLE else View.GONE
            // 仅“条件分支”显示 false 子步骤按钮（条件单支只有 true）
            dlg.stepEditSubFalse.visibility = if (t == "branch_if") View.VISIBLE else View.GONE
            updateWaitDetailVisibility()
            updateBackDelayState()
            updatePauseGroups()
        }

        dlg.stepType.post { updateGroups() }
        dlg.stepType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = updateGroups()
            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }
        dlg.stepWaitMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = updateWaitDetailVisibility()
            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }
        dlg.stepBackLoop.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateBackDelayState()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 构建当前步骤（用于“保存”或“复用”）
        fun buildStep(): MacroStep {
            val t = types[sp(dlg.stepType, types.size)]
            val built = when (MacroActionType.fromKey(t)) {
                MacroActionType.SLIDE -> MacroStep(
                    type = MacroActionType.SLIDE,
                    direction = dirs[sp(dlg.stepDirection, dirs.size)],
                    durationSec = dlg.stepDuration.text.toString().toFloatOrNull()?.coerceAtLeast(0.1f) ?: 0.5f,
                    pauseMode = pauses[sp(dlg.stepPauseMode, pauses.size)],
                    pauseTime = dlg.stepPauseTime.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1,
                    minPauseTime = dlg.stepMinPause.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1,
                    maxPauseTime = dlg.stepMaxPause.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 3
                )
                MacroActionType.BACK -> MacroStep(
                    type = MacroActionType.BACK,
                    loopCount = dlg.stepBackLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    loopDelaySec = run {
                        val loop = dlg.stepBackLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                        if (loop <= 1) 0f else dlg.stepBackDelay.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: 1f
                    }
                )
                MacroActionType.CLICK_TEXT -> MacroStep(
                    type = MacroActionType.CLICK_TEXT,
                    whitelist = splitCsv(dlg.stepWhitelist.text.toString()),
                    blacklist = splitCsv(dlg.stepBlacklist.text.toString()),
                    maxClicks = dlg.stepMaxClicks.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                )
                MacroActionType.CLICK_POSITION -> MacroStep(
                    type = MacroActionType.CLICK_POSITION,
                    posX = dlg.stepPosX.text.toString().toFloatOrNull()?.coerceIn(0f, 100f) ?: 50f,
                    posY = dlg.stepPosY.text.toString().toFloatOrNull()?.coerceIn(0f, 100f) ?: 50f,
                    maxClicks = dlg.stepPosClicks.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                )
                MacroActionType.DELAY -> MacroStep(
                    type = MacroActionType.DELAY,
                    delaySec = dlg.stepDelay.text.toString().toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f
                )
                MacroActionType.WAIT_TEXT -> MacroStep(
                    type = MacroActionType.WAIT_TEXT,
                    waitText = dlg.stepWaitText.text.toString().trim(),
                    waitMode = modes[sp(dlg.stepWaitMode, modes.size)],
                    timeoutSec = dlg.stepTimeout.text.toString().toFloatOrNull()?.coerceAtLeast(0.1f) ?: 10f,
                    excludeText = dlg.stepExclude.text.toString().trim(),
                    loopCount = dlg.stepLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                )
                MacroActionType.BRANCH -> MacroStep(
                    type = MacroActionType.BRANCH,
                    waitText = dlg.stepWaitText.text.toString().trim(),
                    waitMode = modes[sp(dlg.stepWaitMode, modes.size)],
                    timeoutSec = dlg.stepTimeout.text.toString().toFloatOrNull()?.coerceAtLeast(0.1f) ?: 10f,
                    excludeText = dlg.stepExclude.text.toString().trim(),
                    loopCount = dlg.stepLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    subSteps = subSteps.toList()
                )
                MacroActionType.BRANCH_IF -> MacroStep(
                    type = MacroActionType.BRANCH_IF,
                    waitText = dlg.stepWaitText.text.toString().trim(),
                    waitMode = modes[sp(dlg.stepWaitMode, modes.size)],
                    timeoutSec = dlg.stepTimeout.text.toString().toFloatOrNull()?.coerceAtLeast(0.1f) ?: 10f,
                    excludeText = dlg.stepExclude.text.toString().trim(),
                    loopCount = dlg.stepLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    subSteps = subSteps.toList(),
                    falseSubSteps = falseSubSteps.toList()
                )
                MacroActionType.DRAG -> MacroStep(
                    type = MacroActionType.DRAG,
                    dragStartX = dlg.dragStartX.text.toString().toFloatOrNull()?.coerceIn(0f, 100f) ?: 50f,
                    dragStartY = dlg.dragStartY.text.toString().toFloatOrNull()?.coerceIn(0f, 100f) ?: 50f,
                    dragEndX = dlg.dragEndX.text.toString().toFloatOrNull()?.coerceIn(0f, 100f) ?: 50f,
                    dragEndY = dlg.dragEndY.text.toString().toFloatOrNull()?.coerceIn(0f, 100f) ?: 50f,
                    dragDurationMs = ((dlg.dragDuration.text.toString().toFloatOrNull()?.coerceAtLeast(0.1f) ?: 0.5f) * 1000f).toInt().coerceAtLeast(100),
                    loopCount = dlg.stepDragLoop.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    loopDelaySec = dlg.stepDragDelay.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                )
            }
            return built.copy(
                name = dlg.stepName.text.toString().trim(),
                enabled = existing?.enabled ?: true
            )
        }

        // 构建并写入当前步骤（新增或覆盖）
        fun commit() {
            val s = buildStep()
            if (existing == null) list.add(s)
            else {
                // 用引用相等定位，避免多个“内容相同”的步骤被命中第一项（导致改错项/位置错乱）
                val i = list.indexOfFirst { it === existing }
                if (i >= 0) list[i] = s
            }
            onChanged()
        }

        // 退出前若已填入内容，先提示是否保存（避免误触“取消”丢失编辑）；
        // 不自动关闭步骤框，由确认结果决定（“取消”回到继续编辑）
        fun confirmDiscard(d: AlertDialog) {
            val baseline = existing ?: MacroStep(type = MacroActionType.CLICK_TEXT, whitelist = emptyList())
            if (buildStep() == baseline) { d.dismiss(); return }
            AlertDialog.Builder(this)
                .setTitle(R.string.macro_unsaved_title)
                .setMessage(R.string.macro_unsaved_msg)
                .setPositiveButton(R.string.save) { _, _ -> commit(); d.dismiss() }
                .setNegativeButton(R.string.macro_discard) { _, _ -> d.dismiss() }
                .setNeutralButton(R.string.cancel, null)
                .show()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.macro_add_step else R.string.macro_edit_plan)
            .setView(dlg.root)
            .setPositiveButton(R.string.save) { _, _ -> commit() }
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .show()
        dialog.setCanceledOnTouchOutside(false)
        // 自接管“取消”点击（不走默认的“点击即关闭”），先弹保存确认
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { confirmDiscard(dialog) }

        // 拾取坐标：先把本 App 退到后台，让目标 App 在遮罩之下可见，再弹可拖拽准心 overlay；
        // 选中只回填 X%/Y% 并回到编辑页，对话框保持打开，由用户手动确认保存（方便微调）
        dlg.stepPickCoord.setOnClickListener {
            if (!isAccessibilityServicePermissionEnabled()) {
                Toast.makeText(this, "请先在系统设置中开启本应用的无障碍权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启“显示在其他应用上”悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            moveTaskToBack(true)
            CoordinatePicker.start(
                this,
                initXPct = dlg.stepPosX.text.toString().toFloatOrNull() ?: existing?.posX ?: 50f,
                initYPct = dlg.stepPosY.text.toString().toFloatOrNull() ?: existing?.posY ?: 50f,
                onPicked = { xPct, yPct ->
                    runOnUiThread {
                        dlg.stepPosX.setText("%.1f".format(xPct))
                        dlg.stepPosY.setText("%.1f".format(yPct))
                    }
                    bringEditorToFront()
                },
                onCancel = { bringEditorToFront() }
            )
        }
        dlg.dragPickStart.setOnClickListener {
            if (!isAccessibilityServicePermissionEnabled()) {
                Toast.makeText(this, "请先在系统设置中开启本应用的无障碍权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启“显示在其他应用上”悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            moveTaskToBack(true)
            CoordinatePicker.start(
                this,
                initXPct = dlg.dragStartX.text.toString().toFloatOrNull() ?: existing?.dragStartX ?: 50f,
                initYPct = dlg.dragStartY.text.toString().toFloatOrNull() ?: existing?.dragStartY ?: 50f,
                onPicked = { xPct, yPct ->
                    runOnUiThread {
                        dlg.dragStartX.setText("%.1f".format(xPct))
                        dlg.dragStartY.setText("%.1f".format(yPct))
                    }
                    bringEditorToFront()
                },
                onCancel = { bringEditorToFront() }
            )
        }
        dlg.dragPickEnd.setOnClickListener {
            if (!isAccessibilityServicePermissionEnabled()) {
                Toast.makeText(this, "请先在系统设置中开启本应用的无障碍权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启“显示在其他应用上”悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            moveTaskToBack(true)
            CoordinatePicker.start(
                this,
                initXPct = dlg.dragEndX.text.toString().toFloatOrNull() ?: existing?.dragEndX ?: 50f,
                initYPct = dlg.dragEndY.text.toString().toFloatOrNull() ?: existing?.dragEndY ?: 50f,
                onPicked = { xPct, yPct ->
                    runOnUiThread {
                        dlg.dragEndX.setText("%.1f".format(xPct))
                        dlg.dragEndY.setText("%.1f".format(yPct))
                    }
                    bringEditorToFront()
                },
                onCancel = { bringEditorToFront() }
            )
        }
        dlg.stepPauseMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = updatePauseGroups()
            override fun onNothingSelected(p: AdapterView<*>) = Unit
        }
    }

    private fun splitCsv(s: String): List<String> =
        s.split(",").map { it.trim() }.filter { it.isNotBlank() }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    private fun onStepUp(pos: Int) {
        if (pos <= 0) return
        steps.add(pos - 1, steps.removeAt(pos))
        fixSelAfterMove(stepAdapter, pos, pos - 1)
        stepAdapter.notifyItemMoved(pos, pos - 1)
        stepAdapter.notifyItemRangeChanged(0, steps.size)
        updateStepBar(stepAdapter.selectedPosition)
    }
    private fun onStepDown(pos: Int) {
        if (pos < 0 || pos >= steps.size - 1) return
        steps.add(pos + 1, steps.removeAt(pos))
        fixSelAfterMove(stepAdapter, pos, pos + 1)
        stepAdapter.notifyItemMoved(pos, pos + 1)
        stepAdapter.notifyItemRangeChanged(0, steps.size)
        updateStepBar(stepAdapter.selectedPosition)
    }
    private fun onStepEdit(pos: Int) {
        if (pos < 0 || pos >= steps.size) return
        showStepDialog(steps[pos], steps) { refreshSteps(); updateStepBar(stepAdapter.selectedPosition) }
    }
    private fun onStepCopy(pos: Int) {
        if (pos < 0 || pos >= steps.size) return
        steps.add(pos + 1, steps[pos].copy())
        refreshSteps()
    }
    /* 复刻：把当前选中的步骤（单选或多选）拷贝到暂存区，供“嵌入步骤”粘贴复用 */
    private fun onStepReplica() {
        val sel = if (stepAdapter.multiSelect) steps.indices.filter { stepAdapter.selectedSet.contains(it) }
        else listOf(stepAdapter.selectedPosition).filter { it in steps.indices }
        if (sel.isEmpty()) return
        ReuseHolder.list.clear()
        for (i in sel) ReuseHolder.list.add(steps[i].copy())
        Toast.makeText(this, R.string.macro_replica_ok, Toast.LENGTH_SHORT).show()
    }
    /* 批量复制：把选中的步骤就地复制一份（保留原选中态） */
    private fun batchCopySteps() {
        val sel = steps.indices.filter { stepAdapter.selectedSet.contains(it) }
        if (sel.isEmpty()) return
        val copies = sel.map { steps[it].copy() }
        steps.addAll(copies)
        stepAdapter.multiSelect = false
        stepAdapter.selectedSet.clear()
        stepAdapter.notifyItemRangeInserted(steps.size - copies.size, copies.size)
        updateStepBar(-1)
        refreshSteps()
    }
    /* 嵌入：把暂存区中复刻的步骤粘贴到当前列表末尾 */
    private fun embedReusedSteps() {
        if (ReuseHolder.list.isEmpty()) {
            Toast.makeText(this, R.string.macro_reuse_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val inserts = ReuseHolder.list.map { it.copy() }
        steps.addAll(inserts)
        stepAdapter.notifyItemRangeInserted(steps.size - inserts.size, inserts.size)
        binding.stepList.scrollToPosition(steps.size - 1)
        Toast.makeText(this, getString(R.string.macro_embed_step_ok, inserts.size), Toast.LENGTH_SHORT).show()
        refreshSteps()
    }
    private fun fixSelAfterMove(adapter: StepAdapter, from: Int, to: Int) {
        when (adapter.selectedPosition) {
            from -> adapter.selectedPosition = to
            to -> adapter.selectedPosition = from
        }
    }
    private fun updateStepBar(pos: Int) {
        val show = stepAdapter.multiSelect || (pos >= 0 && pos < steps.size)
        binding.stepActionBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        if (stepAdapter.multiSelect) {
            // 多选时启用“复制/复刻/删除”，禁用需要具体目标项的按钮
            binding.stepDetailBtn.isEnabled = false; binding.stepDetailBtn.alpha = 0.3f
            binding.stepUpBtn.isEnabled = false; binding.stepUpBtn.alpha = 0.3f
            binding.stepDownBtn.isEnabled = false; binding.stepDownBtn.alpha = 0.3f
            binding.stepEditBtn.isEnabled = false; binding.stepEditBtn.alpha = 0.3f
            binding.stepCopyBtn.isEnabled = true; binding.stepCopyBtn.alpha = 1f
            binding.stepReplicaBtn.isEnabled = true; binding.stepReplicaBtn.alpha = 1f
            binding.stepDeleteBtn.isEnabled = true; binding.stepDeleteBtn.alpha = 1f
        } else {
            val b = isBranch(steps[pos])
            binding.stepDetailBtn.isEnabled = b
            binding.stepDetailBtn.alpha = if (b) 1f else 0.4f
            binding.stepUpBtn.isEnabled = pos > 0; binding.stepUpBtn.alpha = if (pos > 0) 1f else 0.3f
            binding.stepDownBtn.isEnabled = pos < steps.size - 1; binding.stepDownBtn.alpha = if (pos < steps.size - 1) 1f else 0.3f
            binding.stepEditBtn.isEnabled = true; binding.stepEditBtn.alpha = 1f
            binding.stepCopyBtn.isEnabled = true; binding.stepCopyBtn.alpha = 1f
            binding.stepReplicaBtn.isEnabled = true; binding.stepReplicaBtn.alpha = 1f
            binding.stepDeleteBtn.isEnabled = true; binding.stepDeleteBtn.alpha = 1f
        }
    }
    /* 全选/取消全选（主列表与子步骤列表共用） */
    private fun toggleSelectAll(adapter: StepAdapter, count: Int, onChanged: () -> Unit) {
        if (adapter.multiSelect && adapter.selectedSet.size == count) {
            adapter.multiSelect = false
            adapter.selectedSet.clear()
        } else {
            adapter.multiSelect = true
            adapter.selectedSet.clear()
            for (i in 0 until count) adapter.selectedSet.add(i)
        }
        adapter.notifyDataSetChanged()
        onChanged()
    }
    private fun isBranch(s: MacroStep) = s.type == MacroActionType.BRANCH || s.type == MacroActionType.BRANCH_IF

    /* 条件分支子步骤编辑：复用 StepAdapter，子步骤同样支持选中+公用操作栏（嵌套对话框，不切 Activity） */
    private fun showSubStepEditor(subList: MutableList<MacroStep>, onChanged: () -> Unit, branchLabel: String = "条件分支") {
        val dlg = DialogSubStepEditorBinding.inflate(layoutInflater)
        dlg.subStepList.layoutManager = LinearLayoutManager(this)
        lateinit var adapter: StepAdapter
        lateinit var touch: ItemTouchHelper
        var dirty = false
        fun refreshSubTitle() { dlg.subStepTitle.text = "步骤（${subList.count { it.enabled }}/${subList.size}）" }
        fun fixSel(from: Int, to: Int) { when (adapter.selectedPosition) { from -> adapter.selectedPosition = to; to -> adapter.selectedPosition = from } }
        fun onUp(p: Int) {
            if (p <= 0) return
            dirty = true
            subList.add(p - 1, subList.removeAt(p)); fixSel(p, p - 1); adapter.notifyItemMoved(p, p - 1)
        }
        fun onDown(p: Int) {
            if (p < 0 || p >= subList.size - 1) return
            dirty = true
            subList.add(p + 1, subList.removeAt(p)); fixSel(p, p + 1); adapter.notifyItemMoved(p, p + 1)
        }
        fun onEdit(p: Int) {
            if (p < 0 || p >= subList.size) return
            showStepDialog(subList[p], subList) { dirty = true; adapter.notifyItemChanged(p); updateSubBar(dlg, adapter, subList) }
        }
        fun onCopy(p: Int) {
            if (p < 0 || p >= subList.size) return
            dirty = true
            subList.add(p + 1, subList[p].copy()); adapter.notifyItemInserted(p + 1); refreshSubTitle()
        }
        fun onToggle(p: Int, e: Boolean) {
            if (p < 0 || p >= subList.size) return
            dirty = true
            subList[p] = subList[p].copy(enabled = e); adapter.notifyItemChanged(p)
        }
        fun onSelect(p: Int) { updateSubBar(dlg, adapter, subList) }
        fun batchCopySub() {
            val sel = subList.indices.filter { adapter.selectedSet.contains(it) }
            if (sel.isEmpty()) return
            val copies = sel.map { subList[it].copy() }
            subList.addAll(copies)
            adapter.multiSelect = false
            adapter.selectedSet.clear()
            adapter.notifyItemRangeInserted(subList.size - copies.size, copies.size)
            refreshSubTitle()
            updateSubBar(dlg, adapter, subList)
            dirty = true
        }
        fun batchDeleteSub() {
            val idxs = subList.indices.filter { adapter.selectedSet.contains(it) }
            if (idxs.isEmpty()) return
            AlertDialog.Builder(this)
                .setTitle(R.string.macro_delete_step)
                .setMessage("确定删除选中的 ${idxs.size} 个子步骤？")
                .setPositiveButton(R.string.delete) { _, _ ->
                    idxs.sortedDescending().forEach { subList.removeAt(it) }
                    adapter.multiSelect = false
                    adapter.selectedSet.clear()
                    adapter.notifyDataSetChanged()
                    refreshSubTitle()
                    updateSubBar(dlg, adapter, subList)
                    dirty = true
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        fun onReplica() {
            val sel = if (adapter.multiSelect) subList.indices.filter { adapter.selectedSet.contains(it) }
            else listOf(adapter.selectedPosition).filter { it in subList.indices }
            if (sel.isEmpty()) return
            ReuseHolder.list.clear()
            for (i in sel) ReuseHolder.list.add(subList[i].copy())
            Toast.makeText(this, R.string.macro_replica_ok, Toast.LENGTH_SHORT).show()
        }
        fun onDelete(p: Int) {
            if (adapter.multiSelect) { batchDeleteSub(); return }
            if (p < 0 || p >= subList.size) return
            val s = subList[p]
            AlertDialog.Builder(this)
                .setTitle(R.string.macro_delete_step)
                .setMessage("确定删除该子步骤吗？\n${s.summary()}")
                .setPositiveButton(R.string.delete) { _, _ ->
                    subList.removeAt(p)
                    adapter.selectedPosition = -1
                    adapter.notifyItemRemoved(p)
                    refreshSubTitle()
                    updateSubBar(dlg, adapter, subList)
                    dirty = true
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        adapter = StepAdapter(subList, ::onToggle, ::onSelect, onShowDetail = { showBranchDetail(it) }, onMultiSelect = { updateSubBar(dlg, adapter, subList) }, onStartDrag = { touch.startDrag(it) })
        dlg.subStepList.adapter = adapter
        refreshSubTitle()
        // 子步骤也支持手指长按手柄拖动排序（保持位置，不重建列表）
        touch = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from == to) return false
                subList.add(to, subList.removeAt(from))
                fixSel(from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.notifyItemRangeChanged(0, subList.size)
            }
        })
        touch.attachToRecyclerView(dlg.subStepList)
        dlg.stepDetailBtn.setOnClickListener { val p = adapter.selectedPosition; if (p >= 0 && isBranch(subList[p])) showBranchDetail(subList[p]) }
        dlg.stepUpBtn.setOnClickListener { onUp(adapter.selectedPosition) }
        dlg.stepDownBtn.setOnClickListener { onDown(adapter.selectedPosition) }
        dlg.stepEditBtn.setOnClickListener { onEdit(adapter.selectedPosition) }
        dlg.stepCopyBtn.setOnClickListener { if (adapter.multiSelect) batchCopySub() else onCopy(adapter.selectedPosition) }
        dlg.stepReplicaBtn.setOnClickListener { onReplica() }
        dlg.stepDeleteBtn.setOnClickListener { onDelete(adapter.selectedPosition) }
        dlg.selectAllBtn.setOnClickListener { toggleSelectAll(adapter, subList.size) { updateSubBar(dlg, adapter, subList) } }
        dlg.addSubStep.setOnClickListener { showStepDialog(null, subList) { dirty = true; adapter.notifyItemInserted(subList.size - 1); refreshSubTitle() } }
        dlg.embedStep.setOnClickListener {
            if (ReuseHolder.list.isEmpty()) { Toast.makeText(this, R.string.macro_reuse_empty, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val inserts = ReuseHolder.list.map { it.copy() }
            subList.addAll(inserts)
            adapter.notifyItemRangeInserted(subList.size - inserts.size, inserts.size)
            refreshSubTitle()
            dirty = true
        }

        lateinit var subDialog: AlertDialog
        fun maybeCloseSub() {
            if (!dirty) { subDialog.dismiss(); return }
            AlertDialog.Builder(this)
                .setTitle("有未保存的修改")
                .setMessage("子步骤已修改，确定关闭编辑？修改将在步骤“保存”时一并生效。")
                .setPositiveButton("继续编辑", null)
                .setNegativeButton("关闭") { _, _ -> dirty = false; subDialog.dismiss() }
                .show()
        }
        subDialog = AlertDialog.Builder(this)
            .setTitle("$branchLabel · 子步骤")
            .setView(dlg.root)
            .setPositiveButton(R.string.macro_done) { _, _ -> dirty = false; onChanged() }
            .setNegativeButton(R.string.cancel) { _, _ -> maybeCloseSub() }
            .setCancelable(false)
            .show()
        subDialog.setCanceledOnTouchOutside(false)
    }

    private fun updateSubBar(dlg: DialogSubStepEditorBinding, adapter: StepAdapter, subList: List<MacroStep>) {
        val show = adapter.multiSelect || (adapter.selectedPosition >= 0 && adapter.selectedPosition < subList.size)
        dlg.stepActionBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        if (adapter.multiSelect) {
            // 多选时启用“复制/复刻/删除”，禁用需要具体目标项的按钮
            dlg.stepDetailBtn.isEnabled = false; dlg.stepDetailBtn.alpha = 0.3f
            dlg.stepUpBtn.isEnabled = false; dlg.stepUpBtn.alpha = 0.3f
            dlg.stepDownBtn.isEnabled = false; dlg.stepDownBtn.alpha = 0.3f
            dlg.stepEditBtn.isEnabled = false; dlg.stepEditBtn.alpha = 0.3f
            dlg.stepCopyBtn.isEnabled = true; dlg.stepCopyBtn.alpha = 1f
            dlg.stepReplicaBtn.isEnabled = true; dlg.stepReplicaBtn.alpha = 1f
            dlg.stepDeleteBtn.isEnabled = true; dlg.stepDeleteBtn.alpha = 1f
        } else {
            val p = adapter.selectedPosition
            val b = isBranch(subList[p])
            dlg.stepDetailBtn.isEnabled = b
            dlg.stepDetailBtn.alpha = if (b) 1f else 0.4f
            dlg.stepUpBtn.isEnabled = p > 0; dlg.stepUpBtn.alpha = if (p > 0) 1f else 0.3f
            dlg.stepDownBtn.isEnabled = p < subList.size - 1; dlg.stepDownBtn.alpha = if (p < subList.size - 1) 1f else 0.3f
            dlg.stepEditBtn.isEnabled = true; dlg.stepEditBtn.alpha = 1f
            dlg.stepCopyBtn.isEnabled = true; dlg.stepCopyBtn.alpha = 1f
            dlg.stepDeleteBtn.isEnabled = true; dlg.stepDeleteBtn.alpha = 1f
        }
    }
    private fun onStepToggle(pos: Int, enabled: Boolean) {
        steps[pos] = steps[pos].copy(enabled = enabled)
        refreshSteps()
    }
    private fun onStepDelete(pos: Int) {
        if (stepAdapter.multiSelect) { batchDeleteSteps(); return }
        if (pos < 0 || pos >= steps.size) return
        val s = steps[pos]
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_delete_step)
            .setMessage("确定删除该步骤吗？\n${s.summary()}")
            .setPositiveButton(R.string.delete) { _, _ ->
                steps.removeAt(pos)
                stepAdapter.selectedPosition = -1
                stepAdapter.notifyItemRemoved(pos)
                updateStepBar(-1)
                refreshSteps()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* 多选批量删除步骤（按索引删除，避免内容相同的步骤被一并误删） */
    private fun batchDeleteSteps() {
        val idxs = steps.indices.filter { stepAdapter.selectedSet.contains(it) }
        if (idxs.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_delete_step)
            .setMessage("确定删除选中的 ${idxs.size} 个步骤？")
            .setPositiveButton(R.string.delete) { _, _ ->
                idxs.sortedDescending().forEach { steps.removeAt(it) }
                stepAdapter.multiSelect = false
                stepAdapter.selectedSet.clear()
                stepAdapter.notifyDataSetChanged()
                updateStepBar(-1)
                refreshSteps()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* 分支详情：以树形列出 true/false 子步骤（支持嵌套分支） */
    private fun showBranchDetail(step: MacroStep) {
        val tv = TextView(this)
        tv.text = MacroStepTree.branchTree(step)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.textSize = 13f
        tv.setPadding(40, 24, 40, 24)
        val scroll = ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_step_detail)
            .setView(scroll)
            .setPositiveButton(R.string.macro_done, null)
            .show()
    }

    /* 秒数转输入框文本：整数去小数（如 1），否则保留 1 位小数（如 0.5） */
    private fun secToText(sec: Float): String =
        if (sec == sec.toInt().toFloat()) sec.toInt().toString() else "%.1f".format(sec)
}

/* 复刻步骤的暂存区：点击“复刻”存入（可多个），在步骤/子步骤中点击“嵌入步骤”取出 */
object ReuseHolder {
    val list: MutableList<MacroStep> = mutableListOf()
}

/* 步骤树形文本渲染：供“详情”弹窗复用（步骤/子步骤的分支详情、任务的全部已启用步骤）。
   连接符统一使用半角 ASCII（+-- `-- | 与空格）：
   Unicode 制表符（│├└─┬）在中文等宽字体下按全宽测量，会与半角空格错位，导致树形不工整。 */
object MacroStepTree {

    fun isBranch(s: MacroStep) = s.type == MacroActionType.BRANCH || s.type == MacroActionType.BRANCH_IF

    /* 单个条件分支的详情树：分支自身为根，true/false 子步骤向下分叉 */
    fun branchTree(step: MacroStep): String {
        val sb = StringBuilder()
        sb.append(branchHeader(step)).append("\n")
        appendGroups(sb, branchGroups(step), "   ")
        return sb.toString().trimEnd()
    }

    /* 任务详情树：列出该任务中所有“已启用”的顶层步骤；条件分支同样展开 true/false 子步骤 */
    fun planTree(steps: List<MacroStep>): String {
        val enabled = steps.filter { it.enabled }
        if (enabled.isEmpty()) return "（没有已启用的步骤）"
        val numW = enabled.size.toString().length          // 序号宽度（如 9→1 位、10→2 位）
        val indent = " ".repeat(numW + 2)                   // 与序号前缀“N. ”等宽，组连接符与步骤文字左缘对齐
        val sb = StringBuilder()
        enabled.forEachIndexed { i, s ->
            sb.append("${(i + 1).toString().padStart(numW)}. ").append(stepContent(s))
            if (isBranch(s)) {
                sb.append("：\n")
                appendGroups(sb, branchGroups(s), indent)
            } else {
                sb.append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /* 以「true/false 分组」为节点递归绘制树：
       组行只放“✔ true / ✘ false”标签，其下所有子步骤同列列举（空组占位一行）；
       非末组、非末子项用 "|" 竖线延续；全部为半角字符，任意等宽字体下均严格对齐 */
    private fun appendGroups(sb: StringBuilder, groups: List<Pair<String, List<MacroStep>>>, indent: String) {
        groups.forEachIndexed { gi, (label, children) ->
            val groupLast = gi == groups.lastIndex
            val sym = if (label == "true") "✔" else "✘"
            sb.append(indent).append(if (groupLast) "`-- " else "├-- ") //"+-- "  //"├-- "
                .append(sym).append(" ").append(label).append("\n")
            val childIndent = indent + if (groupLast) "    " else "|   "
            if (children.isEmpty()) {
                sb.append(childIndent).append("`-- (空)").append("\n")
            } else {
                children.forEachIndexed { ci, c ->
                    val childLast = ci == children.lastIndex
                    sb.append(childIndent).append(if (childLast) "`-- " else "├-- ") //"+-- "  //"├-- "
                        .append(stepContent(c)).append("\n")
                    if (isBranch(c)) {
                        appendGroups(sb, branchGroups(c), childIndent + if (childLast) "    " else "|   ")
                    }
                }
            }
        }
    }

    private fun branchGroups(step: MacroStep): List<Pair<String, List<MacroStep>>> {
        val list = mutableListOf<Pair<String, List<MacroStep>>>()
        list.add("true" to step.subSteps.filter { it.enabled })
        if (step.type == MacroActionType.BRANCH_IF) list.add("false" to step.falseSubSteps.filter { it.enabled })
        return list
    }

    private fun stepContent(c: MacroStep): String =
        if (isBranch(c)) branchHeader(c) else c.summary()

    private fun branchHeader(step: MacroStep): String {
        val name = when (step.type) {
            MacroActionType.BRANCH -> "条件单支"
            MacroActionType.BRANCH_IF -> "条件分支"
            else -> "条件分支"
        }
        val base = if (step.waitMode == "always") {
            "$name（恒成立）"
        } else {
            val targets = step.waitText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .joinToString("/").ifBlank { step.waitText }
            val mode = if (step.waitMode == "appear") "等待出现" else "等待消失"
            "$name「$targets」($mode, ${fmtSec(step.timeoutSec)})"
        }
        // 循环次数（条件重复执行次数）大于 1 时补充显示
        return if (step.loopCount > 1) "$base 循环${step.loopCount}次" else base
    }

    private fun fmtSec(sec: Float): String =
        if (sec == sec.toInt().toFloat()) "${sec.toInt()}s" else String.format("%.1f", sec) + "s"
}

class StepAdapter(
    private val steps: List<MacroStep>,
    private val onToggle: (Int, Boolean) -> Unit,
    private val onSelect: (Int) -> Unit,
    private val onShowDetail: (MacroStep) -> Unit = {},
    private val onMultiSelect: (Set<Int>) -> Unit = {},
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<StepAdapter.VH>() {

    var selectedPosition: Int = -1
    var multiSelect: Boolean = false
    val selectedSet = mutableSetOf<Int>()

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView? = itemView.findViewById(R.id.stepIndex)
        val name: TextView = itemView.findViewById(R.id.stepName)
        val summary: TextView = itemView.findViewById(R.id.stepSummary)
        val enabled: CheckBox = itemView.findViewById(R.id.stepEnabled)
        val handle: TextView = itemView.findViewById(R.id.stepHandle)
        val detailBtn: MaterialButton = itemView.findViewById(R.id.stepDetailBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.macro_step_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = steps[pos]
        h.index?.text = (pos + 1).toString()
        h.name.text = s.name
        h.name.visibility = if (s.name.isBlank()) View.GONE else View.VISIBLE
        h.summary.text = s.summary()
        h.summary.alpha = if (s.enabled) 1f else 0.45f
        h.name.alpha = if (s.enabled) 1f else 0.45f
        h.enabled.isChecked = s.enabled
        h.enabled.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) onToggle(p, h.enabled.isChecked) }
        val selected = if (multiSelect) selectedSet.contains(pos) else pos == selectedPosition
        h.itemView.isSelected = selected
        h.itemView.setBackgroundColor(if (selected) 0xFFE9E2F7.toInt() else ContextCompat.getColor(h.itemView.context, R.color.surface))
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p < 0) return@setOnClickListener
            if (multiSelect) {
                if (selectedSet.contains(p)) selectedSet.remove(p) else selectedSet.add(p)
                if (selectedSet.isEmpty()) multiSelect = false
                notifyItemChanged(p)
                onMultiSelect(selectedSet.toSet())
            } else {
                val newSel = if (selectedPosition == p) -1 else p
                val old = selectedPosition
                selectedPosition = newSel
                if (old >= 0 && old != newSel) notifyItemChanged(old)
                notifyItemChanged(p)
                onSelect(newSel)
            }
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p < 0) return@setOnLongClickListener true
            if (!multiSelect) {
                multiSelect = true
                selectedSet.clear()
                selectedSet.add(p)
                if (selectedPosition >= 0) { val o = selectedPosition; selectedPosition = -1; notifyItemChanged(o) }
                notifyItemChanged(p)
                onMultiSelect(selectedSet.toSet())
            }
            true
        }
        // 多选时禁用“详情”（无具体目标项）
        val detailEnabled = !multiSelect && (s.type == MacroActionType.BRANCH || s.type == MacroActionType.BRANCH_IF)
        h.detailBtn.isEnabled = detailEnabled
        h.detailBtn.alpha = if (detailEnabled) 1f else 0.3f
        h.detailBtn.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0 && (steps[p].type == MacroActionType.BRANCH || steps[p].type == MacroActionType.BRANCH_IF)) onShowDetail(steps[p])
        }
        h.handle.setOnLongClickListener { onStartDrag(h); true }
    }

    override fun getItemCount() = steps.size
}
