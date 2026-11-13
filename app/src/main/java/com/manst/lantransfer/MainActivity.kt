package com.manst.lantransfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.manst.lantransfer.ui.LanTransferApp
import com.manst.lantransfer.ui.theme.LanTransferTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LanTransferApplication).container
        setContent {
            LanTransferTheme {
                LanTransferApp(container)
            }
        }
    }
}
