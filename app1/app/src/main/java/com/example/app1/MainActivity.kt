package com.example.app1

import CustomSnackbar // 确保这个 import 存在且 CustomSnackbar 定义正确
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app1.ui.screens.AppContent // 确认 AppContent 导入正确
import com.example.app1.ui.theme.App1Theme // 确认你的主题导入正确

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App1Theme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = 80.dp) // Adjust as needed
                        ) { snackbarData ->
                            CustomSnackbar(snackbarData = snackbarData)
                            // Or use default: Snackbar(snackbarData = snackbarData)
                        }
                    }
                ) { innerPadding -> // Receive innerPadding from Scaffold
                    AppContent(
                        innerPadding = innerPadding, // <<< PASS innerPadding HERE
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
    }
}