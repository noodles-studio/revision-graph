package io.github.noodles_studio.revisiongraph

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.RevisionGraphBundle"

internal object RevisionGraphBundle : DynamicBundle(BUNDLE) {
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
