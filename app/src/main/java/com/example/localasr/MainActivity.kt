package com.example.localasr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.localasr.asr.AsrForegroundService
import com.example.localasr.history.HistoryDatabase
import com.example.localasr.history.TranscriptSession
import com.example.localasr.model.DownloadProgress
import com.example.localasr.model.DownloadResult
import com.example.localasr.model.DownloadSource
import com.example.localasr.model.ModelCatalog
import com.example.localasr.model.ModelManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView
    private lateinit var modelManager: ModelManager
    private lateinit var historyDatabase: HistoryDatabase

    private var transcriptView: TextView? = null
    private var recordButton: Button? = null
    private var recordingStatus: TextView? = null
    private var recordingDetail: TextView? = null
    private var recordingStatusDot: TextView? = null
    private var lastTranscript = ""
    private var pendingRecordPermission = false

    private var downloading = false
    private var selectedSource = DownloadSource.DOMESTIC
    private var modelProgress: ProgressBar? = null
    private var modelStatus: TextView? = null
    private var modelActionButton: Button? = null
    private var modelDeleteButton: Button? = null
    private var stateReceiverRegistered = false
    private val navItems = mutableMapOf<Page, TextView>()

    private val asrStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AsrForegroundService.ACTION_STATE_CHANGED) syncRecordingUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelManager = ModelManager(applicationContext)
        historyDatabase = HistoryDatabase(applicationContext)
        selectedSource = loadDownloadSource()
        setContentView(buildShell())
        showTranscribe()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AsrForegroundService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                asrStateReceiver,
                filter,
                AsrForegroundService.STATE_PERMISSION,
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerReceiver(
                asrStateReceiver,
                filter,
                AsrForegroundService.STATE_PERMISSION,
                null,
            )
        }
        stateReceiverRegistered = true
        syncRecordingUi()
    }

    override fun onStop() {
        if (stateReceiverRegistered) {
            unregisterReceiver(asrStateReceiver)
            stateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        historyDatabase.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO || !pendingRecordPermission) return
        pendingRecordPermission = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            showMessage("需要麦克风权限才能进行语音识别")
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val systemBars = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                    )
                    view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                }
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        header.addView(label("LOCAL ASR", 11f, true, COLOR_PRIMARY).apply {
            letterSpacing = 0.18f
        }, matchWrap())
        pageTitle = label("", 25f, true)
        header.addView(pageTitle, topMargin(4))
        pageSubtitle = label("", 13f, false, COLOR_MUTED)
        header.addView(pageSubtitle, topMargin(3))
        root.addView(header, matchWrap())

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
        }
        navigation.addView(navItem(Page.TRANSCRIBE, "实时转写") { showTranscribe() }, weightedNav())
        navigation.addView(navItem(Page.MODELS, "模型管理") { showModels() }, weightedNav())
        navigation.addView(navItem(Page.HISTORY, "历史记录") { showHistory() }, weightedNav())
        root.addView(navigation, matchWrap())
        return root
    }

    private fun showTranscribe() {
        setPage(Page.TRANSCRIBE, "实时语音转文字", "完全离线处理，音频不会上传")
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }

        val statusCard = horizontalCard().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        recordingStatusDot = TextView(this).apply {
            text = "●"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(COLOR_SUCCESS)
        }
        statusCard.addView(recordingStatusDot, LinearLayout.LayoutParams(dp(28), dp(34)))
        val statusTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        recordingStatus = label("", 15f, true)
        recordingDetail = label("离线引擎 · 16 kHz 麦克风", 12f, false, COLOR_MUTED)
        statusTexts.addView(recordingStatus, matchWrap())
        statusTexts.addView(recordingDetail, topMargin(2))
        statusCard.addView(statusTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(statusCard, matchWrap().apply { bottomMargin = dp(12) })

        val transcriptCard = verticalCard().apply {
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val transcriptHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transcriptHeader.addView(label("实时文本", 16f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        transcriptHeader.addView(chip("仅本地处理", COLOR_PRIMARY, COLOR_PRIMARY_SOFT), wrapWrap())
        transcriptCard.addView(transcriptHeader, matchWrap())

        transcriptView = TextView(this).apply {
            text = AsrForegroundService.currentTranscript.ifBlank {
                lastTranscript.ifBlank { "准备好后点击“开始转写”\n说话内容会实时显示在这里" }
            }
            textSize = 17f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(if (lastTranscript.isBlank()) COLOR_MUTED else COLOR_TEXT)
            setTextIsSelectable(true)
            gravity = Gravity.TOP
            setPadding(0, dp(16), 0, 0)
            movementMethod = ScrollingMovementMethod()
        }
        transcriptCard.addView(
            transcriptView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(2)
            },
        )
        page.addView(transcriptCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            bottomMargin = dp(14)
        })

        recordButton = primaryButton(if (AsrForegroundService.isActive) "停止并保存" else "开始转写").apply {
            setOnClickListener {
                if (AsrForegroundService.isActive) stopRecording() else requestRecording()
            }
        }
        page.addView(recordButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        replaceContent(page)
        syncRecordingUi()
    }

    private fun requestRecording() {
        if (!modelManager.isInstalled()) {
            AlertDialog.Builder(this)
                .setTitle("需要先下载模型")
                .setMessage("APK 不内置语音模型。下载完成后，识别可完全离线运行。")
                .setNegativeButton("取消", null)
                .setPositiveButton("模型管理") { _, _ -> showModels() }
                .show()
            return
        }
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) {
            pendingRecordPermission = true
            requestPermissions(permissions.toTypedArray(), REQUEST_RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        recordButton?.isEnabled = false
        recordingStatus?.text = "正在加载模型…"
        lastTranscript = ""
        transcriptView?.apply {
            text = ""
            setTextColor(COLOR_TEXT)
        }
        val intent = Intent(this, AsrForegroundService::class.java)
            .setAction(AsrForegroundService.ACTION_START)
        startForegroundService(intent)
    }

    private fun stopRecording() {
        recordButton?.apply {
            text = "正在结束…"
            isEnabled = false
        }
        recordingStatus?.text = "正在整理最后一段文字…"
        startService(
            Intent(this, AsrForegroundService::class.java)
                .setAction(AsrForegroundService.ACTION_STOP),
        )
    }

    private fun syncRecordingUi() {
        val serviceTranscript = AsrForegroundService.currentTranscript
        if (serviceTranscript.isNotBlank()) lastTranscript = serviceTranscript
        recordingStatus?.text = when {
            AsrForegroundService.currentStatus.isNotBlank() -> AsrForegroundService.currentStatus
            modelManager.isInstalled() -> "模型已就绪，内容仅在本机处理"
            else -> "尚未安装模型，请先进入模型管理"
        }
        recordingDetail?.text = when {
            AsrForegroundService.isActive -> "前台服务运行中 · 可锁屏或切换应用"
            modelManager.isInstalled() -> "模型校验通过 · 随时可以开始"
            else -> "请先在模型管理中完成下载"
        }
        recordingStatusDot?.setTextColor(
            when {
                AsrForegroundService.isActive -> COLOR_RECORDING
                modelManager.isInstalled() -> COLOR_SUCCESS
                else -> COLOR_WARNING
            },
        )
        transcriptView?.apply {
            text = lastTranscript.ifBlank { "准备好后点击“开始转写”\n说话内容会实时显示在这里" }
            setTextColor(if (lastTranscript.isBlank()) COLOR_MUTED else COLOR_TEXT)
        }
        recordButton?.apply {
            text = if (AsrForegroundService.isActive) "停止并保存" else "开始转写"
            isEnabled = !AsrForegroundService.currentStatus.startsWith("正在整理")
            background = roundedBackground(
                if (AsrForegroundService.isActive) COLOR_RECORDING else COLOR_PRIMARY,
                16f,
            )
        }
        modelDeleteButton?.isEnabled =
            !downloading && !AsrForegroundService.isActive && modelManager.downloadedBytes() > 0L
    }

    private fun showModels() {
        setPage(Page.MODELS, "模型管理", "按需下载，支持暂停和断点续传")
        val model = ModelCatalog.streamingParaformer
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }

        val privacyCard = horizontalCard().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundedBackground(COLOR_PRIMARY_SOFT, 16f)
        }
        privacyCard.addView(label("✓", 18f, true, COLOR_PRIMARY), LinearLayout.LayoutParams(dp(28), dp(32)))
        privacyCard.addView(
            label("APK 不内置模型，下载完成后可完全离线使用", 13f, false, COLOR_PRIMARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        body.addView(privacyCard, matchWrap().apply { bottomMargin = dp(12) })

        val modelCard = verticalCard().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titleTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleTexts.addView(label(model.name, 18f, true), matchWrap())
        titleTexts.addView(label(model.description, 13f, false, COLOR_MUTED), topMargin(5))
        titleRow.addView(titleTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(chip("INT8", COLOR_SUCCESS, COLOR_SUCCESS_SOFT), wrapWrap().apply { leftMargin = dp(8) })
        modelCard.addView(titleRow, matchWrap())

        modelCard.addView(divider(), matchWrap().apply {
            topMargin = dp(18)
            bottomMargin = dp(16)
        })
        modelCard.addView(label("选择下载源", 14f, true), matchWrap())
        val sources = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val domestic = RadioButton(this).apply {
            id = View.generateViewId()
            text = "国内镜像   hf-mirror.com"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            isChecked = selectedSource == DownloadSource.DOMESTIC
        }
        val official = RadioButton(this).apply {
            id = View.generateViewId()
            text = "官方原站   huggingface.co"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            isChecked = selectedSource == DownloadSource.OFFICIAL
        }
        sources.addView(domestic)
        sources.addView(official)
        sources.setOnCheckedChangeListener { _, checkedId ->
            selectedSource = if (checkedId == official.id) DownloadSource.OFFICIAL else DownloadSource.DOMESTIC
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, selectedSource.name)
                .apply()
        }
        modelCard.addView(sources, matchWrap())

        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_BORDER)
        }
        modelCard.addView(modelProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
            topMargin = dp(18)
        })
        modelStatus = label("", 14f, false, COLOR_MUTED)
        modelCard.addView(modelStatus, topMargin(9))

        modelActionButton = primaryButton("下载模型").apply {
            setOnClickListener {
                if (downloading) pauseDownload() else startDownload()
            }
        }
        modelCard.addView(modelActionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(18)
        })
        modelDeleteButton = dangerButton("删除本地模型").apply {
            setOnClickListener { confirmDeleteModel() }
        }
        modelCard.addView(modelDeleteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(8)
        })
        body.addView(modelCard, matchWrap())

        body.addView(label("下载说明", 14f, true), topMargin(18))
        body.addView(
            label("下载中断后会保留已完成部分。再次点击继续时，只获取剩余内容；完成后自动执行 SHA-256 校验。", 13f, false, COLOR_MUTED).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            topMargin(6),
        )

        val scroll = ScrollView(this).apply { addView(body) }
        replaceContent(scroll)
        refreshModelUi()
    }

    private fun startDownload() {
        downloading = true
        refreshModelUi()
        modelManager.download(
            source = selectedSource,
            onProgress = { progress -> runOnUiThread { renderDownloadProgress(progress) } },
            onResult = { result ->
                runOnUiThread {
                    downloading = false
                    when (result) {
                        DownloadResult.Completed -> showMessage("模型下载并校验完成")
                        DownloadResult.Paused -> showMessage("已暂停，可随时继续")
                        is DownloadResult.Failed -> showMessage(result.message)
                    }
                    refreshModelUi()
                }
            },
        )
    }

    private fun pauseDownload() {
        modelActionButton?.isEnabled = false
        modelStatus?.text = "正在暂停…"
        modelManager.pause()
    }

    private fun renderDownloadProgress(progress: DownloadProgress) {
        val ratio = if (progress.totalBytes == 0L) 0 else {
            ((progress.downloadedBytes * 1000L) / progress.totalBytes).toInt().coerceIn(0, 1000)
        }
        modelProgress?.progress = ratio
        modelStatus?.text = "${progress.message}\n${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
    }

    private fun refreshModelUi() {
        val model = ModelCatalog.streamingParaformer
        val installed = modelManager.isInstalled()
        val downloaded = modelManager.downloadedBytes()
        modelProgress?.progress = ((downloaded * 1000L) / model.totalBytes).toInt().coerceIn(0, 1000)
        modelStatus?.text = when {
            installed -> "已安装并校验 · ${formatBytes(model.totalBytes)}"
            downloaded > 0L -> "可继续下载 · ${formatBytes(downloaded)} / ${formatBytes(model.totalBytes)}"
            else -> "未安装 · 需要下载 ${formatBytes(model.totalBytes)}"
        }
        modelActionButton?.apply {
            text = when {
                downloading -> "暂停下载"
                installed -> "重新校验 / 下载"
                downloaded > 0L -> "继续下载"
                else -> "下载模型"
            }
            isEnabled = true
        }
        modelDeleteButton?.isEnabled = !downloading && !AsrForegroundService.isActive && downloaded > 0L
    }

    private fun confirmDeleteModel() {
        if (downloading) return
        if (AsrForegroundService.isActive) {
            showMessage("请先停止当前转写，再删除模型")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除本地模型？")
            .setMessage("删除后历史文字仍会保留，但再次识别前需要重新下载模型。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                modelManager.deleteModel()
                refreshModelUi()
            }
            .show()
    }

    private fun showHistory() {
        setPage(Page.HISTORY, "转写历史", "每次停止转写后自动保存")
        val sessions = historyDatabase.list()
        if (sessions.isEmpty()) {
            val outer = FrameLayout(this).apply { setPadding(dp(20), dp(30), dp(20), dp(30)) }
            val empty = verticalCard().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(42), dp(28), dp(42))
            }
            empty.addView(label("○", 38f, false, COLOR_PRIMARY).apply { gravity = Gravity.CENTER }, matchWrap())
            empty.addView(label("还没有转写记录", 18f, true).apply { gravity = Gravity.CENTER }, topMargin(12))
            empty.addView(
                label("完成一次实时转写后，内容会自动保存在这里", 14f, false, COLOR_MUTED).apply {
                    gravity = Gravity.CENTER
                },
                topMargin(7),
            )
            outer.addView(empty, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            replaceContent(outer)
            return
        }

        val list = ListView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(18))
            clipToPadding = false
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = sessions.size
                override fun getItem(position: Int): TranscriptSession = sessions[position]
                override fun getItemId(position: Int): Long = sessions[position].id

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val session = sessions[position]
                    val outer = FrameLayout(this@MainActivity).apply {
                        setPadding(dp(4), dp(5), dp(4), dp(5))
                    }
                    val card = verticalCard().apply {
                        setPadding(dp(17), dp(15), dp(17), dp(15))
                    }
                    val top = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    top.addView(
                        label(formatHistoryDate(session.startedAt), 15f, true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    top.addView(chip(formatDuration(session.durationMillis), COLOR_PRIMARY, COLOR_PRIMARY_SOFT), wrapWrap())
                    card.addView(top, matchWrap())
                    card.addView(label(historyPreview(session), 14f, false, COLOR_MUTED).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setLineSpacing(dp(2).toFloat(), 1f)
                    }, topMargin(9))
                    outer.addView(card, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    return outer
                }
            }
            setOnItemClickListener { _, _, position, _ -> showHistoryDetails(sessions[position]) }
        }
        replaceContent(list)
    }

    private fun showHistoryDetails(session: TranscriptSession) {
        val textView = TextView(this).apply {
            text = session.text
            textSize = 17f
            setTextColor(COLOR_TEXT)
            setTextIsSelectable(true)
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(formatHistoryDate(session.startedAt))
            .setView(ScrollView(this).apply { addView(textView) })
            .setNeutralButton("删除") { _, _ -> confirmDeleteHistory(session) }
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("语音转写", session.text))
                showMessage("已复制")
            }
            .show()
    }

    private fun confirmDeleteHistory(session: TranscriptSession) {
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage("此操作不会删除模型。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                historyDatabase.delete(session.id)
                showHistory()
            }
            .show()
    }

    private fun replaceContent(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun historyPreview(session: TranscriptSession): String =
        session.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1000L
        return "${totalSeconds / 60}:${String.format(Locale.ROOT, "%02d", totalSeconds % 60)}"
    }

    private fun formatHistoryDate(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))

    private fun loadDownloadSource(): DownloadSource {
        val saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_SOURCE, null)
        return DownloadSource.entries.firstOrNull { it.name == saved } ?: DownloadSource.DOMESTIC
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun setPage(page: Page, title: String, subtitle: String) {
        pageTitle.text = title
        pageSubtitle.text = subtitle
        navItems.forEach { (itemPage, view) ->
            val selected = itemPage == page
            view.setTextColor(if (selected) COLOR_PRIMARY else COLOR_MUTED)
            view.typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            view.background = if (selected) {
                roundedBackground(COLOR_PRIMARY_SOFT, 13f)
            } else {
                roundedBackground(Color.TRANSPARENT, 13f)
            }
        }
    }

    private fun navItem(page: Page, text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(44)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { action() }
        navItems[page] = this
    }

    private fun primaryButton(text: String) = Button(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = roundedBackground(COLOR_PRIMARY, 14f)
    }

    private fun dangerButton(text: String) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(COLOR_RECORDING)
        isAllCaps = false
        background = outlinedBackground(Color.WHITE, COLOR_DANGER_BORDER, 14f)
    }

    private fun label(text: String, size: Float, bold: Boolean, color: Int = COLOR_TEXT) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun outlinedBackground(fillColor: Int, strokeColor: Int, radiusDp: Float) =
        roundedBackground(fillColor, radiusDp).apply {
            setStroke(dp(1), strokeColor)
        }

    private fun verticalCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = outlinedBackground(Color.WHITE, COLOR_BORDER, 18f)
        elevation = dp(1).toFloat()
    }

    private fun horizontalCard() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = outlinedBackground(Color.WHITE, COLOR_BORDER, 16f)
        elevation = dp(1).toFloat()
    }

    private fun chip(text: String, textColor: Int, backgroundColor: Int) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(textColor)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = roundedBackground(backgroundColor, 20f)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(COLOR_BORDER)
        minimumHeight = dp(1)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedNav() = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        leftMargin = dp(3)
        rightMargin = dp(3)
    }

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun topMargin(value: Int) = matchWrap().apply { topMargin = dp(value) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_RECORD_AUDIO = 200
        private const val PREFERENCES = "settings"
        private const val KEY_SOURCE = "download_source"
        private val COLOR_BACKGROUND = 0xFFF6F8FC.toInt()
        private val COLOR_PRIMARY = 0xFF3563E9.toInt()
        private val COLOR_PRIMARY_SOFT = 0xFFEEF4FF.toInt()
        private val COLOR_TEXT = 0xFF101828.toInt()
        private val COLOR_MUTED = 0xFF667085.toInt()
        private val COLOR_BORDER = 0xFFE4E7EC.toInt()
        private val COLOR_SUCCESS = 0xFF12B76A.toInt()
        private val COLOR_SUCCESS_SOFT = 0xFFECFDF3.toInt()
        private val COLOR_WARNING = 0xFFF79009.toInt()
        private val COLOR_RECORDING = 0xFFF04438.toInt()
        private val COLOR_DANGER_BORDER = 0xFFFECACA.toInt()
    }

    private enum class Page { TRANSCRIBE, MODELS, HISTORY }
}
