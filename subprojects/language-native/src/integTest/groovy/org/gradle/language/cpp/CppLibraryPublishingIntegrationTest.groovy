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

import org.gradle.nativeplatform.fixtures.AbstractInstalledToolChainIntegrationSpec
import org.gradle.nativeplatform.fixtures.app.CppAppWithLibrariesWithApiDependencies
import org.gradle.nativeplatform.fixtures.app.CppLib
import org.gradle.test.fixtures.archive.ZipTestFixture
import org.gradle.test.fixtures.maven.MavenFileRepository
import org.junit.Assume

class CppLibraryPublishingIntegrationTest extends AbstractInstalledToolChainIntegrationSpec {
    def setup() {
        // TODO - currently the customizations to the tool chains are ignored by the plugins, so skip these tests until this is fixed
        Assume.assumeTrue(toolChain.id != "mingw" && toolChain.id != "gcccygwin")
    }

    def "can publish binaries and headers of a library to a maven repository"() {
        def lib = new CppLib()
        assert !lib.publicHeaders.files.empty

        given:
        buildFile << """
            apply plugin: 'cpp-library'
            apply plugin: 'maven-publish'
            
            group = 'some.group'
            version = '1.2'
            library {
                baseName = 'test'
            }
            publishing {
                repositories { maven { url 'repo' } }
            }
"""
        lib.writeToProject(testDirectory)

        when:
        run('publish')

        then:
        result.assertTasksExecuted(":compileDebugCpp", ":linkDebug", ":generatePomFileForDebugPublication", ":publishDebugPublicationToMavenRepository", ":cppHeaders", ":generatePomFileForMainPublication", ":publishMainPublicationToMavenRepository", ":compileReleaseCpp", ":linkRelease", ":generatePomFileForReleasePublication", ":publishReleasePublicationToMavenRepository", ":publish")

        def headersZip = file("build/headers/cpp-api-headers.zip")
        new ZipTestFixture(headersZip).hasDescendants(lib.publicHeaders.files*.name)

        def repo = new MavenFileRepository(file("repo"))

        def main = repo.module('some.group', 'test', '1.2')
        main.assertPublished()
        main.assertArtifactsPublished("test-1.2-cpp-api-headers.zip", "test-1.2.pom")
        main.artifactFile(classifier: 'cpp-api-headers', type: 'zip').assertIsCopyOf(headersZip)

        def debug = repo.module('some.group', 'test_debug', '1.2')
        debug.assertPublished()
        debug.assertArtifactsPublished(withSharedLibrarySuffix("test_debug-1.2"), withLinkLibrarySuffix("test_debug-1.2"), "test_debug-1.2.pom")
        debug.artifactFile(type: sharedLibraryExtension).assertIsCopyOf(sharedLibrary("build/lib/main/debug/test").file)
        debug.artifactFile(type: linkLibrarySuffix).assertIsCopyOf(sharedLibrary("build/lib/main/debug/test").linkFile)

        def release = repo.module('some.group', 'test_release', '1.2')
        release.assertPublished()
        release.assertArtifactsPublished(withSharedLibrarySuffix("test_release-1.2"), withLinkLibrarySuffix("test_release-1.2"), "test_release-1.2.pom")
        release.artifactFile(type: sharedLibraryExtension).assertIsCopyOf(sharedLibrary("build/lib/main/release/test").file)
        release.artifactFile(type: linkLibrarySuffix).assertIsCopyOf(sharedLibrary("build/lib/main/release/test").linkFile)
    }

    def "can publish library and its dependencies to a maven repository"() {
        def lib = new CppAppWithLibrariesWithApiDependencies()

        given:
        settingsFile << "include 'deck', 'card', 'shuffle'"
        buildFile << """
            subprojects {
                apply plugin: 'cpp-library'
                apply plugin: 'maven-publish'
                
                group = 'some.group'
                version = '1.2'
                publishing {
                    repositories { maven { url '../repo' } }
                }
            }
            project(':deck') { 
                dependencies {
                    api project(':card')
                    implementation project(':shuffle')
                }
            }
"""
        lib.deck.writeToProject(file('deck'))
        lib.card.writeToProject(file('card'))
        lib.shuffle.writeToProject(file('shuffle'))

        when:
        run('publish')

        then:
        def repo = new MavenFileRepository(file("repo"))

        def deckModule = repo.module('some.group', 'deck', '1.2')
        deckModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2")
        deckModule.assertPublished()

        def deckDebugModule = repo.module('some.group', 'deck_debug', '1.2')
        deckDebugModule.assertPublished()
        deckDebugModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2", "some.group:shuffle:1.2")

        def deckReleaseModule = repo.module('some.group', 'deck_release', '1.2')
        deckReleaseModule.assertPublished()
        deckReleaseModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2", "some.group:shuffle:1.2")

        def cardModule = repo.module('some.group', 'card', '1.2')
        cardModule.assertPublished()

        def cardDebugModule = repo.module('some.group', 'card_debug', '1.2')
        cardDebugModule.assertPublished()

        def cardReleaseModule = repo.module('some.group', 'card_release', '1.2')
        cardReleaseModule.assertPublished()

        def shuffleModule = repo.module('some.group', 'shuffle', '1.2')
        shuffleModule.assertPublished()

        def shuffleDebugModule = repo.module('some.group', 'shuffle_debug', '1.2')
        shuffleDebugModule.assertPublished()

        def shuffleReleaseModule = repo.module('some.group', 'shuffle_release', '1.2')
        shuffleReleaseModule.assertPublished()
    }
}
