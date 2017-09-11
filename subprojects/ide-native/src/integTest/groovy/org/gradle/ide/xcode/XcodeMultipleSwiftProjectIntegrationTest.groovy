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
import org.gradle.nativeplatform.fixtures.app.SwiftAppWithLibraries
import org.gradle.nativeplatform.fixtures.app.SwiftAppWithLibrary
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.IgnoreIf

import static org.gradle.ide.xcode.internal.XcodeUtils.toSpaceSeparatedList

@Requires(TestPrecondition.XCODE)
@IgnoreIf({GradleContextualExecuter.embedded})
class XcodeMultipleSwiftProjectIntegrationTest extends AbstractXcodeIntegrationSpec {
    def setup() {
        settingsFile << """
            include 'app', 'greeter'
        """
    }

    def "create xcode project Swift executable"() {
        given:
        buildFile << """
            project(':app') {
                apply plugin: 'swift-executable'
                dependencies {
                    implementation project(':greeter')
                }
            }
            project(':greeter') {
                apply plugin: 'swift-library'
            }
        """
        def app = new SwiftAppWithLibrary()
        app.library.writeToProject(file("greeter"))
        app.executable.writeToProject(file("app"))

        when:
        succeeds("xcode")

        then:
        executedAndNotSkipped(":app:xcodeProject", ":app:xcodeProjectWorkspaceSettings", ":app:xcodeSchemeAppExecutable", ":app:xcode",
            ":greeter:xcodeProject", ":greeter:xcodeProjectWorkspaceSettings", ":greeter:xcodeSchemeGreeterSharedLibrary", ":greeter:xcode",
            ":xcodeWorkspace", ":xcodeWorkspaceWorkspaceSettings", ":xcode")

        xcodeWorkspace("${rootProjectName}.xcworkspace")
            .contentFile.assertHasProjects([file("${rootProjectName}.xcodeproj"), file('app/app.xcodeproj'), file('greeter/greeter.xcodeproj')]*.absolutePath)

        def project = xcodeProject("app/app.xcodeproj").projectFile
        project.indexTarget.getBuildSettings().SWIFT_INCLUDE_PATHS == toSpaceSeparatedList(file("greeter/build/obj/main/debug"))

        when:
        def resultDebugApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .succeeds()

        then:
        resultDebugApp.assertTasksExecuted(':greeter:compileDebugSwift', ':greeter:linkDebug',
            ':app:compileDebugSwift', ':app:linkDebug')

        when:
        def resultReleaseApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .withConfiguration('Release')
            .succeeds()

        then:
        resultReleaseApp.assertTasksExecuted(':greeter:compileReleaseSwift', ':greeter:linkRelease',
            ':app:compileReleaseSwift', ':app:linkRelease')
    }

    def "create xcode project Swift executable with transitive dependencies"() {
        def app = new SwiftAppWithLibraries()

        given:
        settingsFile.text =  """
            include 'app', 'log', 'hello'
            rootProject.name = "${rootProjectName}"
        """
        buildFile << """
            project(':app') {
                apply plugin: 'swift-executable'
                dependencies {
                    implementation project(':hello')
                }
            }
            project(':hello') {
                apply plugin: 'swift-library'
                dependencies {
                    api project(':log')
                }
            }
            project(':log') {
                apply plugin: 'swift-library'
            }
        """
        app.library.writeToProject(file("hello"))
        app.logLibrary.writeToProject(file("log"))
        app.executable.writeToProject(file("app"))

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
        appProject.indexTarget.getBuildSettings().SWIFT_INCLUDE_PATHS == toSpaceSeparatedList(file("hello/build/obj/main/debug"), file("log/build/obj/main/debug"))
        def helloProject = xcodeProject("hello/hello.xcodeproj").projectFile
        helloProject.indexTarget.getBuildSettings().SWIFT_INCLUDE_PATHS == toSpaceSeparatedList(file("log/build/obj/main/debug"))

        when:
        def resultDebugApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .succeeds()

        then:
        resultDebugApp.assertTasksExecuted(':log:compileDebugSwift', ':log:linkDebug',
            ':hello:compileDebugSwift', ':hello:linkDebug',
            ':app:compileDebugSwift', ':app:linkDebug')

        when:
        def resultReleaseHello = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('Hello SharedLibrary')
            .withConfiguration('Release')
            .succeeds()

        then:
        resultReleaseHello.assertTasksExecuted(':hello:compileReleaseSwift', ':hello:linkRelease',
            ':log:compileReleaseSwift', ':log:linkRelease')
    }

    def "create xcode project Swift executable inside composite build"() {
        given:
        settingsFile.text = """
            includeBuild 'greeter'
            rootProject.name = '${rootProjectName}'
        """
        buildFile << """
            apply plugin: 'swift-executable'
            apply plugin: 'xcode'

            dependencies {
                implementation 'test:greeter:1.3'
            }
        """

        file("greeter/settings.gradle") << "rootProject.name = 'greeter'"
        file('greeter/build.gradle') << """
            apply plugin: 'swift-library'
            apply plugin: 'xcode'

            group = 'test'
        """

        def app = new SwiftAppWithLibrary()
        app.library.writeToProject(file("greeter"))
        app.executable.writeToProject(testDirectory)

        when:
        succeeds("xcode")

        then:
        executedAndNotSkipped(":greeter:xcodeProject", ":greeter:xcodeProjectWorkspaceSettings", ":greeter:xcodeSchemeGreeterSharedLibrary",
            ":xcodeWorkspace", ":xcodeWorkspaceWorkspaceSettings", ":xcode")

        xcodeWorkspace("${rootProjectName}.xcworkspace")
            .contentFile.assertHasProjects([file("${rootProjectName}.xcodeproj"), file('greeter/greeter.xcodeproj')]*.absolutePath)

        def project = xcodeProject("${rootProjectName}.xcodeproj").projectFile
        project.indexTarget.getBuildSettings().SWIFT_INCLUDE_PATHS == toSpaceSeparatedList(file("greeter/build/obj/main/debug"))

        when:
        def resultDebugApp = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('App Executable')
            .succeeds()

        then:
        resultDebugApp.assertTasksExecuted(':greeter:compileDebugSwift', ':greeter:linkDebug', ':compileDebugSwift', ':linkDebug')

        when:
        def resultReleaseGreeter = newXcodebuildExecuter()
            .withWorkspace("${rootProjectName}.xcworkspace")
            .withScheme('Greeter SharedLibrary')
            .withConfiguration('Release')
            .succeeds()

        then:
        resultReleaseGreeter.assertTasksExecuted(':compileReleaseSwift', ':linkRelease')
    }
}
