package org.autojs.autojs.mcp

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import org.autojs.autojs6.R

class PhoneMcpToolListFragment : Fragment(R.layout.fragment_phone_mcp_tool_table) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val readOnly = requireArguments().getBoolean(ARG_READ_ONLY)
        val table = view.findViewById<TableLayout>(R.id.phone_mcp_tool_table)
        table.addView(headerRow())
        PhoneToolSpecs.all.filter { it.readOnly == readOnly }.forEach { table.addView(toolRow(it)) }
        resetHorizontalScroll()
    }

    override fun onResume() {
        super.onResume()
        resetHorizontalScroll()
    }

    private fun resetHorizontalScroll() {
        view?.findViewById<HorizontalScrollView>(R.id.phone_mcp_tool_horizontal_scroll)?.apply {
            isSaveEnabled = false
            post { scrollTo(0, 0) }
        }
    }

    private fun headerRow() = TableRow(requireContext()).apply {
        addView(cell(getString(R.string.phone_mcp_tool_column_name), WIDTH_NAME, true))
        addView(cell(getString(R.string.phone_mcp_tool_column_description), WIDTH_DESCRIPTION, true))
        addView(cell(getString(R.string.phone_mcp_tool_column_arguments), WIDTH_ARGUMENTS, true))
        addView(cell(getString(R.string.phone_mcp_tool_column_attributes), WIDTH_ATTRIBUTES, true))
    }

    private fun toolRow(tool: PhoneToolSpec) = TableRow(requireContext()).apply {
        addView(cell(tool.name, WIDTH_NAME))
        addView(cell(tool.description, WIDTH_DESCRIPTION))
        addView(cell(argumentSummary(tool), WIDTH_ARGUMENTS))
        addView(cell(attributeSummary(tool), WIDTH_ATTRIBUTES))
    }

    private fun attributeSummary(tool: PhoneToolSpec): String {
        return buildList {
            add(getString(if (tool.readOnly) R.string.phone_mcp_tool_read_only else R.string.phone_mcp_tool_write))
            if (tool.idempotent) add(getString(R.string.phone_mcp_tool_idempotent))
            if (tool.destructive) add(getString(R.string.phone_mcp_tool_destructive))
            if (tool.openWorld) add(getString(R.string.phone_mcp_tool_open_world))
        }.joinToString(" · ")
    }

    private fun argumentSummary(tool: PhoneToolSpec): String {
        val required = tool.inputSchema.getAsJsonArray("required")?.map { it.asString }?.toSet().orEmpty()
        val arguments = tool.inputSchema.getAsJsonObject("properties")?.keySet().orEmpty().map {
            if (it in required) "$it*" else it
        }
        return if (arguments.isEmpty()) {
            getString(R.string.phone_mcp_tool_no_arguments)
        } else if (required.isEmpty()) {
            getString(R.string.phone_mcp_tool_optional_arguments, arguments.joinToString(", "))
        } else {
            getString(R.string.phone_mcp_tool_arguments, arguments.joinToString(", "))
        }
    }

    private fun cell(text: String, widthDp: Int, header: Boolean = false) = TextView(requireContext()).apply {
        this.text = text
        isFocusable = false
        setPadding(dp(8), dp(7), dp(8), dp(7))
        minHeight = dp(40)
        if (header) setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(if (header) R.drawable.bg_phone_mcp_table_header else R.drawable.bg_phone_mcp_table_cell)
        layoutParams = TableRow.LayoutParams(dp(widthDp), TableRow.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_READ_ONLY = "read_only"
        private const val WIDTH_NAME = 180
        private const val WIDTH_DESCRIPTION = 360
        private const val WIDTH_ARGUMENTS = 230
        private const val WIDTH_ATTRIBUTES = 190

        fun newInstance(readOnly: Boolean) = PhoneMcpToolListFragment().apply {
            arguments = bundleOf(ARG_READ_ONLY to readOnly)
        }
    }
}
