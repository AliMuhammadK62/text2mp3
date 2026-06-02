package com.alimuhammad.text2mp3

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alimuhammad.text2mp3.databinding.ActivityVoicesBinding
import com.alimuhammad.text2mp3.databinding.ItemVoiceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoicesActivity : AppCompatActivity() {

    private lateinit var b: ActivityVoicesBinding
    private lateinit var adapter: VoiceAdapter
    private val downloading = HashSet<String>()
    private var bulkRunning = false

    private val languages = listOf(ALL) + VoiceCatalog.languages()
    private var visibleStems: List<String> = VoiceCatalog.STEMS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Piper.init(applicationContext)
        b = ActivityVoicesBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.title = getString(R.string.title_voices)

        val langLabels = languages.map { if (it == ALL) "All languages" else VoiceCatalog.languageLabel(it) }
        b.spinnerLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
        b.spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { applyFilter(languages[pos]) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        adapter = VoiceAdapter(
            stemsProvider = { visibleStems },
            isInstalled = { Piper.repository.isInstalled(it) },
            isBusy = { downloading.contains(it) },
            onClick = ::onVoiceClicked,
            onLongClick = ::onVoiceLongClicked
        )
        b.rvVoices.layoutManager = LinearLayoutManager(this)
        b.rvVoices.adapter = adapter

        b.btnDownloadAll.setOnClickListener { downloadAll() }
    }

    private fun applyFilter(lang: String) {
        visibleStems = if (lang == ALL) VoiceCatalog.STEMS
        else VoiceCatalog.STEMS.filter { VoiceCatalog.localeOf(it) == lang }
        adapter.notifyDataSetChanged()
    }

    private fun indexOf(stem: String): Int = visibleStems.indexOf(stem)

    private fun downloadAll() {
        if (bulkRunning) return
        val pending = visibleStems.filter { !Piper.repository.isInstalled(it) }
        if (pending.isEmpty()) {
            Toast.makeText(this, "All shown voices are already installed.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Download ${pending.size} voices?")
            .setMessage("This downloads every shown voice (roughly 20–60 MB each). " +
                "Make sure you have the storage and ideally an unmetered connection. " +
                "Tip: pick a language above first to download just those.")
            .setPositiveButton("Download") { _, _ -> startBulkDownload(pending) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startBulkDownload(pending: List<String>) {
        bulkRunning = true
        b.btnDownloadAll.isEnabled = false
        b.tvAllStatus.visibility = View.VISIBLE
        b.allProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val total = pending.size
            val failures = ArrayList<String>()
            for ((i, stem) in pending.withIndex()) {
                val idx = indexOf(stem)
                downloading.add(stem)
                if (idx >= 0) adapter.notifyItemChanged(idx)
                try {
                    withContext(Dispatchers.IO) {
                        Piper.repository.download(stem) { d, t, phase ->
                            runOnUiThread {
                                b.allProgress.isIndeterminate = t <= 0
                                if (t > 0) b.allProgress.progress = (d * 100 / t).toInt()
                                b.tvAllStatus.text = "Downloading ${i + 1}/$total: $stem — $phase"
                            }
                        }
                        Piper.engine.reloadVoices()
                    }
                } catch (e: Exception) {
                    failures.add(stem)
                } finally {
                    downloading.remove(stem)
                    val ix = indexOf(stem)
                    if (ix >= 0) adapter.notifyItemChanged(ix)
                }
            }
            b.allProgress.visibility = View.GONE
            b.tvAllStatus.text = if (failures.isEmpty()) "All $total voices downloaded."
            else "Done — ${total - failures.size}/$total ok. Failed: ${failures.joinToString(", ")}"
            b.btnDownloadAll.isEnabled = true
            bulkRunning = false
        }
    }

    private fun onVoiceClicked(stem: String, holder: VoiceAdapter.VH) {
        if (bulkRunning || downloading.contains(stem)) return
        if (Piper.repository.isInstalled(stem)) {
            val ix = indexOf(stem); if (ix >= 0) adapter.notifyItemChanged(ix)
            return
        }
        downloading.add(stem)
        holder.bindProgress(0, "Starting…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Piper.repository.download(stem) { done, total, phase ->
                        runOnUiThread {
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            holder.bindProgress(pct, phase)
                        }
                    }
                    Piper.engine.reloadVoices()
                }
            } catch (e: Exception) {
                AlertDialog.Builder(this@VoicesActivity)
                    .setTitle("Download failed").setMessage(e.message).setPositiveButton("OK", null).show()
            } finally {
                downloading.remove(stem)
                val ix = indexOf(stem); if (ix >= 0) adapter.notifyItemChanged(ix)
            }
        }
    }

    private fun onVoiceLongClicked(stem: String) {
        if (bulkRunning || !Piper.repository.isInstalled(stem) || downloading.contains(stem)) return
        AlertDialog.Builder(this)
            .setTitle("Delete $stem?")
            .setMessage("Frees ${Piper.repository.installedSizeBytes(stem) / 1024 / 1024} MB.")
            .setPositiveButton("Delete") { _, _ ->
                Piper.repository.delete(stem)
                Piper.engine.reloadVoices()
                val ix = indexOf(stem); if (ix >= 0) adapter.notifyItemChanged(ix)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class VoiceAdapter(
        private val stemsProvider: () -> List<String>,
        private val isInstalled: (String) -> Boolean,
        private val isBusy: (String) -> Boolean,
        private val onClick: (String, VH) -> Unit,
        private val onLongClick: (String) -> Unit
    ) : RecyclerView.Adapter<VoiceAdapter.VH>() {

        inner class VH(val vb: ItemVoiceBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bindProgress(pct: Int, phase: String) {
                vb.itemProgress.visibility = View.VISIBLE
                if (pct >= 0) { vb.itemProgress.isIndeterminate = false; vb.itemProgress.progress = pct }
                else vb.itemProgress.isIndeterminate = true
                vb.tvState.text = phase
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val vb = ItemVoiceBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            return VH(vb)
        }

        override fun getItemCount(): Int = stemsProvider().size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val stem = stemsProvider()[position]
            val meta = VoiceCatalog.parse(stem)
            holder.vb.tvName.text = stem
            holder.vb.tvInfo.text = "${meta.locale} · ${meta.name} · ${meta.quality}"
            holder.vb.itemProgress.visibility = View.GONE

            val installed = isInstalled(stem)
            val busy = isBusy(stem)
            holder.vb.tvState.text = when {
                busy -> "…"
                installed -> "✓ installed"
                else -> "Download"
            }
            holder.itemView.setOnClickListener { onClick(stem, holder) }
            holder.itemView.setOnLongClickListener { onLongClick(stem); true }
        }
    }

    companion object { private const val ALL = "__all__" }
}
