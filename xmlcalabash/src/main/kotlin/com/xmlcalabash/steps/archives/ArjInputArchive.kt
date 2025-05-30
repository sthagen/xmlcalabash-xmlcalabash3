package com.xmlcalabash.steps.archives

import com.xmlcalabash.config.StepConfiguration
import com.xmlcalabash.documents.XProcBinaryDocument
import com.xmlcalabash.exceptions.XProcError
import com.xmlcalabash.io.MediaType
import com.xmlcalabash.namespace.Ns
import com.xmlcalabash.namespace.NsCx
import net.sf.saxon.s9api.QName
import org.apache.commons.compress.archivers.arj.ArjArchiveEntry
import org.apache.commons.compress.archivers.arj.ArjArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.attribute.FileTime
import kotlin.io.path.inputStream

open class ArjInputArchive(stepConfig: StepConfiguration, doc: XProcBinaryDocument): InputArchive(stepConfig, doc) {
    override val archiveFormat = Ns.arj
    override val baseUri = doc.baseURI
    lateinit var archive: ArjArchiveInputStream

    protected fun openArj() {
        openArchive(".arj")
        archive = ArjArchiveInputStream(BufferedInputStream(archiveFile.inputStream()))
        var index = 0
        for (entry in archive) {
            if (entry.isDirectory) {
                continue
            }

            val archiveEntry = XArchiveEntry(stepConfig, entry.name, entry, this)
            val amap = mutableMapOf<QName, String>(
                Ns.name to entry.name,
                Ns.contentType to "${MediaType.parse(stepConfig.documentManager.mimetypesFileTypeMap.getContentType(entry.name))}",
                NsCx.size to entry.size.toString(),
                NsCx.windowsAttributes to entry.mode.toString(),
            )
            if (entry.mode != 0) {
                amap[NsCx.mode] = entry.unixMode.toString()
                amap[NsCx.modeString] = unixModeString(entry.unixMode)
            }
            if (entry.lastModifiedDate != null) {
                amap[NsCx.lastModified] = iso8601(FileTime.from(entry.lastModifiedDate.toInstant()))
            }
            when (entry.hostOs) {
                ArjArchiveEntry.HostOs.AMIGA -> amap[NsCx.hostOs] = "Amiga"
                ArjArchiveEntry.HostOs.APPLE_GS -> amap[NsCx.hostOs] = "Apple IIGS"
                ArjArchiveEntry.HostOs.ATARI_ST -> amap[NsCx.hostOs] = "Atari ST"
                ArjArchiveEntry.HostOs.DOS -> amap[NsCx.hostOs] = "DOS"
                ArjArchiveEntry.HostOs.MAC_OS -> amap[NsCx.hostOs] = "MacOS"
                ArjArchiveEntry.HostOs.NEXT -> amap[NsCx.hostOs] = "Next"
                ArjArchiveEntry.HostOs.OS_2 -> amap[NsCx.hostOs] = "OS/2"
                ArjArchiveEntry.HostOs.PRIMOS -> amap[NsCx.hostOs] = "PRIMOS"
                ArjArchiveEntry.HostOs.UNIX -> amap[NsCx.hostOs] = "Unix"
                ArjArchiveEntry.HostOs.VAX_VMS -> amap[NsCx.hostOs] = "VAX/VMS"
                ArjArchiveEntry.HostOs.WIN32 -> amap[NsCx.hostOs] = "Windows"
                ArjArchiveEntry.HostOs.WIN95 -> amap[NsCx.hostOs] = "Windows95"
                else -> Unit
            }

            archiveEntry.properties.putAll(amap)
            archiveEntry.position = index++
            _entries.add(archiveEntry)
        }
        archive.close()
    }

    override fun open() {
        try {
            openArj()
        } catch (ex: Exception) {
            throw stepConfig.exception(XProcError.xcInvalidArchiveFormat(Ns.arj), ex)
        }
    }

    override fun close() {
        _entries.clear()
    }

    override fun inputStream(entry: XArchiveEntry): InputStream {
        // I can't see any other way to do this...I could be clever about keeping the
        // stream open and all, but for ar archives, it doesn't seem worth it
        openArchive(".ar")
        archive = ArjArchiveInputStream(BufferedInputStream(archiveFile.inputStream()))

        for (arEntry in archive) {
            if (arEntry.isDirectory) {
                continue
            }
            if (arEntry.name == entry.name) {
                val stream = ByteArrayInputStream(archive.readNBytes(arEntry.size.toInt()))
                archive.close()
                return stream
            }
        }

        archive.close()
        throw IllegalStateException("${entry.name} not found")
    }
}