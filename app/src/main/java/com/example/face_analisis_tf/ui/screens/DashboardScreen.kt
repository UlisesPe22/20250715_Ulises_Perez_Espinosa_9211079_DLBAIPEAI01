package com.example.face_analisis_tf.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.face_analisis_tf.utils.FaceAnalyzer
import kotlinx.coroutines.launch
import java.io.InputStream



private val RiojaRed = Color(0xFFA60616)

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var predictionResults by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { FaceAnalyzer.initializeInterpreter(context) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            predictionResults = emptyList()
            imageBitmap = null
            val stream: InputStream? = context.contentResolver.openInputStream(it)
            val original = BitmapFactory.decodeStream(stream)
            scope.launch { imageBitmap = FaceAnalyzer.processImageWithAnnotations(original) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "MoodBites AI",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                color = RiojaRed,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Where emotions meet flavor—powered by AI.",
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                color = Color.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { launcher.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = RiojaRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(44.dp)
            ) {
                Text(
                    "Upload Image",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            imageBitmap?.let { bmp ->
                Spacer(Modifier.height(24.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp, max = 500.dp)
                        .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        predictionResults = emptyList()
                        scope.launch { predictionResults = FaceAnalyzer.processImage(bmp) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RiojaRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(44.dp)
                ) {
                    Text(
                        "Analyze",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                if (predictionResults.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(predictionResults) { res ->
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = res.substringBefore(":"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = RiojaRed
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = res.substringAfter(":").trim(),
                                        fontSize = 12.sp,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}