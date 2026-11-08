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

/**
 * Saves one street's world as JSON in app storage: the single-player street in `world.json`,
 * shared streets in `streets/<user id>/<street id>.json` (per account, so two accounts on one
 * phone don't share a save).
 */
class WorldRepository(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "world.json"))

    fun exists(): Boolean = file.exists()

    fun delete() {
        file.delete()
    }

    fun load(): WorldState = try {
        if (file.exists()) WorldCodec.decode(file.readText()) else WorldFactory.newWorld()
    } catch (e: Exception) {
        Log.e("WorldRepository", "Couldn't read saved world, starting fresh", e)
        runCatching { file.copyTo(File(file.parentFile, file.nameWithoutExtension + ".corrupt.json"), overwrite = true) }
        WorldFactory.newWorld()
    }

    /** Writes atomically so a crash mid-save can't corrupt the world. */
    @Synchronized
    fun save(world: WorldState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(WorldCodec.encode(world))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        fun forSharedStreet(context: Context, userId: String, streetId: String) =
            WorldRepository(File(context.filesDir, "streets/$userId/$streetId.json"))
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

    /** The PNG bytes, for uploading. Call off the main thread. */
    fun readBytes(id: String): ByteArray? = fileFor(id).takeIf { it.exists() }?.readBytes()

    /** Saves a downloaded PNG. Call off the main thread. */
    fun writeBytes(id: String, png: ByteArray) {
        fileFor(id).writeBytes(png)
    }

    fun deleteFile(id: String) {
        fileFor(id).delete()
    }
}
