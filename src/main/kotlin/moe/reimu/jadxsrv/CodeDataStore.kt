package moe.reimu.jadxsrv

import jadx.api.data.ICodeComment
import jadx.api.data.ICodeRename
import jadx.api.data.IJavaCodeRef
import jadx.api.data.IJavaNodeRef
import jadx.api.data.impl.JadxCodeComment
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxCodeRename
import jadx.api.data.impl.JadxCodeRef
import jadx.api.data.impl.JadxNodeRef
import jadx.core.utils.GsonUtils
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = LoggerFactory.getLogger("jadxsrv.codedata")

/**
 * Reads/writes code data (renames and comments) in the exact JSON format
 * jadx-gui embeds in .jadx project files (JadxProject.buildGson), so the file
 * can be cut-and-pasted into a GUI project and vice versa.
 */
private val codeDataGson = GsonUtils.defaultGsonBuilder()
    .registerTypeAdapter(ICodeComment::class.java, GsonUtils.interfaceReplace(JadxCodeComment::class.java))
    .registerTypeAdapter(ICodeRename::class.java, GsonUtils.interfaceReplace(JadxCodeRename::class.java))
    .registerTypeAdapter(IJavaNodeRef::class.java, GsonUtils.interfaceReplace(JadxNodeRef::class.java))
    .registerTypeAdapter(IJavaCodeRef::class.java, GsonUtils.interfaceReplace(JadxCodeRef::class.java))
    .create()

fun loadCodeData(path: Path): JadxCodeData {
    val data = Files.newBufferedReader(path).use { codeDataGson.fromJson(it, JadxCodeData::class.java) }
    logger.info("Loaded {} renames, {} comments from {}", data.renames.size, data.comments.size, path)
    return data
}

fun saveCodeData(path: Path, codeData: JadxCodeData) {
    // Write-then-move so a crash mid-save can't truncate the previous file.
    val tmp = path.resolveSibling("${path.fileName}.tmp")
    Files.newBufferedWriter(tmp).use { codeDataGson.toJson(codeData, it) }
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    logger.info("Saved {} renames, {} comments to {}", codeData.renames.size, codeData.comments.size, path)
}
