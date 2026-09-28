package io.sodyx.app.security

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSecurityPolicyTest {
    private val manifestFile = File(checkNotNull(System.getProperty("sodyx.mainManifest")))
    private val namespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun manifestKeepsItsNarrowPublicSurface() {
        val manifest = parse(manifestFile)
        assertEquals(0, manifest.getElementsByTagName("uses-permission").length)
        assertEquals(1, manifest.getElementsByTagName("activity").length)

        val activity = manifest.getElementsByTagName("activity").item(0)
        assertEquals("true", activity.attributes.getNamedItemNS(namespace, "exported").nodeValue)

        val filters = manifest.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        assertFalse(hasNamedChild(manifest, "action", "android.intent.action.VIEW"))
        assertFalse(hasNamedChild(manifest, "category", "android.intent.category.BROWSABLE"))
    }

    @Test
    fun manifestPinsPrivacySensitivePlatformDefaults() {
        val manifest = parse(manifestFile)
        val application = manifest.getElementsByTagName("application").item(0)
        assertEquals(
            "false",
            application.attributes.getNamedItemNS(namespace, "allowBackup").nodeValue
        )
        assertEquals(
            "false",
            application.attributes.getNamedItemNS(namespace, "usesCleartextTraffic").nodeValue
        )
        assertEquals(
            "@xml/network_security_config",
            application.attributes.getNamedItemNS(namespace, "networkSecurityConfig").nodeValue
        )
    }

    @Test
    fun networkPolicyRejectsCleartextAndUserTrustAnchors() {
        val policy =
            parse(
                checkNotNull(manifestFile.parentFile).resolve("res/xml/network_security_config.xml")
            )
        val baseConfig = policy.getElementsByTagName("base-config").item(0)
        assertEquals(
            "false",
            baseConfig.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue
        )

        val certificates = policy.getElementsByTagName("certificates")
        assertEquals(1, certificates.length)
        assertEquals("system", certificates.item(0).attributes.getNamedItem("src").nodeValue)
    }

    @Test
    fun extractionPolicyExcludesEveryApplicationStorageDomain() {
        val policy =
            parse(
                checkNotNull(manifestFile.parentFile).resolve("res/xml/data_extraction_rules.xml")
            )
        val excludes = policy.getElementsByTagName("exclude")
        assertTrue(excludes.length >= 16)
        for (index in 0 until excludes.length) {
            assertEquals(".", excludes.item(index).attributes.getNamedItem("path").nodeValue)
        }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }.newDocumentBuilder().parse(file)

    private fun hasNamedChild(
        document: org.w3c.dom.Document,
        tagName: String,
        name: String
    ): Boolean {
        val elements = document.getElementsByTagName(tagName)
        return (0 until elements.length).any { index ->
            elements.item(index).attributes.getNamedItemNS(namespace, "name")?.nodeValue == name
        }
    }
}
