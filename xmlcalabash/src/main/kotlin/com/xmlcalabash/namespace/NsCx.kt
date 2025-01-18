package com.xmlcalabash.namespace

import net.sf.saxon.om.NamespaceUri
import net.sf.saxon.s9api.QName

object NsCx {
    val namespace: NamespaceUri = NamespaceUri.of("http://xmlcalabash.com/ns/extensions")
    val errorNamespace: NamespaceUri = NamespaceUri.of("http://xmlcalabash.com/ns/error")

    val anonymous = QName(namespace, "cx:anonymous")
    val anonymousType = QName(namespace, "cx:anonymous-type")
    val antiItem = QName(namespace, "cx:anti-item")
    val assertions = QName(namespace, "cx:assertions")
    val atomicStep = QName(namespace, "cx:atomic-step")
    val blake3 = QName(namespace, "cx:blake3")
    val cacheAddDocument = QName(namespace, "cx:cache-add-document")
    val cacheRemoveDocument = QName(namespace, "cx:cache-remove-document")
    val contentTypeCheck = QName(namespace, "cx:content-type-check")
    val crc = QName(namespace, "cx:crc")
    val defaultAttribute = QName(namespace, "cx:default-attribute") // Used in the XProc parser
    val defaultElement = QName(namespace, "cx:default-element") // Used in the XProc parser
    val defaultInput = QName(namespace, "cx:default-input")
    val difference = QName(namespace, "cx:difference")
    val document = QName(namespace, "cx:document")
    val dynamicOptions = QName(namespace, "cx:dynamic-options")
    val eager = QName(namespace, "cx:eager")
    val empty = QName(namespace, "cx:empty")
    val emptyGlobalContext = QName(namespace, "cx:empty-global-context")
    val encoding = QName(namespace, "cx:encoding")
    val executable = QName(namespace, "cx:executable")
    val expression = QName(namespace, "cx:expression")
    val fallback = QName(namespace, "cx:fallback")
    val foot = QName(namespace, "cx:foot")
    val guard = QName(namespace, "cx:guard")
    val groupReadable = QName(namespace, "cx:group-readable")
    val groupWritable = QName(namespace, "cx:group-writable")
    val groupExecutable = QName(namespace, "cx:group-executable")
    val head = QName(namespace, "cx:head")
    val href= QName(namespace, "cx:href")
    val hmac = QName(namespace, "cx:hmac")
    val inline = QName(namespace, "cx:inline")
    val inputFilter = QName(namespace, "cx:input-filter")
    val joiner = QName(namespace, "cx:joiner")
    val link = QName(namespace, "cx:link")
    val mergeDuplicates = QName(namespace, "cx:merge-duplicates")
    val messages = QName(namespace, "cx:messages")
    val message = QName(namespace, "cx:message")
    val name = QName(namespace, "cx:name")
    val otherReadable = QName(namespace, "cx:other-readable")
    val otherWritable = QName(namespace, "cx:other-writable")
    val otherExecutable = QName(namespace, "cx:other-executable")
    val onlyStandard = QName(namespace, "cx:only-standard")
    val option = QName(namespace, "cx:option")
    val outputCollector = QName(namespace, "cx:output-collector")
    val pipeline = QName(namespace, "cx:pipeline")
    val processor = QName(namespace, "cx:processor")
    val productBuild = QName(namespace, "cx:product-build")
    val publicIdentifier = QName(namespace, "cx:public-identifier")
    val report = QName(namespace, "cx:report")
    val saxonEdition = QName(namespace, "saxon-edition")
    val saxonVersion = QName(namespace, "saxon-version")
    val select = QName(namespace, "cx:select")
    val sink = QName(namespace, "cx:sink")
    val splitter = QName(namespace, "cx:splitter")
    val stackFrame = QName(namespace, "cx:stack-frame")
    val stackTrace = QName(namespace, "cx:stack-trace")
    val subpipeline = QName(namespace, "subpipeline")
    val symbolicLink = QName(namespace, "cx:symbolic-link")
    val trim = QName(namespace, "cx:trim")
    val unknown = QName(namespace, "cx:unknown")
    val unreachableType = QName(namespace, "cx:unreachable-type")
    val viewport = QName(namespace, "cx:viewport")
    val xmlCalabash = QName(namespace, "cx:xml-calabash")
    val unimplemented = QName(namespace, "cx:unimplemented")
    val yaml = QName(namespace, "cx:yaml")
    val toml = QName(namespace, "cx:toml")
    val `while` = QName(namespace, "cx:while")
    val until = QName(namespace, "cx:until")

    val unusedValue = QName(namespace, "cx:never-used-for-anything")
    val fakeOptionName = QName(namespace, "cx:fake-option-name")

    val checkSum = QName(namespace, "cx:check-sum")
    val device = QName(namespace, "cx:device")
    val devMajor = QName(namespace, "cx:dev-major")
    val devMinor = QName(namespace, "cx:dev-minor")
    val deviceType = QName(namespace, "cx:device-type")
    val format = QName(namespace, "cx:format")
    val groupId = QName(namespace, "cx:group-id")
    val groupName = QName(namespace, "cx:group-name")
    val userId = QName(namespace, "cx:user-id")
    val userName = QName(namespace, "cx:user-name")
    val linkFlag = QName(namespace, "cx:link-flag")
    val linkName = QName(namespace, "cx:link-name")
    val mode = QName(namespace, "cx:mode")
    val modeString = QName(namespace, "cx:mode-string")
    val numberOfLinks = QName(namespace, "cx:number-of-links")
    val remoteDevice = QName(namespace, "cx:remote-device")
    val remoteDevMajor = QName(namespace, "cx:remote-dev-major")
    val remoteDevMinor = QName(namespace, "cx:remote-dev-minor")
    val size = QName(namespace, "cx:size")
    val time = QName(namespace, "cx:time")
    val externalAttributes = QName(namespace, "cx:external-attributes")
    val fileCreationTime = QName(namespace, "cx:file-creation-time")
    val lastAccessTime = QName(namespace, "cx:last-access-time")
    val lastModified = QName(namespace, "cx:last-modified")
    val statusChangeTime = QName(namespace, "cx:status-change-time")
    val windowsAttributes = QName(namespace, "cx:windows-attributes")
    val hostOs = QName(namespace, "cx:host-os")
}