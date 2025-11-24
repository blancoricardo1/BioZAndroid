package com.example.bioz

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.bioz.ui.BleApp
import com.example.bioz.ui.theme.BioZTheme

class MainActivity : ComponentActivity() {

    private val bleViewModel: BleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val requiredPermissions = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            var permissionsGranted by remember { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                permissionsGranted = results.values.all { it }
                if (permissionsGranted) {
                    bleViewModel.startScan()
                }
            }

            LaunchedEffect(Unit) {
                permissionsGranted = requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (!permissionsGranted) {
                    permissionLauncher.launch(requiredPermissions)
                }
            }

            LaunchedEffect(permissionsGranted) {
                if (permissionsGranted) {
                    bleViewModel.startScan()
                }
            }

            BioZTheme {
                BleApp(
                    viewModel = bleViewModel,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
                )
            }
        }
    }
}
