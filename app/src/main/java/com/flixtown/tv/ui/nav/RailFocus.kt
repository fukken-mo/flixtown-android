package com.flixtown.tv.ui.nav

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * The FocusRequester for the nav rail's currently-active item. Screens with
 * a leftmost column/row of focusable content (poster grids, poster rows)
 * read this and attach `Modifier.focusProperties { left = it }` to their
 * leftmost items, so pressing Left there jumps straight to the rail and
 * (via the rail item's own onFocusChanged) reveals it — see
 * [com.flixtown.tv.ui.home.HomeShellScreen].
 *
 * A CompositionLocal instead of threading a parameter through every screen
 * signature: this is a cross-cutting concern shared by every browse screen,
 * not something each screen's own logic needs to reason about.
 */
val LocalRailRevealFocusRequester = compositionLocalOf<FocusRequester?> { null }
