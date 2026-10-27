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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
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
    private lateinit var serverButton: ExtendedFloatingActionButton
    private lateinit var statusView: TextView

    private val history = mutableListOf<ChatMsg>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        server = createServerManager(this)
        llama = LlamaClient(server.port)
        store = ChatStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "PocketAI (${server.flavorName})"
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear) {
                store.clear()
                history.clear()
                adapter.submit(emptyList())
                true
            } else false
        }

        recycler = findViewById(R.id.recycler)
        adapter = ChatAdapter()
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        input = findViewById(R.id.input)
        searchSwitch = findViewById(R.id.search_switch)
        serverButton = findViewById(R.id.server_button)
        statusView = findViewById(R.id.status)
        statusView.movementMethod = ScrollingMovementMethod()

        history.addAll(store.load())
        adapter.submit(history.map { UiMsg(it.role, it.content) })

        serverButton.setOnClickListener { onServerButton() }
        findViewById<ExtendedFloatingActionButton>(R.id.send_button).setOnClickListener { onSend() }

        refreshStatus()
    }

    private fun onServerButton() {
        if (llama.health()) {
            setStatus("server already running on :${server.port}")
            return
        }
        setStatus("starting ${server.flavorName} server…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { startServerWithRetry() }
            if (ok) setStatus("server up on :${server.port} — ${server.modelStatus()}")
            else setStatus("server did not come up. ${server.modelStatus()}")
        }
    }

    private fun startServerWithRetry(): Boolean {
        server.ensureStarted { runOnUiThread { setStatus(it) } }
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
        if (!llama.health()) {
            Toast.makeText(this, "Start the server first", Toast.LENGTH_SHORT).show()
            onServerButton()
            return
        }
        input.setText("")
        busy = true

        history.add(ChatMsg("user", text))
        adapter.submit(history.map { UiMsg(it.role, it.content) })
        store.append(ChatMsg("user", text))
        setStatus("thinking…")

        lifecycleScope.launch {
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

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) server.stop()
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
