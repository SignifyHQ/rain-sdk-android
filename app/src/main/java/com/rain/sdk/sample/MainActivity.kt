package com.rain.sdk.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rain.sdk.RainSdk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SampleApp()
        }
    }
}

@Composable
fun SampleApp() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Rain SDK Sample")
        
        Button(onClick = { 
            // Test Utils logic here
            // val payload = RainSdk.Utils.buildWithdrawPayload(...)
        }) {
            Text(text = "Test Build Payload")
        }
    }
}
