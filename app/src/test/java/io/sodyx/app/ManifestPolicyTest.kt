package io.sodyx.app

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guard the source manifest without introducing a simulated Android runtime. */
class ManifestPolicyTest {
    private val manifest = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }.newDocumentBuilder().parse(File(checkNotNull(System.getProperty("sodyx.mainManifest"))))

    @Test
    fun foundationRequestsNoPermissions() {
        assertEquals(0, manifest.getElementsByTagName("uses-permission").length)
        assertEquals(0, manifest.getElementsByTagName("uses-permission-sdk-23").length)
    }

    @Test
    fun backupsAndCleartextAreDisabled() {
        val application = manifest.getElementsByTagName("application").item(0)
        val namespace = "http://schemas.android.com/apk/res/android"
        assertEquals(
            "false",
            application.attributes.getNamedItemNS(namespace, "allowBackup").nodeValue
        )
        assertEquals(
            "false",
            application.attributes.getNamedItemNS(namespace, "usesCleartextTraffic").nodeValue
        )
    }
}
