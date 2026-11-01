package fr.tvluncher.launcher.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.tvluncher.launcher.R
import fr.tvluncher.launcher.data.AppRepository
import fr.tvluncher.launcher.data.Prefs
import fr.tvluncher.launcher.data.Profile
import fr.tvluncher.launcher.databinding.ActivityProfilesBinding
import fr.tvluncher.launcher.util.FocusEffects

/**
 * "Who's watching?": pick a profile, or long-press one to choose which apps it
 * sees, rename it, recolour it or delete it. Picking one returns home, which
 * re-renders from the new profile's dock and visible apps.
 */
class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilesBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)
        render(focusId = prefs.currentProfileId)
    }

    private fun render(focusId: String? = null) {
        val row = binding.profileRow
        row.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val current = prefs.currentProfileId
        var toFocus: View? = null

        for (profile in prefs.profiles) {
            val item = inflater.inflate(R.layout.item_profile, row, false)
            bindAvatar(item, profile.initial, profile.colorValue, profile.name)
            item.findViewById<TextView>(R.id.badge).text =
                if (profile.id == current) getString(R.string.profile_current) else ""
            item.tag = profile.id
            item.setOnClickListener {
                prefs.currentProfileId = profile.id
                finish()
            }
            item.setOnLongClickListener {
                showMenu(profile)
                true
            }
            row.addView(item)
            if (profile.id == focusId) toFocus = item
        }

        if (prefs.profiles.size < MAX_PROFILES) {
            val add = inflater.inflate(R.layout.item_profile, row, false)
            bindAvatar(add, "+", Profile.PALETTE.last(), getString(R.string.profiles_add))
            add.setOnClickListener { addProfile() }
            row.addView(add)
        }

        val target = toFocus ?: row.getChildAt(0)
        target?.post { target.requestFocus() }
    }

    private fun bindAvatar(item: View, initial: String, color: Int, name: String) {
        item.findViewById<TextView>(R.id.avatar).apply {
            text = initial
            backgroundTintList = ColorStateList.valueOf(color)
        }
        item.findViewById<TextView>(R.id.name).text = name
        item.contentDescription = name
        FocusEffects.bind(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val id = currentFocus?.tag as? String
            val profile = prefs.profiles.firstOrNull { it.id == id }
            if (profile != null) {
                showMenu(profile)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showMenu(profile: Profile) {
        val items = buildList {
            add(Sheet.Item(getString(R.string.profile_apps), R.drawable.ic_eye) { showAppPicker(profile) })
            add(Sheet.Item(getString(R.string.profile_rename), R.drawable.ic_title) { rename(profile) })
            add(Sheet.Item(getString(R.string.profile_color), R.drawable.ic_palette) {
                prefs.updateProfile(profile.copy(color = profile.color + 1))
                render(focusId = profile.id)
            })
            if (prefs.profiles.size > 1) {
                add(Sheet.Item(getString(R.string.profile_delete), R.drawable.ic_delete) { confirmDelete(profile) })
            }
        }
        Sheet.show(this, profile.name, items = items)
    }

    private fun addProfile() {
        val profile = prefs.addProfile(getString(R.string.profile_new_name, prefs.profiles.size + 1))
        render(focusId = profile.id)
        rename(profile)
    }

    private fun rename(profile: Profile) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_profile_name, null, false)
        val input = view.findViewById<EditText>(R.id.nameInput)
        input.setText(profile.name)
        input.setSelection(input.text.length)

        val dialog = AlertDialog.Builder(this, R.style.Theme_TvLuncher_Dialog).setView(view).create()
        fun save() {
            val name = input.text.toString().trim().take(Profile.MAX_NAME)
            if (name.isNotEmpty()) prefs.updateProfile(profile.copy(name = name))
            dialog.dismiss()
            render(focusId = profile.id)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun confirmDelete(profile: Profile) {
        Sheet.show(
            this,
            getString(R.string.profile_delete_confirm, profile.name),
            items = listOf(
                Sheet.Item(getString(R.string.profile_delete), R.drawable.ic_delete) {
                    prefs.removeProfile(profile.id)
                    render(focusId = prefs.currentProfileId)
                },
                Sheet.Item(getString(R.string.cancel)) { },
            ),
        )
    }

    /** Ticks are "visible for this profile"; unticking hides the app for it only. */
    private fun showAppPicker(profile: Profile) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null, false)
        view.findViewById<TextView>(R.id.pickerTitle).text =
            getString(R.string.profile_apps) + " · " + profile.name
        val list = view.findViewById<RecyclerView>(R.id.pickerList)
        val adapter = SettingsAdapter { prefs.animationsEnabled }
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter

        val apps = AppRepository.get(this).apps.value
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }

        fun rebuild() {
            val hidden = prefs.hiddenFor(profile.id)
            adapter.submit(apps.map { app ->
                SettingRow.Toggle(title = app.label, checked = app.packageName !in hidden) { visible ->
                    val next = prefs.hiddenFor(profile.id).toMutableSet()
                    if (visible) next.remove(app.packageName) else next.add(app.packageName)
                    prefs.setHiddenFor(profile.id, next)
                    rebuild()
                }
            })
        }
        rebuild()

        AlertDialog.Builder(this, R.style.Theme_TvLuncher_Dialog).setView(view).create().show()
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    companion object {
        private const val MAX_PROFILES = 6
    }
}
