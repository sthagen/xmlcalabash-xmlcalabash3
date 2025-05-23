package com.xmlcalabash.util

import com.xmlcalabash.exceptions.XProcError
import java.net.URI
import java.nio.file.FileSystems

class Urify(filepath: String, basedir: String?) {
    constructor(filepath: String): this(filepath, null)

    companion object {
        private var _osname = System.getProperty("os.name") ?: "not-windows"
        private var _filesep = FileSystems.getDefault().getSeparator() ?: "/"
        private var _cwd: String? = null

        val filesep: String
            get() {
                return _filesep
            }

        val osname: String
            get() {
                return _osname
            }

        val isWindows: Boolean
            get() {
                return osname.startsWith("Windows")
            }

        val cwd: String
            get() {
                val dir = _cwd ?: System.getProperty("user.dir")
                if (dir.endsWith(filesep)) {
                    return dir
                } else {
                    return dir + filesep
                }
            }

        fun mockOs(name: String, sep: String, cwd: String?) {
            _osname = name
            _filesep = sep
            _cwd = cwd
        }

        private val windowsFilepathRE = "^(?i)(file:/*)?(?i)([a-z]):(.*)$".toRegex()
        private val fileAuthorityRE = "^(?i)(file://)([^/]+)(/.*)?$".toRegex()
        private val filepathRE = "^(?i)(file:)(.*)$".toRegex()
        private val otherSchemeRE = "^(?i)([-a-z0-9+.]+):(.*)$".toRegex()
        private val authorityRE = "^//([^/]+)(/.*)?$".toRegex()
        private val plainPathRE = "^[^:]+$".toRegex()

        fun urify(filestr: String): String {
            return urify(filestr, null)
        }

        fun urify(filestr: String, basedir: URI): String {
            return urify(filestr, basedir.toString())
        }

        fun urify(filestr: String, basedir: String?): String {
            var filepath = Urify(filestr, basedir)

            var suffix: String? = null
            var onlySuffix = false

            if (filepath.hierarchical) {
                val qpos = filestr.indexOf('?')
                val hpos = filestr.indexOf('#')
                val pos = if (qpos >= 0 && (hpos < 0 || qpos < hpos)) {
                    qpos
                } else {
                    hpos
                }

                if (pos >= 0) {
                    suffix = UriUtils.escapeHtmlUri(filestr.substring(pos))
                    onlySuffix = pos == 0
                    filepath = Urify(filestr.substring(0, pos), basedir)
                }
            }

            if (!filepath.hierarchical || (filepath.scheme != null && filepath.absolute)) {
                if (suffix == null) {
                    return filepath.toString()
                }
                return "${filepath}${suffix}"
            }

            val basepath = if (basedir == null) {
                Urify("file://${cwd}", "")
            } else {
                Urify(basedir, "")
            }

            if (!basepath.hierarchical) {
                throw XProcError.xdUrifyNonhierarchicalBase(filepath.toString(), basepath.toString()).exception()
            }

            if (!basepath.absolute) {
                throw XProcError.xdUrifyFailed(filepath.toString(), basepath.toString()).exception()
            }

            if (filepath.driveLetter != null
                && (basepath.driveLetter == null || filepath.driveLetter != basepath.driveLetter)) {
                throw XProcError.xdUrifyDifferentDrives(filepath.toString(), basepath.toString()).exception()
            }

            if ((filepath.driveLetter != null && basepath.authority != null)
                || (filepath.authority != null && basepath.driveLetter != null)) {
                throw XProcError.xdUrifyMixedDrivesAndAuthorities(filepath.toString(), basepath.toString()).exception()
            }

            if ((filepath.scheme != null && filepath.scheme != basepath.scheme)) {
                throw XProcError.xdUrifyDifferentSchemes(filepath.toString(), basepath.toString()).exception()
            }

            if (basepath.scheme != null && basepath.scheme != "file" && basepath.absolute) {
                val result = if(filepath.toString() == "") {
                    basepath.toString()
                } else {
                    UriUtils.resolve(URI(basepath.toString()), filepath.toString())!!.toString()
                }
                if (suffix == null) {
                    return result
                }
                return "${result}${suffix}"
            }

            val rscheme = basepath.scheme
            val rauthority = if (filepath.authority != null) {
                filepath.authority
            } else {
                basepath.authority
            }
            val rdrive = basepath.driveLetter
            val rpath = if (filepath.authority != null || filepath.absolute) {
                filepath.fixedPath()
            } else {
                if (onlySuffix) {
                    basepath.fixedPath()
                } else {
                    basepath.resolvePaths(basepath.fixedPath(), filepath.fixedPath())
                }
            }

            val sb = StringBuilder()
            sb.append(rscheme)
            sb.append(":")
            if (rauthority != null) {
                sb.append("//")
                sb.append(rauthority)
            } else {
                if (basepath.scheme != null && basepath.scheme == "file") {
                    if (basepath.absolute) {
                        sb.append("//")
                        if (rdrive != null) {
                            sb.append("/")
                        }
                    }
                }
            }
            if (rdrive != null) {
                sb.append(rdrive)
                sb.append(":")
            }
            sb.append(rpath)
            suffix?.let { sb.append(it) }
            return sb.toString()
        }
    }

    private var _scheme: String? = null
    private var _explicit = false
    private var _hierarchical = true
    private var _authority: String? = null
    private var _driveLetter: String? = null
    private var _path: String? = null
    private var _absolute = false

    val scheme: String?
        get() = _scheme
    val explicit: Boolean
        get() = _explicit
    val hierarchical: Boolean
        get() = _hierarchical
    val authority: String?
        get() = _authority
    val driveLetter: String?
        get() = _driveLetter
    val path: String?
        get() = _path
    val absolute: Boolean
        get() = _absolute
    val relative: Boolean
        get() = !absolute

    init {
        val faMatch = fileAuthorityRE.find(filepath)
        val fpMatch = filepathRE.find(filepath)
        val osMatch = otherSchemeRE.find(filepath)
        val aMatch = authorityRE.find(filepath)
        val wfMatch = windowsFilepathRE.find(filepath)
        val ppMatch = if (plainPathRE.find(filepath) != null) {
            !filepath.replace('\\', '/').startsWith("//")
        } else {
            false
        }

        if (filesep != "/") {
            var fileuri: Boolean? = null
            if (osMatch != null) {
                val scheme = osMatch.groupValues[1]
                if (scheme == "file" || (isWindows && scheme.length == 1)) {
                    fileuri = true
                } else {
                    fileuri = false
                }
            }

            if (fileuri == null && (basedir == null || basedir == "")) {
                fileuri = true
            }

            if (fileuri == null && basedir != null) {
                val bosMatch = otherSchemeRE.find(basedir)
                if (bosMatch != null) {
                    val scheme = bosMatch.groupValues[1]
                    if (scheme != "file") {
                        fileuri = false
                    }
                }
            }

            if (fileuri != false) {
                _path = filepath.replace(filesep, "/")
            } else {
                _path = filepath
            }
        } else {
            _path = filepath
        }

        if (filepath == "") {
            _path = ""
        } else if (filepath == "//") {
            _path = "/"
            _absolute = true
        } else if (isWindows && wfMatch != null) {
            val fpfile = wfMatch.groupValues[1]
            val fpdrive = wfMatch.groupValues[2]
            val fppath = wfMatch.groupValues[3].replace("\\", "/")
            _scheme = "file"
            _explicit = fpfile.lowercase().startsWith("file:")
            _driveLetter = fpdrive
            _path = fppath.replace("^/+".toRegex(), "/")
            _absolute = _path!!.startsWith("/")
        } else if (isWindows && ppMatch) {
            _scheme = null
            _explicit = false
            _driveLetter = null
            _path = UriUtils.normalizePath(filepath)
            _absolute = _path!!.startsWith("/")
        } else if (faMatch != null) {
            val fpauthority = faMatch.groupValues[2]
            val fppath = faMatch.groupValues[3]
            _scheme = "file"
            _explicit = true
            _authority = fpauthority
            _path = fppath.replace("^/+".toRegex(), "/")
            _absolute = _path!!.startsWith("/")
        } else if (fpMatch != null) {
            val fppath = fpMatch.groupValues[2]
            _scheme = "file"
            _explicit = true
            _path = fppath.replace("^/+".toRegex(), "/")
            _absolute = _path!!.startsWith("/")
        } else if (osMatch != null) {
            val fpscheme = osMatch.groupValues[1]
            val fppath = osMatch.groupValues[2]
            _scheme = fpscheme
            _explicit = true
            _path = fppath
            if (listOf("http", "https", "ftp").contains(fpscheme)) {
                _absolute = _path!!.startsWith("/")
            } else if (listOf("urn", "doi", "mailto").contains(fpscheme)) {
                _hierarchical = false
            } else {
                _hierarchical = _path!!.contains("/")
                _absolute = _path!!.startsWith("/")
            }
        } else if (aMatch != null) {
            val fpauthority = aMatch.groupValues[1]
            val fppath = aMatch.groupValues[2]
            _authority = fpauthority
            _path = fppath.replace("^/+".toRegex(), "/")
            _absolute = _path!!.startsWith("/")
        } else {
            _path = _path!!.replace("^/+".toRegex(), "/")
            _absolute = _path!!.startsWith("/")
        }
    }

    fun fixedPath(): String {
        if (explicit) {
            return _path!!
        }

        var newpath = path!!.replace("?", "%3F")
            .replace("#", "%23")
            .replace("\\", "%5C")
            .replace(" ", "%20")

        // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
        var buf = StringBuilder()
        var pos = newpath.indexOf("%")
        while (pos >= 0) {
            buf.append(newpath.substring(0, pos))
            newpath = newpath.substring(pos)
            if (newpath.length < 3) {
                pos = -1
            } else {
                val c1 = newpath[1].lowercaseChar()
                val c2 = newpath[2].lowercaseChar()
                if (((c1 in '0'..'9') || (c1 in 'a'..'f'))
                    && ((c2 in '0'..'9') || (c2 in 'a'..'f'))) {
                    val numInt = Integer.parseInt("${c1}${c2}", 16)
                    val num = Char(numInt)
                    if ((num in 'A'..'Z') || (num in 'a'..'z') || (num in '0'..'9')
                        || (num == '-') || (num == '.') || (num == '_') || (num == '~')) {
                        buf.append(num)
                    } else {
                        buf.append(newpath.substring(0, 3))
                    }
                    newpath = newpath.substring(3)
                } else {
                    buf.append("%")
                    newpath = newpath.substring(1)
                }

                pos = newpath.indexOf("%")
            }
        }
        buf.append(newpath)

        newpath = buf.toString()
        buf = StringBuilder()
        pos = 0
        while (pos < newpath.length) {
            val ch = newpath[pos]
            if (ch == '%') {
                if (pos + 2 >= newpath.length) {
                    buf.append("%25")
                } else {
                    val c1 = newpath[pos + 1].lowercaseChar()
                    val c2 = newpath[pos + 2].lowercaseChar()
                    if (((c1 in '0'..'9') || (c1 in 'a'..'f'))
                        && ((c2 in '0'..'9') || (c2 in 'a'..'f'))) {
                        buf.append("%")
                    } else {
                        buf.append("%25")
                    }
                }
            } else {
                buf.append(ch)
            }
            pos += 1
        }

        return buf.toString()
    }

    private fun resolvePaths(basepath: String, newpath: String): String {
        // This is only called when newpath is relative.
        val pos = basepath.lastIndexOf("/")
        return if (pos >= 0) {
            resolveDotSegments(basepath.substring(0, pos+1) + newpath)
        } else {
            resolveDotSegments("/" + newpath)
        }
    }

    private fun resolveDotSegments(path: String): String {
        val parts = path.split("/")
        val stack = mutableListOf<String>()
        var endingDots = false
        for (part in parts) {
            endingDots = false
            when (part) {
                "." -> {
                    endingDots = true
                }
                ".." -> {
                    endingDots = true
                    if (stack.isNotEmpty()) {
                        stack.removeLast()
                    }
                }
                else -> {
                    stack += part
                }
            }
        }

        if (endingDots) {
            return "${stack.joinToString("/")}/"
        }
        return stack.joinToString("/")
    }

    override fun toString(): String {
        val sb = StringBuilder()
        if (scheme != null) {
            sb.append(scheme)
            sb.append(":")
        }
        if (authority != null) {
            sb.append("//")
            sb.append(authority)
        } else {
            if (scheme == "file") {
                if (absolute) {
                    sb.append("//")
                    if (driveLetter != null) {
                        sb.append("/")
                    }
                }
            }
        }
        if (driveLetter != null) {
            sb.append(driveLetter)
            sb.append(":")
        }
        sb.append(fixedPath())
        return sb.toString()
    }
}
