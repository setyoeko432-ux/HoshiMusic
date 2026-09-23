package com.naomi.hoshimusic.ui.motion

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

import androidx.compose.animation.ExperimentalSharedTransitionApi

@OptIn(ExperimentalSharedTransitionApi::class)
internal object AppMotion {
    const val DURATION_SCREEN = 350
    const val DURATION_PLAYER = 400
    const val DURATION_FADE = 280
    val EASING: Easing = FastOutSlowInEasing

    val PlayerBoundsTransform = BoundsTransform { _, _ ->
        tween(durationMillis = DURATION_PLAYER, easing = EASING)
    }
}
