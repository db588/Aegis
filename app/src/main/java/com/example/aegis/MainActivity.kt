package com.example.aegis

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aegis.config.Config
import com.example.aegis.data.AppDatabase
import com.example.aegis.data.BlocklistManager
import com.example.aegis.data.WhitelistedDomain
import com.example.aegis.vpn.DnsVpnService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var vpnToggle: SwitchCompat
    private lateinit var statusText: TextView
    private lateinit var blockedCountText: TextView
    private lateinit var blocklistRecycler: RecyclerView
    private lateinit var addSocialMediaBtn: Button
    private lateinit var importUrlBtn: Button
    private lateinit var importFileBtn: Button
    private lateinit var whitelistBtn: Button

    private lateinit var blocklistManager: BlocklistManager
    private lateinit var db: AppDatabase
    private val adapter = BlocklistAdapter()
    private val statsHandler = Handler(Looper.getMainLooper())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchVpnService()
        } else {
            vpnToggle.isChecked = false
            updateStatus("VPN permission denied")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBlocklistFromUri(it) }
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            if (DnsVpnService.isRunning) {
                blockedCountText.text =
                    getString(R.string.blocked_count, DnsVpnService.blockedCount.get())
            }
            statsHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)
        blocklistManager = BlocklistManager(this)

        initializeViews()
        setupListeners()
        loadBlocklists()
        initializeDefaultBlocklists()
    }

    override fun onResume() {
        super.onResume()
        vpnToggle.isChecked = DnsVpnService.isRunning
        statsHandler.post(statsUpdater)
    }

    override fun onPause() {
        super.onPause()
        statsHandler.removeCallbacks(statsUpdater)
    }

    private fun initializeViews() {
        vpnToggle = findViewById(R.id.vpn_toggle)
        statusText = findViewById(R.id.status_text)
        blockedCountText = findViewById(R.id.blocked_count_text)
        blocklistRecycler = findViewById(R.id.blocklist_recycler)
        addSocialMediaBtn = findViewById(R.id.btn_add_social_media)
        importUrlBtn = findViewById(R.id.btn_import_url)
        importFileBtn = findViewById(R.id.btn_import_file)
        whitelistBtn = findViewById(R.id.btn_whitelist)

        blocklistRecycler.layoutManager = LinearLayoutManager(this)
        blocklistRecycler.adapter = adapter
    }

    private fun setupListeners() {
        vpnToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !DnsVpnService.isRunning) startVpn()
            else if (!isChecked && DnsVpnService.isRunning) stopVpn()
        }

        addSocialMediaBtn.setOnClickListener { addSocialMediaBlocklist() }
        importUrlBtn.setOnClickListener { showImportUrlDialog() }
        importFileBtn.setOnClickListener {
            filePickerLauncher.launch(arrayOf("text/*", "application/octet-stream"))
        }
        whitelistBtn.setOnClickListener { showWhitelistDialog() }

        adapter.onEnableToggle = { blocklist, enabled ->
            lifecycleScope.launch {
                blocklistManager.updateBlocklistStatus(blocklist.id, enabled)
                promptVpnRestartIfRunning()
            }
        }

        adapter.onDelete = { blocklist ->
            AlertDialog.Builder(this)
                .setTitle("Delete ${blocklist.name}?")
                .setMessage("This removes ${blocklist.domainCount} domains.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        blocklistManager.deleteBlocklist(blocklist.id)
                        promptVpnRestartIfRunning()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------- VPN control ----------

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val vpnIntent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
        }
        startForegroundService(vpnIntent)
        updateStatus("Aegis active — DNS filtering on")
    }

    private fun stopVpn() {
        val vpnIntent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        startService(vpnIntent)
        updateStatus("Aegis stopped")
    }

    private fun promptVpnRestartIfRunning() {
        if (DnsVpnService.isRunning) {
            Toast.makeText(
                this,
                "Toggle Aegis off and on to apply changes",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------- Blocklist actions ----------

    private fun loadBlocklists() {
        lifecycleScope.launch {
            db.blocklistDao().getAllBlocklists().collect { blocklists ->
                adapter.submitList(blocklists)
                val totalDomains = blocklists.sumOf { it.domainCount }
                updateStatus("${blocklists.size} blocklists, $totalDomains domains")
            }
        }
    }

    private fun initializeDefaultBlocklists() {
        lifecycleScope.launch {
            val existing = db.blocklistDao().getEnabledBlocklists()
            if (existing.isEmpty()) {
                blocklistManager.createCustomBlocklist(
                    "Social Media",
                    "X, Reddit, Instagram, Facebook, TikTok and friends",
                    Config.SOCIAL_MEDIA_DOMAINS
                )
            }
        }
    }

    private fun addSocialMediaBlocklist() {
        lifecycleScope.launch {
            blocklistManager.createCustomBlocklist(
                "Social Media",
                "X, Reddit, Instagram, Facebook, TikTok and friends",
                Config.SOCIAL_MEDIA_DOMAINS
            )
            Toast.makeText(
                this@MainActivity,
                "Added ${Config.SOCIAL_MEDIA_DOMAINS.size} social media domains",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showImportUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val nameInput = EditText(this).apply { hint = "List name (e.g. StevenBlack hosts)" }
        val urlInput = EditText(this).apply {
            hint = "https://…"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Import blocklist from URL")
            .setMessage("Pi-hole / hosts-file format supported")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val name = nameInput.text.toString().ifBlank { "Imported list" }
                val url = urlInput.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                importFromUrl(name, url)
            }
            .setNeutralButton("Use StevenBlack (popular)") { _, _ ->
                importFromUrl(
                    "StevenBlack hosts",
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromUrl(name: String, url: String) {
        updateStatus("Downloading $name…")
        lifecycleScope.launch {
            val id = blocklistManager.importBlocklistFromUrl(name, "Imported from URL", url)
            if (id != null) {
                Toast.makeText(this@MainActivity, "Imported $name", Toast.LENGTH_SHORT).show()
                promptVpnRestartIfRunning()
            } else {
                Toast.makeText(this@MainActivity, "Import failed — check URL", Toast.LENGTH_LONG).show()
                updateStatus("Import failed")
            }
        }
    }

    private fun importBlocklistFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (content.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "File is empty", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported file"
                val id = blocklistManager.importBlocklistFromContent(fileName, "Imported from file", content)
                Toast.makeText(this@MainActivity, "Imported $fileName", Toast.LENGTH_SHORT).show()
                promptVpnRestartIfRunning()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Whitelist ----------

    private fun showWhitelistDialog() {
        lifecycleScope.launch {
            val whitelisted = db.whitelistDao().getWhitelistedDomains().sorted()
            val items = whitelisted.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Whitelist (${items.size})")
                .setItems(items) { _, which ->
                    confirmRemoveFromWhitelist(items[which])
                }
                .setPositiveButton("Add domain") { _, _ -> showAddWhitelistDialog() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showAddWhitelistDialog() {
        val input = EditText(this).apply {
            hint = "example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Add to whitelist")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val domain = input.text.toString().trim().lowercase()
                if (domain.isNotBlank()) {
                    lifecycleScope.launch {
                        blocklistManager.addToWhitelist(domain)
                        Toast.makeText(this@MainActivity, "$domain whitelisted", Toast.LENGTH_SHORT).show()
                        promptVpnRestartIfRunning()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveFromWhitelist(domain: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $domain from whitelist?")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    blocklistManager.removeFromWhitelist(domain)
                    promptVpnRestartIfRunning()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }
}
