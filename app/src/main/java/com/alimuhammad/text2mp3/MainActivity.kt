package com.alimuhammad.text2mp3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alimuhammad.text2mp3.audio.AudioDownloads
import com.alimuhammad.text2mp3.audio.AudioFormatType
import com.alimuhammad.text2mp3.audio.AudioSink
import com.alimuhammad.text2mp3.audio.TextToAudio
import com.alimuhammad.text2mp3.audio.WavFile
import com.alimuhammad.text2mp3.databinding.ActivityMainBinding
import com.alimuhammad.text2mp3.reader.ReaderService
import com.alimuhammad.text2mp3.reader.ReaderState
import com.alimuhammad.text2mp3.text.DocumentReader
import com.alimuhammad.text2mp3.text.OcrReader
import com.alimuhammad.text2mp3.text.PronunciationDict
import com.alimuhammad.text2mp3.text.WebExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val player = WavPlayer()
    private var lastWav: String? = null
    private var busy = false
    private var initialised = false

    private var pendingExportFormat: AudioFormatType = AudioFormatType.MP3

    private lateinit var openDocLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var ocrLauncher: ActivityResultLauncher<String>
    private lateinit var createAudioLauncher: ActivityResultLauncher<String>
    private lateinit var importVoiceLauncher: ActivityResultLauncher<Uri?>
    private lateinit var notifPermLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvHeader.text = runCatching {
            "Text2MP3 v${packageManager.getPackageInfo(packageName, 0).versionName} · by Ali Muhammad"
        }.getOrDefault(getString(R.string.header_title))

        openDocLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadDocument(uri)
        }
        ocrLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) runOcr(uri)
        }
        createAudioLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { outUri ->
            if (outUri != null) exportToUri(outUri, pendingExportFormat)
        }
        importVoiceLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) importVoice(treeUri)
        }
        notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {  }

        wireListeners()
        observeReader()
        bootstrap()
        maybeRequestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        if (initialised) { refreshVoiceSpinner(); syncLog() }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun bootstrap() {
        setStatus("Initialising…", R.color.fg_dim)
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Piper.init(applicationContext) }
                initialised = true
                b.cbRetain.isChecked = Piper.config.retainAfterPlay
                b.sliderRate.value = Piper.config.speakingRate.coerceIn(0.2f, 3.0f)
                b.sliderPitch.value = Piper.config.pitch.coerceIn(0.5f, 2.0f)
                b.sliderVolume.value = Piper.config.volume.coerceIn(0.0f, 1.5f)
                updateRateLabel(b.sliderRate.value)
                updatePitchLabel(b.sliderPitch.value)
                updateVolumeLabel(b.sliderVolume.value)
                b.etText.textSize = Piper.config.editorTextSizeSp

                if (Piper.engine.installedVoices.isEmpty()) downloadDefaultVoice()
                else {
                    refreshVoiceSpinner()
                    setStatus("${Piper.engine.installedVoices.size} voice(s) ready", R.color.accent)
                }
            } catch (e: Exception) {
                setStatus("Init failed: ${e.message}", R.color.err)
            } finally {
                syncLog(); setBusy(false)
                handleIncomingIntent(intent)
            }
        }
    }

    private suspend fun downloadDefaultVoice() {
        val stem = VoiceCatalog.DEFAULT_VOICE
        setStatus("Downloading default voice $stem …", R.color.fg_dim)
        b.progress.isIndeterminate = false
        try {
            withContext(Dispatchers.IO) {
                Piper.repository.download(stem) { done, total, phase ->
                    runOnUiThread {
                        if (total > 0) {
                            b.progress.isIndeterminate = false
                            b.progress.progress = ((done * 100) / total).toInt()
                            setStatus("$phase $stem: ${done / 1024 / 1024}/${total / 1024 / 1024} MB", R.color.fg_dim)
                        } else {
                            b.progress.isIndeterminate = true
                            setStatus("$phase $stem…", R.color.fg_dim)
                        }
                    }
                }
                Piper.engine.reloadVoices()
            }
            Piper.config.defaultVoice = stem
            refreshVoiceSpinner()
            setStatus("Default voice ready", R.color.accent)
        } catch (e: Exception) {
            setStatus("Voice download failed: ${e.message}. Open “Manage voices”.", R.color.err)
        } finally {
            b.progress.isIndeterminate = true
        }
    }

    private fun wireListeners() {
        b.btnSpeak.setOnClickListener { onSpeak() }
        b.btnPlay.setOnClickListener {
            val w = lastWav
            if (w != null) player.play(w, deleteAfter = false) { msg -> appendLog("⚠ $msg") }
            else setStatus("No WAV to play.", R.color.warn)
        }
        b.btnClearLog.setOnClickListener {
            synchronized(Piper.logBuffer) { Piper.logBuffer.setLength(0) }
            b.tvLog.text = ""
        }
        b.btnClearCache.setOnClickListener {
            if (!initialised) return@setOnClickListener
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { Piper.engine.clearCache() }
                lastWav = null; b.btnPlay.isEnabled = false
                syncLog(); setStatus("Cache cleared.", R.color.warn)
            }
        }
        b.btnVoices.setOnClickListener {
            if (initialised) startActivity(Intent(this, VoicesActivity::class.java))
        }
        b.btnPregen.setOnClickListener { showPreGenerateDialog() }
        b.btnFileMp3.setOnClickListener { onExport() }
        b.btnAbout.setOnClickListener { showAbout() }
        b.btnDonate.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/AliMuhammadK62")))
            }
        }

        b.btnReadAloud.setOnClickListener { onReadAloud() }
        b.btnPlayPause.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_TOGGLE) }
        b.btnNext.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_NEXT) }
        b.btnPrev.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_PREV) }
        b.btnStopRead.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_STOP) }
        b.btnSleep.setOnClickListener { showSleepTimerDialog() }

        b.btnOpenDoc.setOnClickListener {
            openDocLauncher.launch(arrayOf(
                "text/plain", "text/html", "text/markdown", "text/*",
                "application/pdf", "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ))
        }
        b.btnReadUrl.setOnClickListener { showUrlDialog() }
        b.btnOcr.setOnClickListener { ocrLauncher.launch("image/*") }
        b.btnPronounce.setOnClickListener { showPronunciationDialog() }
        b.btnSettings.setOnClickListener { showSettingsDialog() }

        b.sliderRate.addOnChangeListener { _, v, _ -> updateRateLabel(v); if (initialised) Piper.config.speakingRate = v }
        b.sliderPitch.addOnChangeListener { _, v, _ -> updatePitchLabel(v); if (initialised) Piper.config.pitch = v }
        b.sliderVolume.addOnChangeListener { _, v, _ -> updateVolumeLabel(v); if (initialised) Piper.config.volume = v }
        b.cbRetain.setOnCheckedChangeListener { _, c -> if (initialised) Piper.config.retainAfterPlay = c }

        b.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!initialised) return
                val stem = b.spinnerVoice.getItemAtPosition(pos) as? String ?: return
                Piper.config.defaultVoice = stem
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun onSpeak() {
        if (!initialised || busy) return
        val text = b.etText.text.toString().trim()
        if (text.isEmpty()) { setStatus("Enter some text first.", R.color.warn); return }
        val stem = b.spinnerVoice.selectedItem as? String
            ?: run { setStatus("Download a voice first.", R.color.warn); return }

        Piper.config.speakingRate = b.sliderRate.value
        val retain = b.cbRetain.isChecked
        setBusy(true); setStatus("Synthesising…", R.color.fg_dim)
        lastWav = null; b.btnPlay.isEnabled = false

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val pitch = Piper.config.pitch
                val volume = Piper.config.volume
                val wav = withContext(Dispatchers.IO) {
                    if (pitch == 1.0f && volume == 1.0f) {

                        Piper.engine.getOrSynthesize(text, stem)
                    } else {

                        val pcm = Piper.engine.synthesizeFloat(text, stem, pitch = pitch)
                        val shorts = Piper.engine.floatToShort(pcm.samples, volume)
                        val tmp = File(cacheDir, "speak_${System.currentTimeMillis()}.wav")
                        WavFile.write(tmp, shorts, pcm.sampleRate)
                        tmp.absolutePath
                    }
                }
                val ms = System.currentTimeMillis() - start
                setStatus("Done in $ms ms", R.color.accent)
                player.play(wav, deleteAfter = !retain) { msg -> appendLog("⚠ $msg") }
                lastWav = if (retain) wav else null
                b.btnPlay.isEnabled = retain
            } catch (e: PiperException) {
                setStatus("Piper error: ${e.message}", R.color.err)
            } catch (e: Exception) {
                setStatus("Error: ${e.message}", R.color.err)
            } finally {
                syncLog(); setBusy(false)
            }
        }
    }

    private fun onReadAloud() {
        if (!initialised) return
        val text = b.etText.text.toString().trim()
        if (text.isEmpty()) { setStatus("Nothing to read.", R.color.warn); return }
        val stem = b.spinnerVoice.selectedItem as? String ?: Piper.config.defaultVoice
        val title = ReaderState.title.ifBlank { "Text2MP3 reader" }
        ReaderService.play(this, text, stem, title)
        setStatus("Reading aloud…", R.color.accent)
    }

    private fun observeReader() {
        lifecycleScope.launch {
            ReaderState.status.combine(ReaderState.index) { s, i -> s to i }.collect { (status, idx) ->
                when (status) {
                    ReaderState.Status.PREPARING -> b.tvReaderStatus.apply { visibility = View.VISIBLE; text = "Preparing…" }
                    ReaderState.Status.PLAYING -> {
                        b.tvReaderStatus.apply { visibility = View.VISIBLE; text = "Reading ${idx + 1}/${ReaderState.spans.size}" }
                        highlightSentence(idx)
                    }
                    ReaderState.Status.PAUSED -> b.tvReaderStatus.apply { visibility = View.VISIBLE; text = "Paused (${idx + 1}/${ReaderState.spans.size})" }
                    ReaderState.Status.DONE -> { b.tvReaderStatus.text = "Finished."; clearHighlight() }
                    ReaderState.Status.ERROR -> { b.tvReaderStatus.apply { visibility = View.VISIBLE; text = "Reader error: ${ReaderState.error ?: ""}" }; clearHighlight() }
                    ReaderState.Status.IDLE -> { b.tvReaderStatus.visibility = View.GONE; clearHighlight() }
                }
            }
        }
    }

    private fun highlightSentence(idx: Int) {
        val spans = ReaderState.spans
        if (idx !in spans.indices) return

        if (b.etText.text.toString() != ReaderState.sourceText) return
        val span = spans[idx]

        val ss = SpannableString(ReaderState.sourceText)
        val end = span.end.coerceAtMost(ss.length)
        val st = span.start.coerceIn(0, end)
        ss.setSpan(BackgroundColorSpan(Color.parseColor("#33508CFF")), st, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.etText.setText(ss, TextView.BufferType.SPANNABLE)
        b.etText.setSelection(st)
    }

    private fun clearHighlight() {
        val plain = b.etText.text.toString()
        if (plain == ReaderState.sourceText && plain.isNotEmpty()) b.etText.setText(plain)
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("Off", "5 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
        val mins = intArrayOf(0, 5, 15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(options) { _, which ->
                ReaderService.sleep(this, mins[which])
                setStatus(if (mins[which] == 0) "Sleep timer off." else "Sleeping in ${mins[which]} min.", R.color.fg_dim)
            }.show()
    }

    private fun loadDocument(uri: Uri) {
        setBusy(true); setStatus("Reading document…", R.color.fg_dim)
        lifecycleScope.launch {
            try {
                val name = DocumentReader.displayName(this@MainActivity, uri) ?: "document"
                val text = withContext(Dispatchers.IO) { DocumentReader.extract(this@MainActivity, uri) }
                b.etText.setText(text)
                ReaderState.title = name
                setStatus("Loaded “$name” (${text.length} chars).", R.color.accent)
            } catch (e: Exception) {
                setStatus("Document error: ${e.message}", R.color.err)
            } finally { setBusy(false) }
        }
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply { hint = "https://example.com/article"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Read a web page")
            .setView(input)
            .setPositiveButton("Fetch") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) fetchUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchUrl(url: String) {
        setBusy(true); setStatus("Fetching $url …", R.color.fg_dim)
        lifecycleScope.launch {
            try {
                val article = withContext(Dispatchers.IO) { WebExtractor.fetch(url) }
                b.etText.setText(article.text)
                ReaderState.title = article.title
                setStatus("Loaded “${article.title}” (${article.text.length} chars).", R.color.accent)
            } catch (e: Exception) {
                setStatus("URL error: ${e.message}", R.color.err)
            } finally { setBusy(false) }
        }
    }

    private fun runOcr(uri: Uri) {
        setBusy(true); setStatus("Recognising text…", R.color.fg_dim)
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { OcrReader.extract(this@MainActivity, uri) }
                b.etText.setText(text)
                ReaderState.title = "Scanned text"
                setStatus("OCR complete (${text.length} chars).", R.color.accent)
            } catch (e: Exception) {
                setStatus("OCR error: ${e.message}", R.color.err)
            } finally { setBusy(false) }
        }
    }

    private fun onExport() {
        if (!initialised || busy) return
        if (b.etText.text.toString().isBlank()) { setStatus("Nothing to export.", R.color.warn); return }
        if (b.spinnerVoice.selectedItem == null) { setStatus("Download a voice first.", R.color.warn); return }
        val formats = AudioFormatType.values()
        AlertDialog.Builder(this)
            .setTitle("Export format")
            .setItems(formats.map { it.label }.toTypedArray()) { _, which ->
                pendingExportFormat = formats[which]
                showExportLocationDialog()
            }.show()
    }

    private fun showExportLocationDialog() {
        val base = ReaderState.title.ifBlank { "piper_audio" }.let {
            it.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "piper_audio" }
        }
        AlertDialog.Builder(this)
            .setTitle("Save ${pendingExportFormat.label} to")
            .setItems(arrayOf("Downloads folder", "Choose location…")) { _, which ->
                when (which) {
                    0 -> exportToDownloads(base, pendingExportFormat)
                    1 -> createAudioLauncher.launch("$base.${pendingExportFormat.ext}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportToDownloads(base: String, type: AudioFormatType) {
        runExport(type) { AudioDownloads.open(this, base, type).let { it.stream to (it.displayPath to it.finalize) } }
    }

    private fun exportToUri(outUri: Uri, type: AudioFormatType) {
        runExport(type) {
            val os = contentResolver.openOutputStream(outUri)
                ?: throw PiperException("Could not open the chosen file for writing.")
            val name = DocumentReader.displayName(this, outUri) ?: "audio.${type.ext}"
            os to (name to {})
        }
    }

    private fun runExport(type: AudioFormatType, targetProvider: () -> Pair<java.io.OutputStream, Pair<String, () -> Unit>>) {
        val stem = b.spinnerVoice.selectedItem as? String ?: Piper.config.defaultVoice
        val text = b.etText.text.toString()
        setBusy(true); b.progress.isIndeterminate = false; b.progress.progress = 0
        setStatus("Exporting ${type.label}…", R.color.fg_dim)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val (stream, meta) = targetProvider()
                    val (displayPath, finalize) = meta
                    val segments = stream.use { os ->
                        val sink = AudioSink.create(type, os, cacheDir)
                        TextToAudio.convert(Piper.engine, text, stem, sink, progress = { done, total ->
                            runOnUiThread {
                                b.progress.progress = if (total > 0) done * 100 / total else 0
                                setStatus("Exporting ${type.label}: $done/$total…", R.color.fg_dim)
                            }
                        })
                    }
                    finalize()
                    segments to displayPath
                }
                setStatus("Saved to ${result.second}  (${result.first} segments).", R.color.accent)
                appendLog("· ${type.label} saved to ${result.second} [$stem]")
            } catch (e: Exception) {
                setStatus("Export error: ${e.message}", R.color.err)
            } finally {
                b.progress.isIndeterminate = true; syncLog(); setBusy(false)
            }
        }
    }

    private fun importVoice(treeUri: Uri) {
        setBusy(true); setStatus("Importing voice…", R.color.fg_dim)
        lifecycleScope.launch {
            try {
                val stem = withContext(Dispatchers.IO) {
                    val s = VoiceImporter.importFromTree(this@MainActivity, treeUri, Piper.config.voicesDir)
                    Piper.engine.reloadVoices(); s
                }
                refreshVoiceSpinner()
                setStatus("Imported voice “$stem”.", R.color.accent)
            } catch (e: Exception) {
                setStatus("Import error: ${e.message}", R.color.err)
            } finally { setBusy(false) }
        }
    }

    private fun showPreGenerateDialog() {
        if (!initialised) return
        val input = EditText(this).apply {
            hint = "One phrase per line"
            setText("Good morning.\nYou have a new alarm.\nTime to wake up.")
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Pre-generate phrases")
            .setView(input)
            .setPositiveButton("Generate") { _, _ ->
                val lines = input.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@setPositiveButton
                val stem = b.spinnerVoice.selectedItem as? String ?: Piper.config.defaultVoice
                runPreGenerate(lines, stem)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runPreGenerate(lines: List<String>, stem: String) {
        setBusy(true); b.progress.isIndeterminate = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Piper.engine.preGenerate(lines, stem) { done, total ->
                        runOnUiThread {
                            b.progress.progress = if (total > 0) (done * 100 / total) else 0
                            setStatus("Pre-generating $done/$total…", R.color.fg_dim)
                        }
                    }
                }
                setStatus("Pre-generation complete (${Piper.engine.cacheCount} cached).", R.color.accent)
            } catch (e: Exception) {
                setStatus("Pre-generate error: ${e.message}", R.color.err)
            } finally {
                b.progress.isIndeterminate = true; syncLog(); setBusy(false)
            }
        }
    }

    private fun showPronunciationDialog() {
        val dict = PronunciationDict.get(this)
        val entries = dict.all()
        val body = if (entries.isEmpty()) "No entries yet." else
            entries.entries.joinToString("\n") { "• ${it.key}  →  ${it.value}" }
        AlertDialog.Builder(this)
            .setTitle("Pronunciation dictionary")
            .setMessage(body)
            .setPositiveButton("Add…") { _, _ -> showAddPronunciationDialog() }
            .setNeutralButton("Clear all") { _, _ -> dict.clear(); setStatus("Pronunciation dictionary cleared.", R.color.warn) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddPronunciationDialog() {
        val from = EditText(this).apply { hint = "Written word/phrase (e.g. GIF)" }
        val to = EditText(this).apply { hint = "Spoken as (e.g. jiff)" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(from); addView(to)
        }
        AlertDialog.Builder(this)
            .setTitle("Add pronunciation")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val f = from.text.toString().trim(); val t = to.text.toString().trim()
                if (f.isNotEmpty() && t.isNotEmpty()) {
                    PronunciationDict.get(this).put(f, t)
                    setStatus("Added “$f → $t”.", R.color.accent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        if (!initialised) return
        val normalize = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Expand numbers, currency & abbreviations"
            isChecked = Piper.config.normalizeText
        }
        val sizeLabel = TextView(this).apply {
            text = "Editor text size: ${Piper.config.editorTextSizeSp.toInt()} sp"
            setPadding(0, 24, 0, 0)
        }
        val sizeSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 12f; valueTo = 30f; stepSize = 1f
            value = Piper.config.editorTextSizeSp.coerceIn(12f, 30f)
            addOnChangeListener { _, v, _ -> sizeLabel.text = "Editor text size: ${v.toInt()} sp" }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(normalize); addView(sizeLabel); addView(sizeSlider)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                Piper.config.normalizeText = normalize.isChecked
                Piper.config.editorTextSizeSp = sizeSlider.value
                b.etText.textSize = sizeSlider.value
                setStatus("Settings saved.", R.color.accent)
            }
            .setNeutralButton(getString(R.string.import_voice)) { _, _ -> importVoiceLauncher.launch(null) }
            .setNegativeButton("Close", null)
            .show()
    }

    private var handledIntentHash = 0
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val key = System.identityHashCode(intent) xor (intent.action?.hashCode() ?: 0)
        if (key == handledIntentHash) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!shared.isNullOrBlank()) { b.etText.setText(shared); handledIntentHash = key; setStatus("Text received.", R.color.accent) }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val sel = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!sel.isNullOrBlank()) { b.etText.setText(sel); handledIntentHash = key; setStatus("Selection received.", R.color.accent) }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null && initialised) { handledIntentHash = key; loadDocument(uri) }
            }
        }
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showAbout() {
        val msg = """
            Text2MP3
            by Ali Muhammad

            Version ${packageManager.getPackageInfo(packageName, 0).versionName}
            Contact: alimuhammadk62@gmail.com

            On-device neural text-to-speech using Piper voices. Acts as a system-wide
            Android TTS engine, reads documents (PDF, EPUB, DOCX, HTML, TXT), web pages
            and scanned images (OCR), reads aloud in the background with lock-screen
            controls and a sleep timer, and exports to MP3, WAV or M4A — fully offline
            once a voice is installed.

            Open-source components & licences:
            • TTS engine — sherpa-onnx (Apache-2.0)
            • Inference — ONNX Runtime (MIT)
            • Phonemisation — espeak-ng (GPLv3); espeak-ng-data bundled
            • Voices — Piper / rhasspy-piper-voices (per-voice: MIT / CC BY)
            • MP3 encoder — java-lame / LAME (LGPL)
            • PDF — PdfBox-Android (Apache-2.0)
            • OCR — Google ML Kit (on-device, Apache-2.0)
            • Archive handling — Apache Commons Compress (Apache-2.0)

            Because espeak-ng (GPLv3) is included, this application is distributed
            under the terms of the GNU GPL v3.

            Source code: https://github.com/AliMuhammadK62/text2mp3

            Text2MP3 is free, open-source donationware. If you find it useful,
            you can support development:  https://paypal.me/AliMuhammadK62

            © 2026 Ali Muhammad.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("About Text2MP3")
            .setMessage(msg)
            .setNeutralButton("Donate ♥") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/AliMuhammadK62")))
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun refreshVoiceSpinner() {
        val stems = Piper.engine.installedVoices.map { it.stem }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, stems)
        b.spinnerVoice.adapter = adapter
        val idx = stems.indexOf(Piper.config.defaultVoice)
        if (idx >= 0) b.spinnerVoice.setSelection(idx)
    }

    private fun updateRateLabel(value: Float) {
        b.tvRate.text = "Speaking rate: ${"%.1f".format(value)}×  (0.5 = faster · 2.0 = slower)"
    }

    private fun updatePitchLabel(value: Float) { b.tvPitch.text = "Pitch: ${"%.2f".format(value)}×" }
    private fun updateVolumeLabel(value: Float) { b.tvVolume.text = "Volume: ${(value * 100).toInt()}%" }

    private fun setBusy(b2: Boolean) {
        busy = b2
        b.progress.visibility = if (b2) View.VISIBLE else View.INVISIBLE
        b.btnSpeak.isEnabled = !b2
        b.btnPregen.isEnabled = !b2
        b.btnFileMp3.isEnabled = !b2
        b.btnReadAloud.isEnabled = !b2
        b.btnOpenDoc.isEnabled = !b2
        b.btnReadUrl.isEnabled = !b2
        b.btnOcr.isEnabled = !b2
    }

    private fun setStatus(msg: String, colorRes: Int) {
        b.tvStatus.text = msg
        b.tvStatus.setTextColor(getColor(colorRes))
    }

    private fun appendLog(line: String) {
        synchronized(Piper.logBuffer) { Piper.logBuffer.append(line).append('\n') }
        syncLog()
    }

    private fun syncLog() {
        val text = synchronized(Piper.logBuffer) { Piper.logBuffer.toString() }
        b.tvLog.text = text
        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }
}
