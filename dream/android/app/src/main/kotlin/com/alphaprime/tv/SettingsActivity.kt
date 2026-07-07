package com.alphaprime.tv

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alphaprime.tv.data.Prefs
import com.alphaprime.tv.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSaved()
        setupToggle()

        binding.btnSave.setOnClickListener { save() }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadSaved() {
        val type = Prefs.getType(this)
        if (type == "xtream") {
            binding.rbXtream.isChecked = true
            showXtream(true)
            binding.etServer.setText(Prefs.getServer(this))
            binding.etUser.setText(Prefs.getUser(this))
            binding.etPass.setText(Prefs.getPass(this))
        } else {
            binding.rbM3u.isChecked = true
            showXtream(false)
            binding.etM3u.setText(Prefs.getM3U(this))
        }
    }

    private fun setupToggle() {
        binding.rgType.setOnCheckedChangeListener { _, id ->
            showXtream(id == R.id.rb_xtream)
        }
    }

    private fun showXtream(show: Boolean) {
        binding.formXtream.visibility = if (show) View.VISIBLE else View.GONE
        binding.formM3u.visibility    = if (show) View.GONE    else View.VISIBLE
    }

    private fun save() {
        binding.tvSaveOk.visibility = View.GONE
        if (binding.rbM3u.isChecked) {
            val url = binding.etM3u.text.toString().trim()
            if (url.isBlank()) { toast(getString(R.string.enter_url)); return }
            Prefs.saveM3U(this, url)
        } else {
            val server = binding.etServer.text.toString().trim()
            val user   = binding.etUser.text.toString().trim()
            val pass   = binding.etPass.text.toString()
            if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                toast(getString(R.string.fill_all)); return
            }
            Prefs.saveXtream(this, server, user, pass)
        }
        binding.tvSaveOk.visibility = View.VISIBLE
        // Fechar após breve momento para o utilizador ver a confirmação
        binding.root.postDelayed({ finish() }, 800)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
