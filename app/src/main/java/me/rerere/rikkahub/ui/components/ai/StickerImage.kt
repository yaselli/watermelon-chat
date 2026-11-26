package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import me.rerere.rikkahub.data.model.ChatSticker
import me.rerere.rikkahub.data.files.FileFolders
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath

private object StickerImages {
    private var loader: ImageLoader? = null

    @Synchronized
    fun get(context: Context): ImageLoader = loader ?: SingletonImageLoader.get(context)
        .newBuilder()
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("sticker_images").toOkioPath())
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .build().also { loader = it }
}

internal fun stickerImageSource(context: Context, url: String): String =
    ChatSticker.localFilename(url)?.let { filename ->
        context.filesDir.resolve(FileFolders.UPLOAD).resolve(filename).toUri().toString()
    } ?: url

@Composable
internal fun StickerImage(url: String, label: String, modifier: Modifier = Modifier, inChat: Boolean = false) {
    var ratio by remember(url) { mutableFloatStateOf(1f) }
    val width = LocalWindowInfo.current.containerSize.width
    val edge = with(LocalDensity.current) { (width / 3f).toDp() }
    val size = if (inChat) Modifier.width(edge * minOf(1f, ratio)).height(edge / maxOf(1f, ratio)) else Modifier
    AsyncImage(
        model = stickerImageSource(LocalContext.current, url),
        imageLoader = StickerImages.get(LocalContext.current.applicationContext),
        contentDescription = label,
        modifier = size.then(modifier),
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            val image = state.result.image
            if (image.width > 0 && image.height > 0) ratio = image.width.toFloat() / image.height
        },
    )
}
