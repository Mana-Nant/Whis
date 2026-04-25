package com.example.whisperandroid.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileUtils {

    /** SAFで選択されたURIから表示名を取得 (不可なら "audio_<timestamp>") */
    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx) ?: defaultName()
            }
        }
        return defaultName()
    }

    private fun defaultName(): String = "audio_${System.currentTimeMillis()}"

    /**
     * content:// の音声URIをアプリのcacheDirにコピー。
     * FFmpegKitは content:// を直接扱えない (saf wrapperが必要) ため、ファイルパスに正規化する。
     */
    suspend fun copyUriToCache(context: Context, uri: Uri, fileName: String): File? =
        withContext(Dispatchers.IO) {
            val dest = File(context.cacheDir, "in_${System.currentTimeMillis()}_$fileName")
            runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                dest
            }.getOrNull()
        }

    /** SAF tree URI にテキストファイルを書き出す。 */
    suspend fun writeTextToTree(
        context: Context,
        treeUri: Uri,
        fileName: String,
        text: String
    ): Uri? = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
        // 同名ファイルがあれば上書き
        dir.findFile(fileName)?.delete()
        val newFile = dir.createFile("text/plain", fileName) ?: return@withContext null
        runCatching {
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            newFile.uri
        }.getOrNull()
    }
}
