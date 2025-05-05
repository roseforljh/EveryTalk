package com.example.app1.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.app1.data.local.SharedPreferencesDataSource

/**
 * Factory for creating AppViewModel instances with the necessary DataSource dependency.
 */
class AppViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            // Create the single DataSource instance needed
            val dataSource = SharedPreferencesDataSource(context.applicationContext)
            @Suppress("UNCHECKED_CAST")
            // AppViewModel constructor now takes the dataSource
            return AppViewModel(dataSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}