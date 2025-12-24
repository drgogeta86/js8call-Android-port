package com.js8call.example.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.js8call.example.R
import com.js8call.example.model.TransmitState
import com.js8call.example.service.JS8EngineService

/**
 * Fragment for composing and transmitting messages.
 */
class TransmitFragment : Fragment() {

    private lateinit var viewModel: TransmitViewModel

    private lateinit var messageEditText: TextInputEditText
    private lateinit var directedEditText: TextInputEditText
    private lateinit var cqButton: Button
    private lateinit var sendButton: Button
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var queueEmptyText: TextView
    private lateinit var txStatusText: TextView
    private lateinit var speedSelect: AutoCompleteTextView
    private var selectedSubmode: Int = SUBMODE_NORMAL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_transmit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[TransmitViewModel::class.java]

        // Find views
        messageEditText = view.findViewById(R.id.message_edit_text)
        directedEditText = view.findViewById(R.id.directed_edit_text)
        cqButton = view.findViewById(R.id.cq_button)
        sendButton = view.findViewById(R.id.send_button)
        queueRecyclerView = view.findViewById(R.id.queue_recycler_view)
        queueEmptyText = view.findViewById(R.id.queue_empty_text)
        txStatusText = view.findViewById(R.id.tx_status_text)
        speedSelect = view.findViewById(R.id.tx_speed_select)

        // Set up listeners
        setupListeners()
        setupSpeedSelector()

        // Observe ViewModel
        observeViewModel()
    }

    private fun setupListeners() {
        // Message text changes
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setComposedMessage(s?.toString() ?: "")
                updateSendButtonState()
            }
        })

        // Directed callsign changes
        directedEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setDirectedTo(s?.toString() ?: "")
            }
        })

        // CQ button
        cqButton.setOnClickListener {
            viewModel.sendCQ()
            Snackbar.make(requireView(), "CQ queued", Snackbar.LENGTH_SHORT).show()
        }

        // Send button
        sendButton.setOnClickListener {
            sendMessage()
        }
    }

    private fun setupSpeedSelector() {
        val speedOptions = listOf(
            SpeedOption(getString(R.string.tx_speed_slow), SUBMODE_SLOW),
            SpeedOption(getString(R.string.tx_speed_normal), SUBMODE_NORMAL),
            SpeedOption(getString(R.string.tx_speed_fast), SUBMODE_FAST),
            SpeedOption(getString(R.string.tx_speed_turbo), SUBMODE_TURBO)
        )

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            speedOptions.map { it.label }
        )
        speedSelect.setAdapter(adapter)

        val defaultIndex = speedOptions.indexOfFirst { it.submode == SUBMODE_NORMAL }
            .takeIf { it >= 0 } ?: 0
        speedSelect.setText(speedOptions[defaultIndex].label, false)
        selectedSubmode = speedOptions[defaultIndex].submode

        speedSelect.setOnItemClickListener { _, _, position, _ ->
            selectedSubmode = speedOptions.getOrNull(position)?.submode ?: SUBMODE_NORMAL
        }
    }

    private fun observeViewModel() {
        // Observe composed message
        viewModel.composedMessage.observe(viewLifecycleOwner) { text ->
            if (text.isEmpty() && messageEditText.text?.toString()?.isNotEmpty() == true) {
                messageEditText.text?.clear()
            }
        }

        // Observe TX state
        viewModel.txState.observe(viewLifecycleOwner) { state ->
            txStatusText.text = when (state) {
                TransmitState.IDLE -> getString(R.string.tx_status_idle)
                TransmitState.QUEUED -> getString(R.string.tx_status_queued)
                TransmitState.TRANSMITTING -> getString(R.string.tx_status_transmitting)
            }
        }

        // Observe queue
        viewModel.queue.observe(viewLifecycleOwner) { queue ->
            if (queue.isEmpty()) {
                queueRecyclerView.visibility = View.GONE
                queueEmptyText.visibility = View.VISIBLE
            } else {
                queueRecyclerView.visibility = View.VISIBLE
                queueEmptyText.visibility = View.GONE
                // TODO: Update adapter with queue
            }
        }
    }

    private fun updateSendButtonState() {
        val hasText = messageEditText.text?.isNotBlank() == true
        sendButton.isEnabled = hasText
    }

    private fun sendMessage() {
        val text = messageEditText.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            Snackbar.make(requireView(), "Enter a message", Snackbar.LENGTH_SHORT).show()
            return
        }

        val directed = directedEditText.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        // Queue the message
        viewModel.queueMessage(text, directed)

        // Trigger native TX via the engine service
        val txIntent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_TRANSMIT_MESSAGE
            putExtra(JS8EngineService.EXTRA_TX_TEXT, text)
            putExtra(JS8EngineService.EXTRA_TX_SUBMODE, selectedSubmode)
            if (directed != null) {
                putExtra(JS8EngineService.EXTRA_TX_DIRECTED, directed)
            }
        }
        requireContext().startService(txIntent)

        // Show confirmation
        val message = if (directed != null) {
            "Message queued for $directed"
        } else {
            "Message queued"
        }
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    private data class SpeedOption(val label: String, val submode: Int)

    companion object {
        private const val SUBMODE_NORMAL = 0
        private const val SUBMODE_FAST = 1
        private const val SUBMODE_TURBO = 2
        private const val SUBMODE_SLOW = 4
    }
}
