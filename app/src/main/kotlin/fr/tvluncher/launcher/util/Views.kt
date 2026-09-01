package fr.tvluncher.launcher.util

import android.content.Context
import android.util.TypedValue
import android.view.View

fun Context.dp(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
)

fun Context.dpInt(value: Float): Int = dp(value).toInt()

fun View.dp(value: Float): Float = context.dp(value)
