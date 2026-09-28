package com.flixtown.tv.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        CinemaColor.Surface, CinemaColor.Background, CinemaColor.Background)))
        .padding(horizontal = 48.dp, vertical = 27.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().widthIn(max = 1050.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(CinemaColor.Surface,
                CinemaColor.Surface.copy(alpha = 0.94f), CinemaColor.AccentDeep.copy(alpha = 0.18f))))
            .border(1.dp, CinemaColor.Accent.copy(alpha = 0.44f), RoundedCornerShape(22.dp))
            .padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("FLIX TOWN", color = CinemaColor.Accent, fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold)
                Text("Your screen. Your movies.", color = CinemaColor.Text,
                    fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Sign in with your account", color = CinemaColor.Muted, fontSize = 16.sp)
                LoginField(username, "Username", false) { username = it }
                LoginField(password, "Password", true) { password = it }
                PremiumButton(onClick = {
                    if (busy) return@PremiumButton
                    if (username.isBlank() || password.isBlank()) {
                        error = "Enter both account fields"; return@PremiumButton
                    }
                    busy = true; error = ""
                    scope.launch {
                        try { onAuthenticated(repo.signIn(username, password)) }
                        catch (e: Exception) { error = e.message ?: "Sign in failed" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Checking…" else "Sign in", fontSize = 18.sp) }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFA9A9),
                    fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("OR ACTIVATE WITH YOUR PHONE", color = CinemaColor.Accent,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val bitmap = remember(pair?.activationUrl) { pair?.activationUrl?.let(::qrBitmap) }
                Box(Modifier.size(190.dp)
                    .background(CinemaColor.Accent.copy(alpha = 0.12f), RoundedCornerShape(17.dp))
                    .border(2.dp, CinemaColor.Accent, RoundedCornerShape(17.dp))
                    .padding(9.dp), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap.asImageBitmap(),
                        contentDescription = "Flix Town activation QR code",
                        modifier = Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(9.dp))
                            .padding(8.dp))
                    else Text("Preparing code…", color = CinemaColor.Text, fontSize = 15.sp)
                }
                Text("1  Scan this code with your phone", color = CinemaColor.Text, fontSize = 16.sp)
                Text("2  Enter your username and password", color = CinemaColor.Muted, fontSize = 15.sp)
                Text(pair?.let { "Code ${it.code}  •  myflixtown.com/activate.php" }
                    ?: "Waiting for activation code", color = CinemaColor.Muted, fontSize = 14.sp,
                    maxLines = 2)
            }
        }
    }
}

@Composable
private fun LoginField(value: String, hint: String, secret: Boolean, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(CinemaColor.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .background(CinemaColor.Surface, RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 1.dp,
                if (focused) CinemaColor.Accent else Color(0xFF465069), RoundedCornerShape(10.dp))
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
