package com.blockparty.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.blockparty.core.model.WorldState
import com.blockparty.core.world.WorldCodec
import com.blockparty.core.world.WorldFactory
import java.io.File

/** Saves the single-player world as JSON in app storage. */
class WorldRepository(context: Context) {
    private val file = File(context.filesDir, "world.json")

    fun load(): WorldState = try {
        if (file.exists()) WorldCodec.decode(file.readText()) else WorldFactory.newWorld()
    } catch (e: Exception) {
        Log.e("WorldRepository", "Couldn't read saved world, starting fresh", e)
        runCatching { file.copyTo(File(file.parentFile, "world.corrupt.json"), overwrite = true) }
        WorldFactory.newWorld()
    }

    /** Writes atomically so a crash mid-save can't corrupt the world. */
    @Synchronized
    fun save(world: WorldState) {
        val tmp = File(file.parentFile, "world.json.tmp")
        tmp.writeText(WorldCodec.encode(world))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Stores player drawings as transparent PNGs and keeps decoded copies in memory.
 * [images] is Compose state (update it on the main thread), so the street redraws as
 * drawings finish loading.
 */
class SpriteStore(context: Context) {
    private val dir = File(context.filesDir, "sprites").apply { mkdirs() }

    val images = mutableStateMapOf<String, ImageBitmap>()

    private fun fileFor(id: String) = File(dir, "$id.png")

    /** Writes a sprite PNG. Call off the main thread. */
    fun writeFile(id: String, bitmap: Bitmap) {
        fileFor(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Decodes a sprite from disk. Call off the main thread. */
    fun decode(id: String): ImageBitmap? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    }

    fun deleteFile(id: String) {
        fileFor(id).delete()
    }
}
