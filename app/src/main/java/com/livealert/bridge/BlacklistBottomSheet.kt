package com.livealert.bridge

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.livealert.bridge.databinding.BottomSheetBlacklistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlacklistBottomSheet(
    private val onDismissListener: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBlacklistBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: BridgePreferences
    private lateinit var adapter: AppPickerAdapter

    private var allApps: List<AppInfoItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBlacklistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = BridgePreferences(requireContext())

        adapter = AppPickerAdapter { item, isBlacklisted ->
            if (isBlacklisted) {
                preferences.addToBlacklist(item.packageName)
            } else {
                preferences.removeFromBlacklist(item.packageName)
            }
            updateSubtitle()
        }

        binding.rvAppList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppList.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnManualAdd.setOnClickListener {
            val query = binding.etSearch.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                preferences.addToBlacklist(query)
                binding.etSearch.text?.clear()
                loadInstalledApps()
            }
        }

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.rvAppList.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = requireContext().packageManager
            val myPackage = requireContext().packageName
            val blacklisted = preferences.blacklistedPackages

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = mutableListOf<AppInfoItem>()

            for (app in installed) {
                if (app.packageName == myPackage) continue
                // Include apps that have launch intents or are third-party / media apps
                val isLaunchable = pm.getLaunchIntentForPackage(app.packageName) != null
                val isNonSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                val isAlreadyBlacklisted = blacklisted.contains(app.packageName)

                if (isLaunchable || isNonSystem || isAlreadyBlacklisted) {
                    val label = pm.getApplicationLabel(app).toString()
                    val icon = try {
                        pm.getApplicationIcon(app)
                    } catch (e: Exception) {
                        null
                    }
                    appList.add(
                        AppInfoItem(
                            appName = label,
                            packageName = app.packageName,
                            icon = icon,
                            isBlacklisted = isAlreadyBlacklisted
                        )
                    )
                }
            }

            // Also add any blacklisted package that might not be in installed apps (custom package entries)
            for (pkg in blacklisted) {
                if (appList.none { it.packageName == pkg }) {
                    appList.add(
                        AppInfoItem(
                            appName = pkg,
                            packageName = pkg,
                            icon = null,
                            isBlacklisted = true
                        )
                    )
                }
            }

            // Sort: blacklisted items first, then alphabetical by label
            appList.sortWith(
                compareByDescending<AppInfoItem> { it.isBlacklisted }
                    .thenBy { it.appName.lowercase() }
            )

            withContext(Dispatchers.Main) {
                allApps = appList
                binding.progressBar.visibility = View.GONE
                binding.rvAppList.visibility = View.VISIBLE
                filterList(binding.etSearch.text?.toString()?.trim() ?: "")
                updateSubtitle()
            }
        }
    }

    private fun filterList(query: String) {
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(filtered)

        // If user typed a custom package that is not in the installed list, show manual add button
        val exactMatch = allApps.any { it.packageName.equals(query, ignoreCase = true) }
        if (query.isNotEmpty() && !exactMatch && query.contains(".")) {
            binding.btnManualAdd.visibility = View.VISIBLE
            binding.btnManualAdd.text = "Exclude \"$query\""
        } else {
            binding.btnManualAdd.visibility = View.GONE
        }
    }

    private fun updateSubtitle() {
        val count = preferences.blacklistedPackages.size
        binding.tvBlacklistSubtitle.text = if (count == 0) {
            "0 apps excluded (all media apps supported)"
        } else {
            "$count app${if (count > 1) "s" else ""} excluded from Live Alerts"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        onDismissListener()
    }
}
