package org.autojs.autojs.ui.log

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.autojs.autojs.AutoJs.Companion.instance
import org.autojs.autojs.core.console.GlobalConsole
import org.autojs.autojs.ui.fragment.BindingDelegates.viewBinding
import org.autojs.autojs.ui.main.ViewPagerFragment
import org.autojs.autojs6.databinding.FragmentLogBinding

class LogFragment : ViewPagerFragment(45) {

    private val binding by viewBinding(FragmentLogBinding::bind)
    private lateinit var console: GlobalConsole

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentLogBinding.inflate(inflater, container, false).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.console.apply {
            setConsole(instance.globalConsole.also { console = it })
            setPinchToZoomEnabled(true)
        }
    }

    override fun onFabClick(fab: FloatingActionButton) {
        console.clear()
    }

    override fun onBackPressed(activity: Activity) = false
}
