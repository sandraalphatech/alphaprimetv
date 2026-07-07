package com.alphaprime.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alphaprime.tv.data.M3UChannel
import com.alphaprime.tv.data.M3URepository
import com.alphaprime.tv.data.Prefs
import com.alphaprime.tv.databinding.ActivityMainBinding
import com.alphaprime.tv.ui.ChannelAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var adapter: ChannelAdapter

    // Lista completa de canais (TV apenas)
    private val allChannels = mutableListOf<M3UChannel>()
    private val seenUrls    = mutableSetOf<String>()

    private var currentChannel: M3UChannel? = null
    private var networkLoadDone = false

    private val uiHandler  = Handler(Looper.getMainLooper())
    private var switchJob: Runnable? = null
    private var clockJob:  Runnable? = null
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initVLC()
        initRecyclerView()
        initSearch()
        startClock()

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Prefs.isConfigured(this)) {
            loadChannels()
        } else {
            showStatus(getString(R.string.no_config), isError = false)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Se voltou das Configurações com lista nova e ainda não carregou
        if (Prefs.isConfigured(this) && allChannels.isEmpty() && !networkLoadDone) {
            loadChannels()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        switchJob?.let { uiHandler.removeCallbacks(it) }
        clockJob?.let  { uiHandler.removeCallbacks(it) }
        releaseVLC()
    }

    // ─── VLC setup ───────────────────────────────────────────────────────────────

    private fun initVLC() {
        libVLC = LibVLC(this, arrayListOf(
            "--network-caching=3000",
            "--live-caching=3000",
            "--rtsp-tcp",
            "--no-drop-late-frames",
            "--no-skip-frames"
        ))
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer.attachViews(binding.vlcLayout, null, false, false)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> runOnUiThread {
                    binding.playerProgress.visibility = View.GONE
                    binding.tvPlayerError.visibility  = View.GONE
                    binding.channelInfoBar.visibility = View.VISIBLE
                    binding.tvStatusDot.text  = "● AO VIVO"
                    binding.tvStatusDot.setTextColor(0xFF10B981.toInt())
                }
                MediaPlayer.Event.Buffering -> runOnUiThread {
                    binding.playerProgress.visibility =
                        if (event.buffering < 100f) View.VISIBLE else View.GONE
                }
                MediaPlayer.Event.EncounteredError -> runOnUiThread {
                    binding.playerProgress.visibility = View.GONE
                    binding.tvPlayerError.text = "⚠  Erro ao reproduzir canal"
                    binding.tvPlayerError.visibility = View.VISIBLE
                    binding.tvStatusDot.text  = "● ERRO"
                    binding.tvStatusDot.setTextColor(0xFFEF4444.toInt())
                }
                MediaPlayer.Event.EndReached -> runOnUiThread {
                    binding.playerProgress.visibility = View.GONE
                    binding.tvStatusDot.text  = "● PARADO"
                    binding.tvStatusDot.setTextColor(0xFF9CA3AF.toInt())
                }
            }
        }
    }

    private fun releaseVLC() {
        try {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVLC.release()
        } catch (_: Exception) {}
    }

    // ─── RecyclerView ────────────────────────────────────────────────────────────

    private fun initRecyclerView() {
        adapter = ChannelAdapter(
            onClick = { ch -> playChannel(ch) },
            onFocus = { ch -> playChannel(ch) }
        )
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter       = this@MainActivity.adapter
            setHasFixedSize(false)
            itemAnimator  = null
            setItemViewCacheSize(20)
        }
    }

    // ─── Pesquisa ────────────────────────────────────────────────────────────────

    private fun initSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterChannels(s?.toString() ?: "") }
        })
    }

    private fun filterChannels(query: String) {
        lifecycleScope.launch {
            val filtered = if (query.isBlank()) allChannels.toList()
            else withContext(Dispatchers.Default) {
                allChannels.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.group.contains(query, ignoreCase = true)
                }
            }
            submitList(filtered)
        }
    }

    // ─── Carregamento de canais ───────────────────────────────────────────────────

    private fun loadChannels() {
        showLoading(true)
        networkLoadDone = false

        lifecycleScope.launch {
            // 1. Cache imediata (UI responsiva enquanto a rede carrega)
            val cached = withContext(Dispatchers.IO) { M3URepository.loadCache(this@MainActivity) }
            if (cached.isNotEmpty()) {
                val tvOnly = cached.filter { it.type == com.alphaprime.tv.data.ChannelType.TV && it.url.isNotBlank() }
                allChannels.clear(); seenUrls.clear()
                allChannels.addAll(tvOnly); tvOnly.forEach { seenUrls.add(it.url) }
                submitList(allChannels.toList())
                if (currentChannel == null && allChannels.isNotEmpty()) playChannel(allChannels.first())
            }

            // 2. Rede (bloqueia thread de IO; os batches acumulam-se, UI actualiza só no final)
            try {
                withContext(Dispatchers.IO) {
                    var isFirstBatch = true
                    M3URepository.loadNetwork(this@MainActivity) { batch, isFinal ->
                        if (isFirstBatch) {
                            // Primeiro batch da rede — descarta cache e começa de novo
                            allChannels.clear(); seenUrls.clear()
                            isFirstBatch = false
                        }
                        for (ch in batch) {
                            if (ch.type == com.alphaprime.tv.data.ChannelType.TV &&
                                ch.url.isNotBlank() && seenUrls.add(ch.url)) {
                                allChannels.add(ch)
                            }
                        }
                        // Actualiza UI só no batch final → 0 recomposições durante o download
                        if (isFinal) {
                            runOnUiThread {
                                networkLoadDone = true
                                showLoading(false)
                                submitList(allChannels.toList())
                                if (currentChannel == null && allChannels.isNotEmpty()) {
                                    playChannel(allChannels.first())
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    networkLoadDone = true
                    showLoading(false)
                    if (allChannels.isEmpty()) {
                        showStatus("${getString(R.string.error_loading)}: ${e.message}", isError = true)
                    }
                }
            }
        }
    }

    private fun submitList(list: List<M3UChannel>) {
        binding.tvCount.text = list.size.toString()
        adapter.submitList(list)
    }

    // ─── Reprodução ──────────────────────────────────────────────────────────────

    private fun playChannel(ch: M3UChannel) {
        if (ch.url == currentChannel?.url) return
        currentChannel = ch
        adapter.selectedUrl = ch.url

        // Actualiza painel de info
        binding.tvNowPlaying.text  = ch.name
        binding.tvInfoChannel.text = ch.name
        binding.tvInfoGroup.text   = ch.group.ifBlank { "Geral" }
        binding.channelInfoBar.visibility = View.GONE  // esconde até o player confirmar Playing
        binding.playerProgress.visibility = View.VISIBLE
        binding.tvPlayerError.visibility  = View.GONE
        binding.tvStatusDot.text = "● A carregar"
        binding.tvStatusDot.setTextColor(0xFFD4A843.toInt())

        // Debounce 100ms — evita paragem/arranque rápido ao percorrer com D-pad
        switchJob?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable {
            try {
                mediaPlayer.stop()
                val media = Media(libVLC, android.net.Uri.parse(ch.url)).apply {
                    addOption(":network-caching=3000")
                    addOption(":live-caching=3000")
                }
                mediaPlayer.media = media
                media.release()
                mediaPlayer.play()
            } catch (e: Exception) {
                runOnUiThread {
                    binding.playerProgress.visibility = View.GONE
                    binding.tvPlayerError.text = "⚠  ${e.message}"
                    binding.tvPlayerError.visibility = View.VISIBLE
                }
            }
        }
        switchJob = r
        uiHandler.postDelayed(r, 100)
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────────

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (!loading) binding.tvStatus.visibility = View.GONE
    }

    private fun showStatus(msg: String, isError: Boolean) {
        binding.tvStatus.text = msg
        binding.tvStatus.setTextColor(if (isError) 0xFFEF4444.toInt() else 0xFF9CA3AF.toInt())
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun startClock() {
        clockJob = object : Runnable {
            override fun run() {
                binding.tvClock.text = clockFmt.format(Date())
                uiHandler.postDelayed(this, 30_000)
            }
        }
        uiHandler.post(clockJob!!)
    }
}
