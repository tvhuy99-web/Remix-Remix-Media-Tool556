package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    onNavigateToRecord: () -> Unit,
    onNavigateToTrim: () -> Unit,
    onNavigateToJoin: () -> Unit,
    onNavigateToMix: () -> Unit,
    onNavigateToImg2Vid: () -> Unit,
    onNavigateToSub: () -> Unit,
    onNavigateToStem: () -> Unit,
    onNavigateToOther: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CÔNG CỤ XỬ LÝ MEDIA (AUDIO & VIDEO)",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00A0FF),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )

            Button(
                onClick = onNavigateToRecord,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(contentColor = Color(0xFFFF0000))
            ) {
                Text("🎙 GHI ÂM TRỰC TIẾP")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToTrim,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("✂ CẮT ĐA ĐOẠN")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToJoin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔗 NỐI NHIỀU FILE")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToMix,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(contentColor = Color(0xFFDD0000))
            ) {
                Text("🎵 GHÉP NHẠC & TỰ ĐỘNG GIẢM NỀN")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToOther,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("⚙ TÍNH NĂNG KHÁC (HIỆU ỨNG/TRÍCH XUẤT)")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToImg2Vid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(contentColor = Color(0xFF00AA00))
            ) {
                Text("🖼 GHÉP ẢNH VÀO ÂM THANH")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToSub,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(contentColor = Color(0xFF8800FF))
            ) {
                Text("📝 ĐỌC & TRÍCH XUẤT PHỤ ĐỀ")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onNavigateToStem,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(contentColor = Color(0xFFFFA500))
            ) {
                Text("🎙 TÁCH NHẠC VÀ LỜI (AI TRÊN MÁY)")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🛠 CÀI ĐẶT CHUNG")
            }
        }
    }
}
