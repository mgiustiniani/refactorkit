# language: en
Feature: Kotlin script semantics fail closed by host context

  @REQ-KOTLIN-MATRIX-001#standalone-script-refusal
  Scenario: Standalone script refusal
    Given a standalone Kotlin script declares an explicit @file:DependsOn host dependency
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And Kotlin semantic admission exposes "kotlin.scriptSemanticsUnsupported" as a structured daemon diagnostic code
    And the script contributes no symbols, references, workspace edits, or managed plans

  @REQ-KOTLIN-MATRIX-001#standalone-implicit-receiver-refusal
  Scenario: Standalone implicit receiver refusal
    Given a standalone Kotlin script references an unbound host-provided receiver
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the script contributes no symbols, references, workspace edits, or managed plans

  @REQ-KOTLIN-MATRIX-001#standalone-script-change-refusal
  Scenario: Standalone script change refusal
    Given a standalone Kotlin script declares an explicit @file:DependsOn host dependency
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the standalone script dependency input changes and the workspace is reprojected
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the script contributes no symbols, references, workspace edits, or managed plans

  @REQ-KOTLIN-MATRIX-001#gradle-host-receiver-scope-refusal
  Scenario Outline: Gradle host receiver scope refusal
    Given a Gradle Kotlin DSL "<script>" uses nested host receiver scopes
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the script contributes no symbols, references, workspace edits, or managed plans
    And the Gradle host captures "<script>" as an auxiliary input outside Kotlin source ownership

    Examples:
      | script             |
      | settings.gradle.kts |
      | build.gradle.kts    |

  @REQ-KOTLIN-MATRIX-001#gradle-build-context-change-refusal
  Scenario: Gradle build context change refusal
    Given a Gradle project has settings and project Kotlin scripts with a versioned plugin context
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the Gradle settings and project script inputs change and the workspace is reprojected
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the script contributes no symbols, references, workspace edits, or managed plans
    And the Gradle host captures "build.gradle.kts" as an auxiliary input outside Kotlin source ownership

  @REQ-KOTLIN-MATRIX-001#gradle-settings-script-refusal
  Scenario: Gradle settings script refusal
    Given a Gradle settings.gradle.kts script declares plugin management and a marker-writing body
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the script contributes no symbols, references, workspace edits, or managed plans
    And the Gradle host captures "settings.gradle.kts" as an auxiliary input outside Kotlin source ownership

  @REQ-KOTLIN-MATRIX-001#gradle-project-script-refusal
  Scenario: Gradle project script refusal
    Given a Gradle build.gradle.kts script declares a plugin-provided accessor and a marker-writing body
    When the bounded Kotlin/JVM build projection and compiler gate inspect the captured workspace
    Then the inspection reports "kotlin.scriptSemanticsUnsupported" before launching a compiler process
    And the script contributes no symbols, references, workspace edits, or managed plans
    And the Gradle host captures "build.gradle.kts" as an auxiliary input outside Kotlin source ownership
