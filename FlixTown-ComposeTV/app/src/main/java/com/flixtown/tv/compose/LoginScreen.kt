package com.flixtown.tv.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(repo: FlixRepository, onAuthenticated: (TvAccount) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pair by remember { mutableStateOf<PairCode?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { repo.startPair() }.onSuccess { pair = it }
            .onFailure { error = "QR login unavailable. You can sign in with your remote." }
    }
    LaunchedEffect(pair) {
        val current = pair ?: return@LaunchedEffect
        repeat(190) {
            delay(3_000)
            try {
                val account = repo.pollPair(current)
                if (account != null) { onAuthenticated(account); return@LaunchedEffect }
            } catch (e: Exception) {
                if (e.message?.contains("expired", true) == true) {
                    error = "Activation code expired. Restart the app for a new code."
                    return@LaunchedEffect
                }
            }
        }
    }
    Row(Modifier.fillMaxSize().background(Color(0xFF090C16))
        .padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("FLIX TOWN", color = Color(0xFFFF527C), fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Text("Sign in", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            LoginField(username, "Username", false) { username = it }
            Spacer(Modifier.height(12.dp))
            LoginField(password, "Password", true) { password = it }
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                if (busy) return@Button
                if (username.isBlank() || password.isBlank()) { error = "Enter both account fields"; return@Button }
                busy = true; error = ""
                scope.launch {
                    try { onAuthenticated(repo.signIn(username, password)) }
                    catch (e: Exception) { error = e.message ?: "Sign in failed" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Checking…" else "Sign in", fontSize = 18.sp) }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(error, color = Color(0xFFFF9CB2), fontSize = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("Activate with your phone", color = Color.White, fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Scan the QR code, then enter your account on your phone.",
                color = Color(0xFFC5CCDA), fontSize = 16.sp)
            Spacer(Modifier.height(18.dp))
            val bitmap = remember(pair?.activationUrl) { pair?.activationUrl?.let(::qrBitmap) }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "Flix Town activation QR code",
                    modifier = Modifier.size(190.dp).background(Color.White, RoundedCornerShape(12.dp))
                        .padding(10.dp))
            }
            Spacer(Modifier.height(15.dp))
            Text(pair?.let { "Code: ${it.code}  •  myflixtown.com/activate.php" }
                ?: "Preparing activation code…", color = Color(0xFFD2D8E5), fontSize = 16.sp)
        }
    }
}

@Composable
private fun LoginField(value: String, hint: String, secret: Boolean, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Color(0xFFFF527C)),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .background(Color(0xFF1C2332), RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 1.dp,
                if (focused) Color(0xFFFF527C) else Color(0xFF465069), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = Color(0xFFAAB3C5), fontSize = 18.sp)
                inner()
            }
        })
}

private fun qrBitmap(url: String): Bitmap {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 260, 260)
    return Bitmap.createBitmap(260, 260, Bitmap.Config.RGB_565).also { bitmap ->
        for (y in 0 until 260) for (x in 0 until 260)
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
