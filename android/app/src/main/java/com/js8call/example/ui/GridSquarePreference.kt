package com.js8call.example.ui

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.js8call.example.R
import java.util.Locale

class GridSquarePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    var onUpdateClickListener: (() -> Unit)? = null

    private var editText: EditText? = null

    init {
        layoutResource = R.layout.preference_grid_square
        isPersistent = true
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val edit = holder.findViewById(R.id.grid_edit) as? EditText
        val button = holder.findViewById(R.id.grid_update_button) as? Button

        if (edit != null) {
            editText = edit
            val stored = getPersistedString("").trim()
            val normalized = stored.uppercase(Locale.US)
            if (stored != normalized) {
                persistString(normalized)
            }
            edit.setText(normalized)
            edit.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    persistFromEdit()
                    true
                } else {
                    false
                }
            }
            edit.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    persistFromEdit()
                }
            }
        }

        button?.setOnClickListener { onUpdateClickListener?.invoke() }
        edit?.isEnabled = isEnabled
        button?.isEnabled = isEnabled
    }

    fun setGridValue(value: String) {
        val normalized = value.trim().uppercase(Locale.US)
        if (!callChangeListener(normalized)) return
        persistString(normalized)
        editText?.let { edit ->
            if (edit.text.toString() != normalized) {
                edit.setText(normalized)
            }
        }
        notifyChanged()
    }

    private fun persistFromEdit() {
        val edit = editText ?: return
        val normalized = edit.text.toString().trim().uppercase(Locale.US)
        if (!callChangeListener(normalized)) return
        if (edit.text.toString() != normalized) {
            edit.setText(normalized)
        }
        if (normalized != getPersistedString("")) {
            persistString(normalized)
        }
    }
}
