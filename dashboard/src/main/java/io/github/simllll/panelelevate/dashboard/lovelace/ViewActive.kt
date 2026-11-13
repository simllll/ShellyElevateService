package io.github.simllll.panelelevate.dashboard.lovelace

import androidx.compose.runtime.compositionLocalOf

/**
 * False inside a neighbouring view that [ViewSwitcher] keeps composed off-screen for swiping.
 * Things that must exist once per screen (floating buttons, hash pop-ups) or cost a decoder
 * (videos) only run in the active view.
 */
val LocalViewActive = compositionLocalOf { true }
