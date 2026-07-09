package org.refactorkit.testkit

import java.nio.file.Path

data class GoldenTestCase(
    val name: String,
    val beforeDir: Path,
    val afterDir: Path,
    val requestFile: Path,
    val expectedPlanFile: Path,
)
