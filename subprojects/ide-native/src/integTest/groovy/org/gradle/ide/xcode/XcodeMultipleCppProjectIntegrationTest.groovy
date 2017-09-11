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

package org.gradle.ide.xcode

import org.gradle.ide.xcode.fixtures.AbstractXcodeIntegrationSpec
import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.nativeplatform.fixtures.app.CppAppWithLibraries
import org.gradle.nativeplatform.fixtures.app.CppAppWithLibrary
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.IgnoreIf

import static org.gradle.ide.xcode.internal.XcodeUtils.toSpaceSeparatedList

@Requires(TestPrecondition.XCODE)
@IgnoreIf({GradleContextualExecuter.embedded})
class XcodeMultipleCppProjectIntegrationTest extends AbstractXcodeIntegrationSpec {
    def setup() {
        settingsFile << """
            include 'app', 'greeter'
        """
    }

    def "create xcode project C++ executable"() {
        given:
        buildFile << """
            project(':app') {
                apply plugin: 'cpp-executable'
                dependencies {
                    implementation project(':greeter')
                }
            }
            project(':greeter') {
                apply plugin: 'cpp-library'
            }
"""
        def app = new CppAppWithLibrary()
        app.greeterLib.writeToProject(file('greeter'))
        app.main.writeToProject(file('app'))

        when:
        succeeds("xcode")

        then:
        executedAndNotSkipped(":app:xcodeProject", ":app:xcodeProjectWorkspaceSettings", ":app:xcodeSchemeAppExecutable", ":app:xcode",
            ":greeter:xcodeProject", ":greeter:xcodeProjectWorkspaceSettings", ":greeter:xcodeSchemeGreeterSharedLibrary", ":greeter:xcode",
            ":xcodeWorkspace", ":xcodeWorkspaceWorkspaceSettings", ":xcode")

        xcodeWorkspace("${rootProjectName}.xcworkspace")
            .contentFile.assertHasProjects([file("${rootProjectName}.xcodeproj"), file('app/app.xcodeproj'), file('greeter/greeter.xcodeproj')]*.absolutePath)

        def project = xcodeProject("app/app.xcodeproj").projectFile
        project.indexTarget.getBuildSettings().HEADER_SEARCH_PATHS == toSpaceSeparatedList(file("app/src/main/headers"), file("greeter/src/main/public"))

        when:
        def resultDebugApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .succeeds()

        then:
        resultDebugApp.assertTasksExecuted(':greeter:compileDebugCpp', ':greeter:linkDebug',
            ':app:compileDebugCpp', ':app:linkDebug')

        when:
        def resultReleaseApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .withConfiguration('Release')
            .succeeds()

        then:
        resultReleaseApp.assertTasksExecuted(':greeter:compileReleaseCpp', ':greeter:linkRelease',
            ':app:compileReleaseCpp', ':app:linkRelease')
    }

    def "create xcode project C++ executable with transitive dependencies"() {
        def app = new CppAppWithLibraries()

        given:
        settingsFile.text =  """
            include 'app', 'log', 'hello'
            rootProject.name = "${rootProjectName}"
        """
        buildFile << """
            project(':app') {
                apply plugin: 'cpp-executable'
                dependencies {
                    implementation project(':hello')
                }
            }
            project(':hello') {
                apply plugin: 'cpp-library'
                dependencies {
                    implementation project(':log')
                }
            }
            project(':log') {
                apply plugin: 'cpp-library'
            }
        """
        app.greeterLib.writeToProject(file("hello"))
        app.loggerLib.writeToProject(file("log"))
        app.main.writeToProject(file("app"))

        when:
        succeeds("xcode")

        then:
        executedAndNotSkipped(":app:xcodeProject", ":app:xcodeProjectWorkspaceSettings", ":app:xcodeSchemeAppExecutable", ":app:xcode",
            ":log:xcodeProject", ":log:xcodeProjectWorkspaceSettings", ":log:xcodeSchemeLogSharedLibrary", ":log:xcode",
            ":hello:xcodeProject", ":hello:xcodeProjectWorkspaceSettings", ":hello:xcodeSchemeHelloSharedLibrary", ":hello:xcode",
            ":xcodeWorkspace", ":xcodeWorkspaceWorkspaceSettings", ":xcode")

        xcodeWorkspace("${rootProjectName}.xcworkspace")
            .contentFile.assertHasProjects([file("${rootProjectName}.xcodeproj"), file('app/app.xcodeproj'), file('log/log.xcodeproj'), file('hello/hello.xcodeproj')]*.absolutePath)

        def appProject = xcodeProject("app/app.xcodeproj").projectFile
        appProject.indexTarget.getBuildSettings().HEADER_SEARCH_PATHS == toSpaceSeparatedList(file("app/src/main/headers"), file("hello/src/main/public"))
        def helloProject = xcodeProject("hello/hello.xcodeproj").projectFile
        helloProject.indexTarget.getBuildSettings().HEADER_SEARCH_PATHS == toSpaceSeparatedList(file("hello/src/main/public"), file("hello/src/main/headers"), file("log/src/main/public"))

        when:
        def resultDebugApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .succeeds()

        then:
        resultDebugApp.assertTasksExecuted(':log:compileDebugCpp', ':log:linkDebug',
            ':hello:compileDebugCpp', ':hello:linkDebug',
            ':app:compileDebugCpp', ':app:linkDebug')

        when:
        def resultReleaseHello = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('Hello SharedLibrary')
            .withConfiguration('Release')
            .succeeds()

        then:
        resultReleaseHello.assertTasksExecuted(':hello:compileReleaseCpp', ':hello:linkRelease',
            ':log:compileReleaseCpp', ':log:linkRelease')
    }

    def "create xcode project C++ executable inside composite build"() {
        given:
        settingsFile.text = """
            includeBuild 'greeter'
            rootProject.name = '${rootProjectName}'
        """
        buildFile << """
            apply plugin: 'cpp-executable'
            apply plugin: 'xcode'

            dependencies {
                implementation 'test:greeter:1.3'
            }
        """

        file("greeter/settings.gradle") << "rootProject.name = 'greeter'"
        file('greeter/build.gradle') << """
            apply plugin: 'cpp-library'
            apply plugin: 'xcode'

            group = 'test'
        """

        def app = new CppAppWithLibrary()
        app.greeterLib.writeToProject(file('greeter'))
        app.main.writeToProject(testDirectory)

        when:
        succeeds("xcode")

        then:
        executedAndNotSkipped(":greeter:xcodeProject", ":greeter:xcodeProjectWorkspaceSettings", ":greeter:xcodeSchemeGreeterSharedLibrary",
            ":xcodeWorkspace", ":xcodeWorkspaceWorkspaceSettings", ":xcode")

        xcodeWorkspace("${rootProjectName}.xcworkspace")
            .contentFile.assertHasProjects([file("${rootProjectName}.xcodeproj"), file('greeter/greeter.xcodeproj')]*.absolutePath)

        def project = xcodeProject("${rootProjectName}.xcodeproj").projectFile
        project.indexTarget.getBuildSettings().HEADER_SEARCH_PATHS == toSpaceSeparatedList(file("src/main/headers"), file("greeter/src/main/public"))

        when:
        def resultDebugApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .succeeds()

        then:
        resultDebugApp.assertTasksExecuted(':greeter:compileDebugCpp', ':greeter:linkDebug', ':compileDebugCpp', ':linkDebug')

        when:
        def resultReleaseGreeter = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('Greeter SharedLibrary')
            .withConfiguration('Release')
            .succeeds()

        then:
        resultReleaseGreeter.assertTasksExecuted(':compileReleaseCpp', ':linkRelease')
    }
}
