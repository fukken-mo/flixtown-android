package com.nimertv.app.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage

/** Remembers which item of a screen had focus so it can be restored when coming back. */
class FocusMemory(var key: Any? = null)

/**
 * Restores focus to the item whose [key] was focused last on this screen, and records
 * focus as the user moves around.
 */
@Composable
fun Modifier.rememberFocus(memory: FocusMemory, key: Any): Modifier {
    val requester = remember { FocusRequester() }
    if (memory.key == key) {
        LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.key = key }
}

private fun isConfirm(code: Int) =
    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * The single focusable building block of the app. Handles remote OK (click), a held OK or the
 * Menu key (long click), and draws the shared focus treatment: gentle scale, purple outline
 * and a soft shadow.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.06f,
    background: Color = Palette.Surface,
    focusedBackground: Color = Palette.SurfaceHigh,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(180), label = "focusScale")

    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused() else pressed = false
            }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                when {
                    isConfirm(native.keyCode) -> {
                        if (event.type == KeyEventType.KeyDown) {
                            if (native.repeatCount == 0) {
                                pressed = true
                                longFired = false
                            } else if (pressed && !longFired && onLongClick != null) {
                                longFired = true
                                onLongClick()
                            }
                        } else if (event.type == KeyEventType.KeyUp) {
                            // Only a press that started on this item counts, so the key-up of the
                            // press that opened a screen cannot click through.
                            if (pressed && !longFired) onClick()
                            pressed = false
                        }
                        true
                    }
                    native.keyCode == KeyEvent.KEYCODE_MENU && onLongClick != null -> {
                        if (event.type == KeyEventType.KeyUp) onLongClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick?.invoke() })
            }
            .shadow(if (focused) 18.dp else 0.dp, shape, ambientColor = Palette.Purple, spotColor = Palette.Purple)
            .clip(shape)
            .background(if (focused) focusedBackground else background)
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.PurpleLight else Palette.Outline, shape),
    ) {
        content(focused)
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        background = if (primary) Palette.Purple.copy(alpha = if (enabled) 0.85f else 0.3f) else Palette.Surface,
        focusedBackground = if (primary) Palette.Purple else Palette.SurfaceHigh,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (focused || primary) Color.White else Palette.TextDim)
            Text(
                text,
                style = Type.Label.copy(fontWeight = FontWeight.SemiBold),
                color = if (focused || primary) Color.White else Palette.Text,
                maxLines = 1,
            )
        }
    }
}

/** Left-rail entry (categories, seasons). */
@Composable
fun RailItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    trailing: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.03f,
        background = if (selected) Palette.Purple.copy(alpha = 0.22f) else Color.Transparent,
        focusedBackground = Palette.Purple.copy(alpha = 0.55f),
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = Type.Label,
                color = if (focused || selected) Color.White else Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) Text(trailing, style = Type.Caption, color = if (focused) Color.White else Palette.TextFaint)
        }
    }
}

/** Provider artwork with a typographic fallback, so a missing or broken image never leaves a blank tile. */
@Composable
fun Artwork(url: String, title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier.background(Brush.linearGradient(listOf(Palette.SurfaceHigh, Palette.PurpleDeep.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                title,
                style = Type.Caption.copy(textAlign = TextAlign.Center),
                color = Palette.TextDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Channel logo: shown whole on a neutral tile, with the channel name as fallback. */
@Composable
fun LogoArt(url: String, title: String, modifier: Modifier = Modifier, inset: Dp = 10.dp) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!loaded) {
            Text(
                title,
                style = Type.Caption.copy(textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp),
            )
        }
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().padding(inset),
            )
        }
    }
}

/** 2:3 poster tile for movies and series. */
@Composable
fun PosterCard(
    title: String,
    poster: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    badge: String? = null,
    progress: Float? = null,
    favorite: Boolean = false,
) {
    Column(modifier) {
        FocusSurface(onClick = onClick, onLongClick = onLongClick, onFocused = onFocused, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) { focused ->
            Artwork(poster, title, Modifier.fillMaxSize())
            if (focused) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.Purple.copy(alpha = 0.45f))))
            }
            if (badge != null) {
                Text(
                    badge,
                    style = Type.Caption.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (favorite) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).background(Color(0xB3000000), CircleShape), contentAlignment = Alignment.Center) {
                    Text("★", color = Palette.Gold, style = Type.Caption)
                }
            }
            if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0x66FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(Palette.PurpleLight))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Palette.PurpleLight, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        if (label != null) {
            Spacer(Modifier.height(14.dp))
            Text(label, style = Type.Body, color = Palette.TextDim)
        }
    }
}

/** Full-area message with an optional retry button that takes focus. */
@Composable
fun Message(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val requester = remember { FocusRequester() }
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = Type.Title.copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(8.dp))
        Text(detail, style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.TextDim)
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(actionLabel, onAction, Modifier.focusRequester(requester), primary = true)
            LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
        }
    }
}

/**
 * Remote-friendly text field. It behaves like a button until OK is pressed, then opens the
 * on-screen keyboard; the D-pad is never trapped inside the text.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    width: Dp = 360.dp,
) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    val outer = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    FocusSurface(
        onClick = { editing = true },
        modifier = modifier.width(width).focusRequester(outer),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
    ) { focused ->
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (value.isEmpty()) Text(placeholder, style = Type.Body, color = Palette.TextFaint)
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = { onValueChange(it.replace("\n", "")) },
                    singleLine = true,
                    textStyle = Type.Body.copy(color = Color.White),
                    cursorBrush = SolidColor(Palette.PurpleLight),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onAny = {
                        keyboard?.hide()
                        editing = false
                        runCatching { outer.requestFocus() }
                        onImeAction()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inner)
                        .onFocusChanged {
                            // Leaving the text with the D-pad returns the field to "button" mode.
                            if (it.isFocused) hadFocus = true else if (hadFocus) {
                                hadFocus = false
                                editing = false
                            }
                        }
                        .onKeyEvent { event ->
                            // Back while typing returns to "button" mode instead of leaving the screen.
                            if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && event.type == KeyEventType.KeyUp) {
                                keyboard?.hide()
                                editing = false
                                runCatching { outer.requestFocus() }
                                true
                            } else {
                                false
                            }
                        },
                )
                LaunchedEffect(Unit) {
                    runCatching { inner.requestFocus() }
                    keyboard?.show()
                }
            } else {
                val shown = if (password) "•".repeat(value.length) else value
                Text(shown, style = Type.Body, color = if (focused) Color.White else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).background(Palette.Purple, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(title, style = Type.Section)
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            Text(hint, style = Type.Caption)
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp)
