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

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: TextInputEditText
    private lateinit var searchSwitch: MaterialSwitch
    private lateinit var statusView: TextView

    private val history = mutableListOf<ChatMsg>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        server = ServerHolder.get(this)
        llama = LlamaClient(server.port, server.apiKey)
        store = ChatStore(this)

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
                    store.clear()
                    history.clear()
                    adapter.submit(emptyList())
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

        history.addAll(store.load())
        adapter.submit(history.map { UiMsg(it.role, it.content) })
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
            store.append(ChatMsg("user", text))
            setStatus("thinking…")

            val answer = withContext(Dispatchers.IO) {
                try {
                    val system = buildString {
                        append("You are PocketAI, a private assistant running fully offline on the user's phone. ")
                        append("Be helpful, warm and concise. ")
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
            store.append(ChatMsg("assistant", answer))
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

    override fun onDestroy() {
        super.onDestroy()
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
