package com.zionhuang.music.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.annotation.MenuRes
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.navigation.NavigationView
import com.zionhuang.music.R
import java.io.Serializable

typealias MenuModifier = Menu.() -> Unit
typealias MenuItemClickListener = (MenuItem) -> Unit

class MenuBottomSheetDialogFragment : BottomSheetDialogFragment() {
    @MenuRes
    private var menuResId: Int = 0
    private var menuModifier: MenuModifier? = null
    private var onMenuItemClicked: MenuItemClickListener? = null

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        menuResId = requireArguments().getInt(KEY_MENU_RES_ID, 0)
        menuModifier = requireArguments().getSerializable(KEY_MENU_MODIFIER) as? MenuModifier
        onMenuItemClicked = requireArguments().getSerializable(KEY_MENU_LISTENER) as? MenuItemClickListener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.menu_bottom_sheet_dialog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<NavigationView>(R.id.navigation_view).apply {
            inflateMenu(menuResId)
            menuModifier?.invoke(menu)
            setNavigationItemSelectedListener {
                onMenuItemClicked?.invoke(it)
                dismiss()
                true
            }
        }
    }

    fun setMenuModifier(menuModifier: MenuModifier): MenuBottomSheetDialogFragment {
        requireArguments().putSerializable(KEY_MENU_MODIFIER, menuModifier as Serializable)
        return this
    }

    fun setOnMenuItemClickListener(listener: MenuItemClickListener): MenuBottomSheetDialogFragment {
        requireArguments().putSerializable(KEY_MENU_LISTENER, listener as Serializable)
        return this
    }

    companion object {
        private const val KEY_MENU_RES_ID = "MENU_RES_ID"
        private const val KEY_MENU_MODIFIER = "MENU_MODIFIER"
        private const val KEY_MENU_LISTENER = "LISTENER"

        fun newInstance(@MenuRes menuResId: Int) = MenuBottomSheetDialogFragment().apply {
            arguments = bundleOf(KEY_MENU_RES_ID to menuResId)
        }

    }
}