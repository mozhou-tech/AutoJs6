package org.autojs.autojs.mcp

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.autojs.autojs.ui.main.ViewPagerFragment
import org.autojs.autojs6.R

class PhoneMcpFragment : ViewPagerFragment(ROTATION_GONE) {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            id = R.id.phone_mcp_fragment_container
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.phone_mcp_fragment_container, PhoneMcpSettingsActivity.SettingsFragment())
                .commit()
        }
    }

    override fun onFabClick(fab: FloatingActionButton) = Unit

    override fun onBackPressed(activity: Activity) = false
}
