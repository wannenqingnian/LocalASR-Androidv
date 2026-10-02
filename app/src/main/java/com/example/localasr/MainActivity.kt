package com.example.localasr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.localasr.asr.AsrForegroundService
import com.example.localasr.history.HistoryDatabase
import com.example.localasr.history.TranscriptSession
import com.example.localasr.model.DownloadProgress
import com.example.localasr.model.DownloadResult
import com.example.localasr.model.DownloadSource
import com.example.localasr.model.DownloadableModel
import com.example.localasr.model.ModelCatalog
import com.example.localasr.model.ModelManager
import com.example.localasr.model.RecognitionMode
import com.example.localasr.model.SpeechModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView
    private lateinit var drawerLayer: FrameLayout
    private lateinit var modelManager: ModelManager
    private lateinit var historyDatabase: HistoryDatabase

    private var transcriptView: EditText? = null
    private var recordButton: Button? = null
    private var recordingStatus: TextView? = null
    private var recordingDetail: TextView? = null
    private var recordingStatusDot: TextView? = null
    private var noiseSuppressionSwitch: Switch? = null
    private var lastTranscript = ""
    private var pendingExportText = ""
    private var pendingRecordPermission = false
    private var pendingImportModelId: String? = null

    private var downloading = false
    private var downloadingModel: DownloadableModel? = null
    private var importingModel = false
    private var selectedSource = DownloadSource.DOMESTIC
    private var drawerOpen = false
    private val modelProgressViews = mutableMapOf<String, ProgressBar>()
    private val modelStatusViews = mutableMapOf<String, TextView>()
    private val modelActionButtons = mutableMapOf<String, Button>()
    private val modelImportButtons = mutableMapOf<String, TextView>()
    private val modelDeleteButtons = mutableMapOf<String, Button>()
    private var stateReceiverRegistered = false
    private var currentPage = Page.TRANSCRIBE
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

    override fun onResume() {
        super.onResume()
        if (currentPage == Page.PERMISSIONS) showPermissions()
    }

    override fun onDestroy() {
        historyDatabase.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) {
            hideDrawer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (currentPage == Page.PERMISSIONS) showPermissions()
            return
        }
        if (requestCode != REQUEST_RECORD_AUDIO || !pendingRecordPermission) return
        pendingRecordPermission = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            showMessage("需要麦克风权限才能进行语音识别")
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_IMPORT_MODEL) pendingImportModelId = null
            return
        }
        when (requestCode) {
            REQUEST_EXPORT_TEXT -> {
                val target = data?.data ?: return
                runCatching {
                    contentResolver.openOutputStream(target)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                        checkNotNull(writer) { "无法创建导出文件" }
                        writer.write(pendingExportText)
                    }
                }.onSuccess {
                    showMessage("转写文本已导出")
                }.onFailure {
                    showMessage("导出失败：${it.message ?: "无法写入文件"}")
                }
            }
            REQUEST_IMPORT_MODEL -> handleModelImportResult(data)
        }
    }

    private fun buildShell(): View {
        val root = FrameLayout(this).apply {
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
        val app = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(18), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            contentDescription = "打开导航菜单"
            isClickable = true
            isFocusable = true
            background = roundedBackground(COLOR_PRIMARY_SOFT, 14f)
            setOnClickListener { showDrawer() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(13) })
        val titleArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleArea.addView(label("LOCAL ASR", 10f, true, COLOR_PRIMARY).apply {
            letterSpacing = 0.16f
        }, matchWrap())
        pageTitle = label("", 23f, true)
        titleArea.addView(pageTitle, topMargin(2))
        pageSubtitle = label("", 12f, false, COLOR_MUTED)
        titleArea.addView(pageSubtitle, topMargin(2))
        header.addView(titleArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        app.addView(header, matchWrap())

        content = FrameLayout(this)
        app.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(app, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        drawerLayer = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        drawerLayer.addView(View(this).apply {
            setBackgroundColor(COLOR_SCRIM)
            setOnClickListener { hideDrawer() }
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(16).toFloat()
            isClickable = true
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, dp(14))
        }
        val drawerTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerTitle.addView(label("本地语音转文字", 19f, true), matchWrap())
        drawerTitle.addView(label("离线识别 · 隐私优先", 12f, false, COLOR_MUTED), topMargin(4))
        drawerHeader.addView(drawerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        drawerHeader.addView(TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            contentDescription = "关闭导航菜单"
            isClickable = true
            setOnClickListener { hideDrawer() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        drawer.addView(drawerHeader, matchWrap())
        drawer.addView(
            divider(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(12)
            },
        )
        drawer.addView(navItem(Page.TRANSCRIBE, "实时转写") { showTranscribe() }, matchWrap().apply {
            bottomMargin = dp(6)
        })
        drawer.addView(navItem(Page.MODELS, "模型管理") { showModels() }, matchWrap().apply {
            bottomMargin = dp(6)
        })
        drawer.addView(navItem(Page.PERMISSIONS, "后台与权限") { showPermissions() }, matchWrap().apply {
            bottomMargin = dp(6)
        })
        drawer.addView(navItem(Page.HISTORY, "历史记录") { showHistory() }, matchWrap())
        drawer.addView(View(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        drawer.addView(label("模型按需下载 · 音频仅在本机处理", 12f, false, COLOR_MUTED).apply {
            setPadding(dp(6), dp(12), dp(6), dp(4))
        }, matchWrap())
        drawer.addView(label("版本 0.6.1", 11f, false, COLOR_MUTED).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }, matchWrap())
        drawerLayer.addView(drawer, FrameLayout.LayoutParams(
            dp(300),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START,
        ))
        root.addView(drawerLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return root
    }

    private fun showDrawer() {
        drawerOpen = true
        drawerLayer.visibility = View.VISIBLE
    }

    private fun hideDrawer() {
        drawerOpen = false
        drawerLayer.visibility = View.GONE
    }

    private fun showTranscribe() {
        val selectedModel = modelManager.selectedModel
        val isOffline = selectedModel.mode == RecognitionMode.OFFLINE
        setPage(
            Page.TRANSCRIBE,
            if (isOffline) "川渝方言分段转写" else "实时语音转文字",
            "音频只在本机处理",
        )
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
        recordingDetail = label(
            if (isOffline) "停顿后显示整段文字 · 16 kHz 麦克风" else "实时显示文字 · 16 kHz 麦克风",
            12f,
            false,
            COLOR_MUTED,
        )
        statusTexts.addView(recordingStatus, matchWrap())
        statusTexts.addView(recordingDetail, topMargin(2))
        statusCard.addView(statusTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(statusCard, matchWrap().apply { bottomMargin = dp(12) })

        val modelSwitchCard = horizontalCard().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showModelPicker() }
        }
        val modelSwitchTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modelSwitchTexts.addView(label("识别模型 · 点击切换", 12f, false, COLOR_MUTED), matchWrap())
        modelSwitchTexts.addView(label(selectedModel.name, 15f, true), topMargin(2))
        modelSwitchCard.addView(
            modelSwitchTexts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val modelReady = modelManager.isInstalled(selectedModel)
        modelSwitchCard.addView(
            chip(
                if (modelReady) "已就绪" else modelInstallState(selectedModel),
                if (modelReady) COLOR_SUCCESS else COLOR_WARNING,
                if (modelReady) COLOR_SUCCESS_SOFT else COLOR_WARNING_SOFT,
            ),
            wrapWrap().apply { leftMargin = dp(8) },
        )
        page.addView(modelSwitchCard, matchWrap().apply { bottomMargin = dp(12) })

        val speakerReady = modelManager.isInstalled(ModelCatalog.speakerEmbedding)
        val speakerCard = horizontalCard().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showModels() }
        }
        val speakerTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speakerTexts.addView(label("说话人音色区分", 14f, true), matchWrap())
        speakerTexts.addView(
            label(
                if (speakerReady) "已开启 · 自动标记说话人1/2/3" else "未安装 · 点击前往模型管理",
                12f,
                false,
                COLOR_MUTED,
            ),
            topMargin(2),
        )
        speakerCard.addView(speakerTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speakerCard.addView(
            chip(
                if (speakerReady) "已开启" else "未开启",
                if (speakerReady) COLOR_SUCCESS else COLOR_WARNING,
                if (speakerReady) COLOR_SUCCESS_SOFT else COLOR_WARNING_SOFT,
            ),
            wrapWrap().apply { leftMargin = dp(8) },
        )
        page.addView(speakerCard, matchWrap().apply { bottomMargin = dp(12) })

        val noiseSuppressionAvailable = NoiseSuppressor.isAvailable()
        val noiseSuppressionEnabled = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_NOISE_SUPPRESSION, true) && noiseSuppressionAvailable
        val noiseSuppressionCard = horizontalCard().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }
        val noiseSuppressionTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noiseSuppressionTexts.addView(label("实时降噪", 14f, true), matchWrap())
        val noiseSuppressionDetail = label("", 12f, false, COLOR_MUTED)
        fun updateNoiseSuppressionDetail(enabled: Boolean) {
            noiseSuppressionDetail.text = when {
                !noiseSuppressionAvailable -> "当前设备不支持系统降噪"
                enabled -> "已开启 · 过滤稳定背景噪音"
                else -> "已关闭 · 使用原始麦克风音频"
            }
        }
        updateNoiseSuppressionDetail(noiseSuppressionEnabled)
        noiseSuppressionTexts.addView(noiseSuppressionDetail, topMargin(2))
        noiseSuppressionCard.addView(
            noiseSuppressionTexts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        noiseSuppressionSwitch = Switch(this).apply {
            isChecked = noiseSuppressionEnabled
            isEnabled = noiseSuppressionAvailable && !AsrForegroundService.isActive
            buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(KEY_NOISE_SUPPRESSION, enabled)
                    .apply()
                updateNoiseSuppressionDetail(enabled)
            }
        }
        noiseSuppressionCard.addView(noiseSuppressionSwitch, wrapWrap().apply { leftMargin = dp(8) })
        page.addView(noiseSuppressionCard, matchWrap().apply { bottomMargin = dp(12) })

        val transcriptCard = verticalCard().apply {
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val transcriptHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transcriptHeader.addView(
            label(if (isOffline) "分段文本" else "实时文本", 16f, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        transcriptHeader.addView(chip("仅本地处理", COLOR_PRIMARY, COLOR_PRIMARY_SOFT), wrapWrap())
        transcriptCard.addView(transcriptHeader, matchWrap())

        val initialTranscript = AsrForegroundService.currentTranscript.ifBlank { lastTranscript }
        if (initialTranscript.isNotBlank()) lastTranscript = initialTranscript
        transcriptView = EditText(this).apply {
            setText(initialTranscript)
            hint = if (isOffline) {
                "准备好后点击“开始转写”\n每次停顿后会显示一段文字"
            } else {
                "准备好后点击“开始转写”\n说话内容会实时显示在这里"
            }
            textSize = 17f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setTextIsSelectable(true)
            gravity = Gravity.TOP
            setPadding(0, dp(16), 0, 0)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            setHorizontallyScrolling(false)
            movementMethod = ScrollingMovementMethod()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!AsrForegroundService.isActive) lastTranscript = s?.toString().orEmpty()
                }
            })
        }
        transcriptCard.addView(
            transcriptView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(2)
            },
        )
        val transcriptActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transcriptActions.addView(textAction("改名") { renameCurrentSpeakers() }, weightedAction())
        transcriptActions.addView(textAction("复制") { copyTranscript() }, weightedAction().apply {
            leftMargin = dp(6)
        })
        transcriptActions.addView(textAction("整篇分享") { shareTranscript() }, weightedAction().apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        transcriptActions.addView(textAction("导出") { exportTranscript() }, weightedAction())
        transcriptCard.addView(transcriptActions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44),
        ).apply { topMargin = dp(12) })
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

    private fun showModelPicker() {
        if (downloading || AsrForegroundService.isActive) {
            showMessage("请先暂停下载或停止当前转写")
            return
        }
        val models = ModelCatalog.models
        val currentIndex = models.indexOfFirst { it.id == modelManager.selectedModel.id }
        val labels = models.map { model ->
            "${model.name} · ${modelInstallState(model)}\n${model.description}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("切换识别模型")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = models[which]
                dialog.dismiss()
                if (!modelManager.isInstalled(selected)) {
                    AlertDialog.Builder(this)
                        .setTitle("模型尚未下载")
                        .setMessage("请先在模型管理中下载“${selected.name}”，下载完成后即可在这里切换。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("去下载") { _, _ -> showModels() }
                        .show()
                    return@setSingleChoiceItems
                }
                if (selected.id == modelManager.selectedModel.id) return@setSingleChoiceItems
                modelManager.selectModel(selected)
                showMessage("已切换到“${selected.name}”")
                showTranscribe()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun modelInstallState(model: SpeechModel): String {
        if (modelManager.isInstalled(model)) return "已安装"
        val downloaded = modelManager.downloadedBytes(model)
        if (downloaded <= 0L) return "未安装"
        val percent = ((downloaded * 100L) / model.totalBytes).toInt().coerceIn(1, 99)
        return "已下载 $percent%"
    }

    private fun requestRecording() {
        if (!modelManager.isInstalled()) {
            val model = modelManager.selectedModel
            AlertDialog.Builder(this)
                .setTitle("需要先下载模型")
                .setMessage("当前选择“${model.name}”。APK 不内置语音模型，请先下载后再使用。")
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
        noiseSuppressionSwitch?.isEnabled = false
        recordingStatus?.text = "正在加载模型…"
        lastTranscript = ""
        transcriptView?.apply {
            setText("")
            setTextColor(COLOR_TEXT)
        }
        val intent = Intent(this, AsrForegroundService::class.java)
            .setAction(AsrForegroundService.ACTION_START)
            .putExtra(
                AsrForegroundService.EXTRA_NOISE_SUPPRESSION,
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getBoolean(KEY_NOISE_SUPPRESSION, true),
            )
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
        val selectedModel = modelManager.selectedModel
        val serviceTranscript = AsrForegroundService.currentTranscript
        if (
            serviceTranscript.isNotBlank() &&
            (AsrForegroundService.isActive || lastTranscript.isBlank())
        ) {
            lastTranscript = serviceTranscript
        }
        recordingStatus?.text = when {
            AsrForegroundService.currentStatus.isNotBlank() -> AsrForegroundService.currentStatus
            modelManager.isInstalled() -> "模型已就绪，内容仅在本机处理"
            else -> "尚未安装模型，请先进入模型管理"
        }
        recordingDetail?.text = when {
            AsrForegroundService.isActive -> "前台服务运行中 · 可锁屏或切换应用"
            modelManager.isInstalled() && selectedModel.mode == RecognitionMode.OFFLINE ->
                "${selectedModel.name} · 停顿后显示整段文字"
            modelManager.isInstalled() -> "${selectedModel.name} · 实时显示文字"
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
            if (text.toString() != lastTranscript && (AsrForegroundService.isActive || !hasFocus())) {
                setText(lastTranscript)
                setSelection(text.length)
            }
            isFocusable = !AsrForegroundService.isActive
            isFocusableInTouchMode = !AsrForegroundService.isActive
            isCursorVisible = !AsrForegroundService.isActive
        }
        recordButton?.apply {
            text = if (AsrForegroundService.isActive) "停止并保存" else "开始转写"
            isEnabled = !AsrForegroundService.currentStatus.startsWith("正在整理")
            background = roundedBackground(
                if (AsrForegroundService.isActive) COLOR_RECORDING else COLOR_PRIMARY,
                16f,
            )
        }
        noiseSuppressionSwitch?.isEnabled =
            NoiseSuppressor.isAvailable() && !AsrForegroundService.isActive
        if (currentPage == Page.MODELS) refreshModelUi()
    }

    private fun transcriptText(): String = transcriptView?.text?.toString()?.trim().orEmpty()

    private fun renameCurrentSpeakers() {
        if (AsrForegroundService.isActive) {
            showMessage("请先停止并保存，再修改说话人姓名")
            return
        }
        showSpeakerRenameDialog(transcriptText()) { updatedText ->
            lastTranscript = updatedText
            transcriptView?.apply {
                setText(updatedText)
                setSelection(text.length)
            }
            AsrForegroundService.lastSavedSessionId.takeIf { it != 0L }?.let { sessionId ->
                historyDatabase.updateText(sessionId, updatedText)
                AsrForegroundService.updateLastSavedTranscript(sessionId, updatedText)
            }
            showMessage("说话人姓名已统一修改")
        }
    }

    private fun showSpeakerRenameDialog(text: String, onRenamed: (String) -> Unit) {
        val labels = text.lineSequence()
            .flatMap { line ->
                val labelEnd = line.indexOf('：').takeIf { it >= 0 } ?: return@flatMap emptySequence()
                SPEAKER_LABEL.findAll(line.substring(0, labelEnd)).map { it.value }
            }
            .distinct()
            .toList()
        if (labels.isEmpty()) {
            showMessage("当前文字中没有可改名的说话人标签")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择要改名的说话人")
            .setItems(labels.toTypedArray()) { _, which ->
                val oldName = labels[which]
                val input = EditText(this).apply {
                    hint = "输入真实姓名"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    setSingleLine(true)
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                }
                AlertDialog.Builder(this)
                    .setTitle("将“$oldName”统一改名")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存") { _, _ ->
                        val newName = input.text.toString().trim()
                        if (newName.isBlank()) {
                            showMessage("姓名不能为空")
                            return@setPositiveButton
                        }
                        val exactLabel = Regex("${Regex.escape(oldName)}(?!\\d)")
                        val updatedText = text.lineSequence().joinToString("\n") { line ->
                            val labelEnd = line.indexOf('：')
                            if (labelEnd < 0) return@joinToString line
                            exactLabel.replace(line.substring(0, labelEnd), newName) + line.substring(labelEnd)
                        }
                        onRenamed(updatedText)
                    }
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyTranscript() {
        val text = transcriptText()
        if (text.isBlank()) {
            showMessage("当前没有可复制的文字")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("语音转写", text))
        showMessage("已复制整篇文字")
    }

    private fun shareTranscript() {
        val text = transcriptText()
        if (text.isBlank()) {
            showMessage("当前没有可分享的文字")
            return
        }
        shareText(text, "分享整篇转写")
    }

    private fun shareText(text: String, chooserTitle: String) {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "语音转写")
                putExtra(Intent.EXTRA_TEXT, text)
            },
            chooserTitle,
        ))
    }

    private fun exportTranscript() {
        val text = transcriptText()
        if (text.isBlank()) {
            showMessage("当前没有可导出的文字")
            return
        }
        exportText(text, System.currentTimeMillis())
    }

    private fun exportText(text: String, time: Long) {
        pendingExportText = text
        val fileName = "LocalASR-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(time))}.txt"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, fileName)
            },
            REQUEST_EXPORT_TEXT,
        )
    }

    private fun showModels() {
        setPage(Page.MODELS, "模型管理", "下载、导入或删除本地模型")
        modelProgressViews.clear()
        modelStatusViews.clear()
        modelActionButtons.clear()
        modelImportButtons.clear()
        modelDeleteButtons.clear()

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
            label("模型仅保存在本机；当前识别模型请在主界面切换", 13f, false, COLOR_PRIMARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        body.addView(privacyCard, matchWrap().apply { bottomMargin = dp(12) })

        body.addView(downloadSourceCard(), matchWrap().apply { bottomMargin = dp(16) })
        val installedCount = ModelCatalog.downloadableModels.count { modelManager.isInstalled(it) }
        val modelsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modelsHeader.addView(
            label("模型文件", 17f, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        modelsHeader.addView(
            chip("已安装 $installedCount/${ModelCatalog.downloadableModels.size}", COLOR_PRIMARY, COLOR_PRIMARY_SOFT),
            wrapWrap(),
        )
        body.addView(modelsHeader, matchWrap().apply { bottomMargin = dp(10) })
        ModelCatalog.models.forEach { model ->
            body.addView(modelManagementCard(model), matchWrap().apply { bottomMargin = dp(12) })
        }
        body.addView(label("辅助模型", 17f, true), topMargin(6).apply { bottomMargin = dp(10) })
        body.addView(
            modelManagementCard(ModelCatalog.speakerEmbedding),
            matchWrap().apply { bottomMargin = dp(12) },
        )
        body.addView(
            label(
                "下载支持暂停和断点续传；本地导入需一次选择该模型所需的全部文件。两种方式都会执行大小与 SHA-256 校验。",
                13f,
                false,
                COLOR_MUTED,
            ).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            topMargin(4),
        )

        replaceContent(ScrollView(this).apply { addView(body) })
        refreshModelUi()
    }

    private fun downloadSourceCard(): View = verticalCard().apply {
        setPadding(dp(18), dp(15), dp(18), dp(12))
        addView(label("下载线路", 15f, true), matchWrap())
        addView(label("线路只影响在线下载，本地导入不受影响。", 12f, false, COLOR_MUTED), topMargin(3))
        val sources = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val domestic = RadioButton(this@MainActivity).apply {
            id = View.generateViewId()
            text = "国内镜像"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            isChecked = selectedSource == DownloadSource.DOMESTIC
        }
        val official = RadioButton(this@MainActivity).apply {
            id = View.generateViewId()
            text = "官方原站"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            isChecked = selectedSource == DownloadSource.OFFICIAL
        }
        sources.addView(domestic, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sources.addView(official, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sources.setOnCheckedChangeListener { _, checkedId ->
            selectedSource = if (checkedId == official.id) DownloadSource.OFFICIAL else DownloadSource.DOMESTIC
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, selectedSource.name)
                .apply()
        }
        addView(sources, matchWrap())
    }

    private fun modelManagementCard(model: DownloadableModel): View = verticalCard().apply {
        setPadding(dp(18), dp(17), dp(18), dp(18))
        val titleRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titleTexts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        titleTexts.addView(label(model.name, 17f, true), matchWrap())
        titleTexts.addView(label(model.description, 13f, false, COLOR_MUTED), topMargin(4))
        titleRow.addView(titleTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(
            chip(
                when {
                    model !is SpeechModel -> "音色区分"
                    model.mode == RecognitionMode.OFFLINE -> "分段识别"
                    else -> "实时识别"
                },
                COLOR_PRIMARY,
                COLOR_PRIMARY_SOFT,
            ),
            wrapWrap().apply { leftMargin = dp(8) },
        )
        addView(titleRow, matchWrap())

        val progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_BORDER)
        }
        modelProgressViews[model.id] = progress
        addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
            topMargin = dp(16)
        })
        val status = label("", 13f, false, COLOR_MUTED)
        modelStatusViews[model.id] = status
        addView(status, topMargin(8))

        addView(label("模型地址", 13f, true), topMargin(15))
        val domesticUrl = modelRepositoryUrl(DownloadSource.DOMESTIC, model)
        val officialUrl = modelRepositoryUrl(DownloadSource.OFFICIAL, model)
        addView(selectableAddress("国内：$domesticUrl"), topMargin(5))
        addView(selectableAddress("官方：$officialUrl"), topMargin(4))
        val copyActions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        copyActions.addView(textAction("复制国内地址") { copyModelUrl(domesticUrl) }, weightedAction())
        copyActions.addView(textAction("复制官方地址") { copyModelUrl(officialUrl) }, weightedAction().apply {
            leftMargin = dp(8)
        })
        addView(copyActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(8)
        })
        addView(
            label(
                "本地文件：${model.files.joinToString("、") { it.remoteName }}",
                12f,
                false,
                COLOR_MUTED,
            ),
            topMargin(10),
        )

        val downloadButton = primaryButton("下载模型").apply {
            setOnClickListener {
                if (downloading && downloadingModel?.id == model.id) pauseDownload() else startDownload(model)
            }
        }
        modelActionButtons[model.id] = downloadButton
        addView(downloadButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(15)
        })
        val importButton = textAction("从本地导入模型文件") { requestModelImport(model) }
        modelImportButtons[model.id] = importButton
        addView(importButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(8)
        })
        val deleteButton = dangerButton("删除本地模型").apply {
            setOnClickListener { confirmDeleteModel(model) }
        }
        modelDeleteButtons[model.id] = deleteButton
        addView(deleteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(8)
        })
    }

    private fun selectableAddress(text: String) = label(text, 11f, false, COLOR_MUTED).apply {
        setTextIsSelectable(true)
    }

    private fun modelRepositoryUrl(source: DownloadSource, model: DownloadableModel): String =
        model.repositoryUrl(source)

    private fun copyModelUrl(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("模型地址", url))
        showMessage("模型地址已复制")
    }

    private fun startDownload(model: DownloadableModel) {
        if (downloading || AsrForegroundService.isActive) {
            showMessage("请先暂停当前任务或停止转写")
            return
        }
        downloading = true
        downloadingModel = model
        importingModel = false
        refreshModelUi()
        modelManager.download(
            model = model,
            source = selectedSource,
            onProgress = { progress -> runOnUiThread { renderDownloadProgress(progress) } },
            onResult = { result ->
                runOnUiThread {
                    downloading = false
                    downloadingModel = null
                    when (result) {
                        DownloadResult.Completed -> showMessage("${model.name}下载并校验完成")
                        DownloadResult.Paused -> showMessage("已暂停，可随时继续")
                        is DownloadResult.Failed -> showMessage(result.message)
                    }
                    refreshModelUi()
                }
            },
        )
    }

    private fun requestModelImport(model: DownloadableModel) {
        if (downloading || AsrForegroundService.isActive) {
            showMessage("请先暂停当前任务或停止转写")
            return
        }
        pendingImportModelId = model.id
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQUEST_IMPORT_MODEL,
        )
    }

    private fun handleModelImportResult(data: Intent?) {
        val modelId = pendingImportModelId ?: return
        val model = ModelCatalog.findDownloadable(modelId) ?: return
        pendingImportModelId = null
        val uris = buildList {
            data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
            }
            data?.data?.let { if (it !in this) add(it) }
        }
        if (uris.isEmpty()) {
            showMessage("没有选择模型文件")
            return
        }
        val filesByName = uris.associateBy { documentDisplayName(it) }
        downloading = true
        downloadingModel = model
        importingModel = true
        refreshModelUi()
        modelManager.importModel(
            model = model,
            filesByName = filesByName,
            onProgress = { progress -> runOnUiThread { renderDownloadProgress(progress) } },
            onResult = { result ->
                runOnUiThread {
                    downloading = false
                    downloadingModel = null
                    importingModel = false
                    when (result) {
                        DownloadResult.Completed -> showMessage("${model.name}导入并校验完成")
                        DownloadResult.Paused -> showMessage("已暂停导入")
                        is DownloadResult.Failed -> showMessage("导入失败：${result.message}")
                    }
                    refreshModelUi()
                }
            },
        )
    }

    private fun documentDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun pauseDownload() {
        val modelId = downloadingModel?.id ?: return
        modelActionButtons[modelId]?.isEnabled = false
        modelStatusViews[modelId]?.text = "正在暂停…"
        modelManager.pause()
    }

    private fun renderDownloadProgress(progress: DownloadProgress) {
        val modelId = downloadingModel?.id ?: return
        val ratio = if (progress.totalBytes == 0L) 0 else {
            ((progress.downloadedBytes * 1000L) / progress.totalBytes).toInt().coerceIn(0, 1000)
        }
        modelProgressViews[modelId]?.progress = ratio
        modelStatusViews[modelId]?.text =
            "${progress.message}\n${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
    }

    private fun refreshModelUi() {
        ModelCatalog.downloadableModels.forEach { model ->
            val installed = modelManager.isInstalled(model)
            val downloaded = modelManager.downloadedBytes(model)
            val isActive = downloading && downloadingModel?.id == model.id
            modelProgressViews[model.id]?.progress =
                ((downloaded * 1000L) / model.totalBytes).toInt().coerceIn(0, 1000)
            modelStatusViews[model.id]?.text = when {
                isActive && importingModel -> "正在准备导入…"
                isActive -> "正在连接下载线路…"
                installed -> "已安装并校验 · ${formatBytes(model.totalBytes)}"
                downloaded > 0L -> "可继续下载 · ${formatBytes(downloaded)} / ${formatBytes(model.totalBytes)}"
                else -> "未安装 · 需要 ${formatBytes(model.totalBytes)}"
            }
            modelActionButtons[model.id]?.apply {
                text = when {
                    isActive && importingModel -> "暂停导入"
                    isActive -> "暂停下载"
                    installed -> "重新校验模型"
                    downloaded > 0L -> "继续下载"
                    else -> "下载模型"
                }
                isEnabled = !AsrForegroundService.isActive && (!downloading || isActive)
            }
            modelImportButtons[model.id]?.isEnabled = !downloading && !AsrForegroundService.isActive
            modelDeleteButtons[model.id]?.isEnabled =
                !downloading && !AsrForegroundService.isActive && downloaded > 0L
        }
    }

    private fun confirmDeleteModel(model: DownloadableModel) {
        if (downloading) return
        if (AsrForegroundService.isActive) {
            showMessage("请先停止当前转写，再删除模型")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除本地模型？")
            .setMessage("将删除“${model.name}”。历史文字仍会保留，再次使用前需要重新下载或导入。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                modelManager.deleteModel(model)
                showModels()
            }
            .show()
    }

    private fun showPermissions() {
        setPage(Page.PERMISSIONS, "后台与权限", "按系统能力逐项设置，返回后自动刷新")
        val batteryIgnored = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }

        val info = verticalCard().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(COLOR_PRIMARY_SOFT, 16f)
        }
        info.addView(label("长时间录音建议完成以下设置", 15f, true, COLOR_PRIMARY), matchWrap())
        info.addView(
            label("不同品牌的系统名称和入口可能不同；App 只能打开设置页，最终开关需要你在系统界面确认。", 12f, false, COLOR_PRIMARY).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            topMargin(5),
        )
        body.addView(info, matchWrap().apply { bottomMargin = dp(12) })

        fun addPermissionCard(
            title: String,
            description: String,
            status: String,
            statusOk: Boolean,
            actionText: String,
            action: () -> Unit,
        ) {
            val card = verticalCard().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(label(title, 16f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(
                chip(
                    status,
                    if (statusOk) COLOR_SUCCESS else COLOR_WARNING,
                    if (statusOk) COLOR_SUCCESS_SOFT else COLOR_WARNING_SOFT,
                ),
                wrapWrap().apply { leftMargin = dp(8) },
            )
            card.addView(heading, matchWrap())
            card.addView(
                label(description, 13f, false, COLOR_MUTED).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                topMargin(7),
            )
            card.addView(primaryButton(actionText).apply {
                textSize = 14f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(13)
            })
            body.addView(card, matchWrap().apply { bottomMargin = dp(12) })
        }

        addPermissionCard(
            title = "忽略电池优化",
            description = "允许长时间录音时继续在后台运行，减少系统因省电而中断转写。",
            status = if (batteryIgnored) "已允许" else "未允许",
            statusOk = batteryIgnored,
            actionText = if (batteryIgnored) "查看电池优化设置" else "申请忽略电池优化",
            action = { requestBatteryOptimizationExemption(batteryIgnored) },
        )
        addPermissionCard(
            title = "自启动权限",
            description = "请在厂商管理页面中允许本应用自启动。Android 没有统一接口，因此状态需要在系统页面确认。",
            status = "需确认",
            statusOk = false,
            actionText = "打开自启动设置",
            action = { openAutoStartSettings() },
        )
        addPermissionCard(
            title = "省电策略",
            description = "请在应用耗电管理中选择“不限制”或“无限制”，并允许后台活动。",
            status = if (batteryIgnored) "基础设置完成" else "待设置",
            statusOk = batteryIgnored,
            actionText = "打开应用耗电管理",
            action = { openBatteryPolicySettings() },
        )
        addPermissionCard(
            title = "通知栏保活",
            description = "录音期间会显示不可滑除的前台服务通知；通知权限关闭后，系统可能隐藏运行提示。",
            status = if (notificationsAllowed) "已允许" else "未允许",
            statusOk = notificationsAllowed,
            actionText = if (notificationsAllowed) "管理通知设置" else "允许通知权限",
            action = { requestOrOpenNotificationSettings(notificationsAllowed) },
        )

        replaceContent(ScrollView(this).apply { addView(body) })
    }

    private fun requestBatteryOptimizationExemption(alreadyIgnored: Boolean) {
        val intent = if (alreadyIgnored) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        }
        launchSettings(intent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openAutoStartSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val candidates = when {
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> emptyList()
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                settingsComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                settingsComponent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                settingsComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            manufacturer.contains("meizu") -> listOf(
                settingsComponent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            )
            else -> emptyList()
        }
        launchFirstAvailable(candidates, applicationDetailsIntent())
    }

    private fun openBatteryPolicySettings() {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val candidates = mutableListOf(
            Intent(
                "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",
                Uri.parse("package:$packageName"),
            ),
        )
        if (
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")
        ) {
            candidates += listOf(
                settingsComponent(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                ).putExtra("pkgName", packageName),
                settingsComponent(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                ).putExtra("pkgName", packageName),
            )
        }
        launchFirstAvailable(candidates, applicationDetailsIntent())
    }

    private fun requestOrOpenNotificationSettings(alreadyAllowed: Boolean) {
        if (!alreadyAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            return
        }
        launchSettings(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            applicationDetailsIntent(),
        )
    }

    private fun settingsComponent(packageName: String, className: String): Intent {
        return Intent().setComponent(ComponentName(packageName, className))
    }

    private fun applicationDetailsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }

    private fun launchFirstAvailable(candidates: List<Intent>, fallback: Intent) {
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        showMessage("系统未开放直达入口，请在应用设置中手动确认")
        launchSettings(fallback, Intent(Settings.ACTION_SETTINGS))
    }

    private fun launchSettings(intent: Intent, fallback: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(fallback) }
                    .onFailure { showMessage("当前系统没有可用的设置入口") }
            }
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
            .setNeutralButton("更多") { _, _ -> textView.post { showHistoryActions(session) } }
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("语音转写", session.text))
                showMessage("已复制")
            }
            .show()
    }

    private fun showHistoryActions(session: TranscriptSession) {
        AlertDialog.Builder(this)
            .setTitle("记录操作")
            .setItems(arrayOf("分享记录", "导出 TXT", "说话人改名", "删除记录")) { _, which ->
                when (which) {
                    0 -> shareText(session.text, "分享转写记录")
                    1 -> exportText(session.text, session.startedAt)
                    2 -> {
                        showSpeakerRenameDialog(session.text) { updatedText ->
                            historyDatabase.updateText(session.id, updatedText)
                            if (AsrForegroundService.lastSavedSessionId == session.id) {
                                lastTranscript = updatedText
                                AsrForegroundService.updateLastSavedTranscript(session.id, updatedText)
                            }
                            showMessage("说话人姓名已统一修改")
                            showHistoryDetails(session.copy(text = updatedText))
                        }
                    }
                    3 -> confirmDeleteHistory(session)
                }
            }
            .setNegativeButton("取消", null)
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
        bytes >= 1_000_000L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun setPage(page: Page, title: String, subtitle: String) {
        currentPage = page
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
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minHeight = dp(52)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener {
            action()
            hideDrawer()
        }
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

    private fun textAction(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(COLOR_PRIMARY)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = outlinedBackground(Color.WHITE, COLOR_BORDER, 12f)
        setOnClickListener { action() }
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

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedAction() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    private fun topMargin(value: Int) = matchWrap().apply { topMargin = dp(value) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_RECORD_AUDIO = 200
        private const val REQUEST_EXPORT_TEXT = 201
        private const val REQUEST_NOTIFICATION_PERMISSION = 202
        private const val REQUEST_IMPORT_MODEL = 203
        private const val PREFERENCES = "settings"
        private const val KEY_SOURCE = "download_source"
        private const val KEY_NOISE_SUPPRESSION = "noise_suppression"
        private val SPEAKER_LABEL = Regex("说话人\\d+")
        private val COLOR_BACKGROUND = 0xFFF6F8FC.toInt()
        private val COLOR_PRIMARY = 0xFF3563E9.toInt()
        private val COLOR_PRIMARY_SOFT = 0xFFEEF4FF.toInt()
        private val COLOR_TEXT = 0xFF101828.toInt()
        private val COLOR_MUTED = 0xFF667085.toInt()
        private val COLOR_BORDER = 0xFFE4E7EC.toInt()
        private val COLOR_SUCCESS = 0xFF12B76A.toInt()
        private val COLOR_SUCCESS_SOFT = 0xFFECFDF3.toInt()
        private val COLOR_WARNING = 0xFFF79009.toInt()
        private val COLOR_WARNING_SOFT = 0xFFFFF7E8.toInt()
        private val COLOR_RECORDING = 0xFFF04438.toInt()
        private val COLOR_DANGER_BORDER = 0xFFFECACA.toInt()
        private val COLOR_SCRIM = 0x660F172A
    }

    private enum class Page { TRANSCRIBE, MODELS, PERMISSIONS, HISTORY }
}
