package com.bpoppa.pocketai

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var server: ServerManager
    private lateinit var llama: LlamaClient
    private lateinit var store: ChatStore
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: TextInputEditText
    private lateinit var searchSwitch: MaterialSwitch
    private lateinit var statusView: TextView

    private val history = mutableListOf<ChatMsg>()
    private var busy = false
    private var secureMode = false

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are PocketAI, a private assistant running fully offline on the user's phone. " +
                "Be helpful, warm and concise."
    }

    private fun systemPrompt(): String = prefs.getString("system_prompt", null) ?: DEFAULT_SYSTEM_PROMPT
    private val historyOn: Boolean get() = prefs.getBoolean("history_on", true) && !secureMode
    private val ragOn: Boolean get() = prefs.getBoolean("rag_on", false) && historyOn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        server = ServerHolder.get(this)
        llama = LlamaClient(server.port, server.apiKey)
        store = ChatStore(this)
        prefs = getSharedPreferences("pocketai", MODE_PRIVATE)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "PocketAI (${server.flavorName})"
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear -> {
                    confirmDeleteChat()
                    true
                }
                R.id.action_history -> {
                    prefs.edit().putBoolean("history_on", !item.isChecked).apply()
                    item.isChecked = !item.isChecked
                    history.clear()
                    if (historyOn) {
                        history.addAll(store.load())
                        setStatus("chat history: saved")
                    } else {
                        setStatus("chat history: not saved (this session only)")
                    }
                    adapter.submit(history.map { UiMsg(it.role, it.content) })
                    true
                }
                R.id.action_rag -> {
                    prefs.edit().putBoolean("rag_on", !item.isChecked).apply()
                    item.isChecked = !item.isChecked
                    setStatus(if (item.isChecked) "RAG memory on (needs saved history)" else "RAG memory off")
                    true
                }
                R.id.action_secure -> {
                    toggleSecure(item)
                    true
                }
                R.id.action_prompt -> {
                    editSystemPrompt()
                    true
                }
                R.id.action_server -> {
                    onServerButton()
                    true
                }
                R.id.action_token -> {
                    showToken()
                    true
                }
                else -> false
            }
        }

        // Edge-to-edge (targetSdk 35): keep content out of the system bars.
        val root = findViewById<View>(R.id.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        recycler = findViewById(R.id.recycler)
        adapter = ChatAdapter()
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        input = findViewById(R.id.input)
        searchSwitch = findViewById(R.id.search_switch)
        statusView = findViewById(R.id.status)
        statusView.movementMethod = ScrollingMovementMethod()
        statusView.setOnClickListener { onServerButton() }

        if (historyOn) {
            history.addAll(store.load())
            adapter.submit(history.map { UiMsg(it.role, it.content) })
        }
        findViewById<FloatingActionButton>(R.id.send_button).setOnClickListener { onSend() }

        refreshStatus()
    }

    private fun onServerButton() {
        setStatus("checking server…")
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) { llama.health() }
            if (running) {
                setStatus("server already running on :${server.port}")
                return@launch
            }
            setStatus("starting ${server.flavorName} server…")
            val ok = withContext(Dispatchers.IO) { startServerWithRetry() }
            if (ok) {
                setStatus("server up on :${server.port} — ${server.modelStatus()}")
                androidx.core.content.ContextCompat.startForegroundService(
                    this@MainActivity,
                    android.content.Intent(this@MainActivity, PocketAIService::class.java)
                )
            } else {
                setStatus("server did not come up. ${server.modelStatus()}")
            }
        }
    }

    private fun startServerWithRetry(): Boolean {
        try {
            server.ensureStarted { runOnUiThread { setStatus(it) } }
        } catch (e: Exception) {
            runOnUiThread { setStatus("server start failed: ${e.message}") }
            return false
        }
        repeat (20) {
            if (llama.health()) return true
            Thread.sleep(1000)
        }
        return llama.health()
    }

    private fun onSend() {
        if (busy) return
        val text = input.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return
        setStatus("checking server…")
        lifecycleScope.launch {
            val up = withContext(Dispatchers.IO) { llama.health() }
            if (!up) {
                Toast.makeText(this@MainActivity, "Start the server first", Toast.LENGTH_SHORT).show()
                onServerButton()
                return@launch
            }
            input.setText("")
            busy = true

            history.add(ChatMsg("user", text))
            adapter.submit(history.map { UiMsg(it.role, it.content) })
            if (historyOn) store.append(ChatMsg("user", text))
            setStatus("thinking…")

            val answer = withContext(Dispatchers.IO) {
                try {
                    val system = buildString {
                        append(systemPrompt())
                        if (ragOn) {
                            val mem = store.search(text)
                            if (mem.isNotEmpty()) {
                                append("\n\nRelevant snippets from the user's past chats with you (memory):\n")
                                mem.takeLast(4).forEach { m ->
                                    append("- ${m.role}: ${m.content.take(300)}\n")
                                }
                            }
                        }
                        if (searchSwitch.isChecked) {
                            setStatus("searching the web…")
                            val results = SearchHelper.search(text)
                            val ctx = SearchHelper.contextFor(text, results)
                            if (ctx.isNotEmpty()) append("\n\n$ctx")
                        }
                    }
                    val msgs = buildList {
                        add(ChatMsg("system", system))
                        addAll(history.takeLast(12))
                    }
                    llama.complete(msgs)
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
            }
            history.add(ChatMsg("assistant", answer))
            if (historyOn) store.append(ChatMsg("assistant", answer))
            adapter.submit(history.map { UiMsg(it.role, it.content) })
            recycler.scrollToPosition(history.size - 1)
            setStatus("done")
            busy = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        lifecycleScope.launch {
            val healthy = withContext(Dispatchers.IO) { llama.health() }
            setStatus(if (healthy) "server up on :${server.port} — ${server.modelStatus()}"
                      else "server down — tap START SERVER. ${server.modelStatus()}")
        }
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusView.text = s }
    }

    private fun showToken() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("PocketAI token", server.apiKey))
        android.app.AlertDialog.Builder(this)
            .setTitle("Access token")
            .setMessage("${server.apiKey}\n\n(copied to clipboard — paste it into the PocketAI Companion extension options)")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDeleteChat() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete chat history?")
            .setMessage("The stored chat file will be securely overwritten and removed from this phone. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                store.secureWipe()
                history.clear()
                adapter.submit(emptyList())
                setStatus("chat history securely wiped")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleSecure(item: android.view.MenuItem) {
        if (!item.isChecked) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Enable secure chat?")
                .setMessage("Stored chat history is wiped now, this session is never saved, and the chat file is wiped again when you leave the app.")
                .setPositiveButton("Enable") { _, _ ->
                    secureMode = true
                    item.isChecked = true
                    store.secureWipe()
                    history.clear()
                    adapter.submit(emptyList())
                    setStatus("secure chat: nothing saved, wipes on exit")
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            secureMode = false
            item.isChecked = false
            setStatus("secure chat off")
        }
    }

    private fun editSystemPrompt() {
        val box = android.widget.EditText(this).apply {
            setText(systemPrompt())
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom system prompt")
            .setMessage("Defines PocketAI's persona and rules for the app chat. Make it yours.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("system_prompt", box.text.toString().trim()).apply()
                Toast.makeText(this, "system prompt saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs.edit().remove("system_prompt").apply()
                Toast.makeText(this, "system prompt reset to default", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_history)?.isChecked = historyOn
        menu.findItem(R.id.action_rag)?.isChecked = ragOn
        menu.findItem(R.id.action_secure)?.isChecked = secureMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (secureMode) store.secureWipe()
        if (isFinishing && !PocketAIService.running) server.stop()
    }
}

data class UiMsg(val role: String, val content: String)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<UiMsg>()
    @SuppressLint("NotifyDataSetChanged")
    fun submit(new: List<UiMsg>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val role: TextView = v.findViewById(R.id.msg_role)
        val content: TextView = v.findViewById(R.id.msg_content)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_msg, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.role.text = if (m.role == "user") "you" else "pocketai"
        holder.content.text = m.content
        holder.content.setTextIsSelectable(true)
    }
}
