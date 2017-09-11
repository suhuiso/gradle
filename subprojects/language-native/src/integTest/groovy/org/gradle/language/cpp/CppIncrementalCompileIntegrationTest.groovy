/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp

import org.gradle.integtests.fixtures.SourceFile
import org.gradle.nativeplatform.fixtures.AbstractInstalledToolChainIntegrationSpec
import org.gradle.nativeplatform.fixtures.app.CppApp
import org.gradle.nativeplatform.fixtures.app.CppLib
import org.gradle.nativeplatform.fixtures.app.IncrementalCppStaleCompileOutputApp
import org.gradle.nativeplatform.fixtures.app.IncrementalCppStaleCompileOutputLib
import org.gradle.nativeplatform.fixtures.app.SourceElement
import org.junit.Assume

class CppIncrementalCompileIntegrationTest extends AbstractInstalledToolChainIntegrationSpec {
    def setup() {
        // TODO - currently the customizations to the tool chains are ignored by the plugins, so skip these tests until this is fixed
        Assume.assumeTrue(toolChain.id != "mingw" && toolChain.id != "gcccygwin")
    }

    def "removes stale object files for executable"() {
        settingsFile << "rootProject.name = 'app'"
        def app = new IncrementalCppStaleCompileOutputApp()

        given:
        app.writeToProject(testDirectory)

        and:
        buildFile << """
            apply plugin: 'cpp-executable'
         """

        and:
        succeeds "assemble"
        app.applyChangesToProject(testDirectory)

        expect:
        succeeds "assemble"
        result.assertTasksExecuted(":compileDebugCpp", ":linkDebug", ":installDebug", ":assemble")
        result.assertTasksNotSkipped(":compileDebugCpp", ":linkDebug", ":installDebug", ":assemble")

        file("build/obj/main/debug").assertHasDescendants(expectIntermediateDescendants(app.alternate))
        executable("build/exe/main/debug/app").assertExists()
        installation("build/install/main/debug").exec().out == app.expectedOutput
    }

    def "removes stale object files for library"() {
        def lib = new IncrementalCppStaleCompileOutputLib()
        settingsFile << "rootProject.name = 'hello'"

        given:
        lib.writeToProject(testDirectory)

        and:
        buildFile << """
            apply plugin: 'cpp-library'
         """

        and:
        succeeds "assemble"
        lib.applyChangesToProject(testDirectory)

        expect:
        succeeds "assemble"
        result.assertTasksExecuted(":compileDebugCpp", ":linkDebug", ":assemble")
        result.assertTasksNotSkipped(":compileDebugCpp", ":linkDebug", ":assemble")

        file("build/obj/main/debug").assertHasDescendants(expectIntermediateDescendants(lib.alternate))
        sharedLibrary("build/lib/main/debug/hello").assertExists()
    }

    def "skips compile and link tasks for executable when source doesn't change"() {
        def app = new CppApp()
        settingsFile << "rootProject.name = 'app'"

        given:
        app.writeToProject(testDirectory)

        and:
        buildFile << """
            apply plugin: 'cpp-executable'
         """

        and:
        succeeds "assemble"

        expect:
        succeeds "assemble"
        result.assertTasksExecuted(":compileDebugCpp", ":linkDebug", ":installDebug", ":assemble")
        result.assertTasksSkipped(":compileDebugCpp", ":linkDebug", ":installDebug", ":assemble")

        executable("build/exe/main/debug/app").assertExists()
        installation("build/install/main/debug").exec().out == app.expectedOutput
    }

    def "skips compile and link tasks for library when source doesn't change"() {
        def lib = new CppLib()
        settingsFile << "rootProject.name = 'hello'"

        given:
        lib.writeToProject(testDirectory)

        and:
        buildFile << """
            apply plugin: 'cpp-library'
         """

        and:
        succeeds "assemble"

        expect:
        succeeds "assemble"
        result.assertTasksExecuted(":compileDebugCpp", ":linkDebug", ":assemble")
        result.assertTasksSkipped(":compileDebugCpp", ":linkDebug", ":assemble")

        sharedLibrary("build/lib/main/debug/hello").assertExists()
    }

    private List<String> expectIntermediateDescendants(SourceElement sourceElement) {
        List<String> result = new ArrayList<String>()

        String sourceSetName = sourceElement.getSourceSetName()
        String intermediateFilesDirPath = "build/obj/main/debug"
        File intermediateFilesDir = file(intermediateFilesDirPath)
        for (SourceFile sourceFile : sourceElement.getFiles()) {
            if (!sourceFile.getName().endsWith(".h")) {
                def cppFile = file("src", sourceSetName, sourceFile.path, sourceFile.name)
                result.add(objectFileFor(cppFile, intermediateFilesDirPath).relativizeFrom(intermediateFilesDir).path)
                if (toolChain.isVisualCpp()) {
                    result.add(debugFileFor(cppFile).relativizeFrom(intermediateFilesDir).path)
                }
            }
        }
        return result
    }

    def debugFileFor(File sourceFile, String intermediateFilesDir = "build/obj/main/debug") {
        return intermediateFileFor(sourceFile, intermediateFilesDir, ".obj.pdb")
    }
}
