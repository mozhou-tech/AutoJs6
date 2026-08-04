package org.autojs.autojs.mcp

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import org.autojs.autojs.ui.main.ViewPagerFragment
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.FragmentPhoneMcpBinding

class PhoneMcpFragment : ViewPagerFragment(ROTATION_GONE) {

    private var binding: FragmentPhoneMcpBinding? = null
    private var selectedPage = PAGE_READ_ONLY

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentPhoneMcpBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedPage = savedInstanceState?.getInt(STATE_SELECTED_PAGE, PAGE_READ_ONLY) ?: PAGE_READ_ONLY
        binding?.phoneMcpTabs?.apply {
            addTab(newTab().setText(R.string.phone_mcp_tab_read_only_tools))
            addTab(newTab().setText(R.string.phone_mcp_tab_control_tools))
            addTab(newTab().setText(R.string.phone_mcp_tab_settings))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectedPage = tab.position
                    showPage(tab.position)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            getTabAt(selectedPage)?.select()
        }
        if (childFragmentManager.findFragmentById(R.id.phone_mcp_fragment_container) == null) {
            showPage(selectedPage)
        }
    }

    private fun showPage(page: Int) {
        val tag = when (page) {
            PAGE_READ_ONLY -> TAG_READ_ONLY
            PAGE_CONTROL -> TAG_CONTROL
            else -> TAG_SETTINGS
        }
        val current = childFragmentManager.findFragmentById(R.id.phone_mcp_fragment_container)
        if (current?.tag == tag) return
        val fragment: Fragment = when (page) {
            PAGE_READ_ONLY -> PhoneMcpToolListFragment.newInstance(readOnly = true)
            PAGE_CONTROL -> PhoneMcpToolListFragment.newInstance(readOnly = false)
            else -> PhoneMcpSettingsActivity.SettingsFragment()
        }
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.phone_mcp_fragment_container, fragment, tag)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_PAGE, selectedPage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onFabClick(fab: FloatingActionButton) = Unit

    override fun onBackPressed(activity: Activity) = false

    companion object {
        private const val PAGE_READ_ONLY = 0
        private const val PAGE_CONTROL = 1
        private const val PAGE_SETTINGS = 2
        private const val STATE_SELECTED_PAGE = "phone_mcp_selected_page"
        private const val TAG_SETTINGS = "phone_mcp_settings"
        private const val TAG_READ_ONLY = "phone_mcp_tools_read_only"
        private const val TAG_CONTROL = "phone_mcp_tools_control"
    }
}
