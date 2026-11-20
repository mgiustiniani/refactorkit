package org.refactorkit.cli.previewcommand

import java.nio.file.Files
import java.nio.file.Path

internal fun writeMavenFailsafeJvmProfileFixture(root: Path, shape: String, javaExecutable: String) {
    require(shape in setOf(
        "explicit profile JVM", "profile descriptor defaults", "inactive profile",
        "competing direct plugin", "unresolved profile argLine",
    ))
    val profileSettings = when (shape) {
        "explicit profile JVM" -> """
            <forkCount>1</forkCount><reuseForks>true</reuseForks><argLine>-Dprofile.failsafe=true</argLine>
            <jvm>$javaExecutable</jvm><jdkToolchain><version>1.8</version><vendor>temurin</vendor></jdkToolchain>
        """.trimIndent()
        "unresolved profile argLine" -> "<argLine>${'$'}{profile.unresolved}</argLine>"
        else -> ""
    }
    val competingPlugin = if (shape == "competing direct plugin") """
        <build><plugins><plugin>
          <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
          <version>3.5.4</version><configuration><argLine>-Doutside-profile=true</argLine></configuration>
        </plugin></plugins></build>
    """.trimIndent() else ""
    fun write(path: String, contents: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents.trimIndent() + "\n")
    }
    write("pom.xml", """
        <project><modelVersion>4.0.0</modelVersion><groupId>fixture.profile</groupId>
          <artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
          <modules><module>app</module></modules>
        </project>
    """)
    write("app/pom.xml", """
        <project><modelVersion>4.0.0</modelVersion>
          <parent><groupId>fixture.profile</groupId><artifactId>reactor</artifactId><version>1</version></parent>
          <artifactId>app</artifactId><properties><maven.compiler.release>17</maven.compiler.release></properties>
          $competingPlugin
          <profiles><profile><id>quality</id><build><plugins>
            <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId>
              <version>3.6.0</version><executions><execution><id>register-integration-source</id>
                <phase>generate-test-sources</phase><goals><goal>add-test-source</goal></goals>
                <configuration><sources><source>src/it/java</source></sources></configuration>
              </execution></executions>
            </plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
              <version>3.5.4</version><executions><execution><id>profile-integration</id>
                <phase>integration-test</phase><goals><goal>integration-test</goal><goal>verify</goal></goals>
                <configuration><reportsDirectory>target/profile-reports</reportsDirectory>
                  $profileSettings
                </configuration>
              </execution></executions>
            </plugin>
          </plugins></build></profile></profiles>
        </project>
    """)
    write("app/src/main/java/fixture/App.java", "package fixture; public class App {}")
    write("app/src/test/java/fixture/AppTest.java", "package fixture; public class AppTest {}")
    write("app/src/it/java/fixture/AppIT.java", "package fixture; public class AppIT {}")
}
