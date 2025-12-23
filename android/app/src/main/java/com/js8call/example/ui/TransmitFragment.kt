package com.js8call.example.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.js8call.example.R
import com.js8call.example.model.TransmitState

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

        // Set up listeners
        setupListeners()

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

        // Show confirmation
        val message = if (directed != null) {
            "Message queued for $directed"
        } else {
            "Message queued"
        }
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }
}
