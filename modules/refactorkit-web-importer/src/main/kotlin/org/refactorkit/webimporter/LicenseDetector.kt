package org.refactorkit.webimporter

enum class LicenseRisk { LOW, MEDIUM, HIGH, UNKNOWN }

data class LicenseInfo(
    val detected: String,
    val risk: LicenseRisk,
)

object LicenseDetector {
    fun detect(code: String): LicenseInfo = when {
        "MIT License" in code || "Permission is hereby granted, free of charge" in code ->
            LicenseInfo("MIT", LicenseRisk.LOW)
        "Apache License" in code || "Licensed under the Apache License" in code ->
            LicenseInfo("Apache-2.0", LicenseRisk.LOW)
        "BSD" in code && ("Redistribution and use" in code || "2-Clause" in code || "3-Clause" in code) ->
            LicenseInfo("BSD", LicenseRisk.LOW)
        "GNU Lesser General Public License" in code || "LGPL" in code ->
            LicenseInfo("LGPL", LicenseRisk.MEDIUM)
        "GNU General Public License" in code || "GNU GENERAL PUBLIC" in code ->
            LicenseInfo("GPL", LicenseRisk.HIGH)
        "Mozilla Public License" in code || "MPL" in code ->
            LicenseInfo("MPL", LicenseRisk.MEDIUM)
        "Copyright" in code || "copyright" in code ->
            LicenseInfo("copyright-notice", LicenseRisk.MEDIUM)
        else ->
            LicenseInfo("unknown", LicenseRisk.UNKNOWN)
    }
}
