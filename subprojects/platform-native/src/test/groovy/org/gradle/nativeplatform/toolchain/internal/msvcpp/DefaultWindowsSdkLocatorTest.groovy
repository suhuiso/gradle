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

package org.gradle.nativeplatform.toolchain.internal.msvcpp

import org.gradle.util.TreeVisitor
import spock.lang.Specification


class DefaultWindowsSdkLocatorTest extends Specification {
    final WindowsSdkLocator.SearchResult legacySdkLookup = Stub(WindowsSdkLocator.SearchResult)
    final WindowsSdkLocator legacyWindowsSdkLocator = Stub(WindowsSdkLocator) {
        locateWindowsSdks(_) >> legacySdkLookup
    }
    final WindowsKitComponentLocator.SearchResult windowsKitLookup = Stub(WindowsKitComponentLocator.SearchResult)
    WindowsKitComponentLocator windowsKitSdkLocator = Stub(WindowsKitComponentLocator) {
        locateComponents(_) >> windowsKitLookup
    }

    WindowsSdkLocator locator = new DefaultWindowsSdkLocator(legacyWindowsSdkLocator, windowsKitSdkLocator)

    def "prefers a windows kit sdk over a legacy sdk"() {
        def sdk = Mock(WindowsKitWindowsSdk)
        given:
        legacySdkLookup.available >> true
        windowsKitLookup.available >> true
        windowsKitLookup.component >> sdk

        when:
        def result = locator.locateWindowsSdks(null)

        then:
        result.available
        result.sdk == sdk
    }

    def "finds a legacy sdk when a windows kit sdk cannot be found"() {
        def sdk = Mock(LegacyWindowsSdk)

        given:
        legacySdkLookup.available >> true
        legacySdkLookup.sdk >> sdk
        windowsKitLookup.available >> false
        windowsKitLookup.component >> null

        when:
        def result = locator.locateWindowsSdks(null)

        then:
        result.available
        result.sdk == sdk
    }

    def "does not find an sdk if neither locator is successful"() {
        def visitor = Mock(TreeVisitor)

        given:
        legacySdkLookup.available >> false
        legacySdkLookup.sdk >> null
        legacySdkLookup.explain(_) >> { args -> args[0].node("fail") }
        windowsKitLookup.available >> false
        windowsKitLookup.component >> null

        when:
        def result = locator.locateWindowsSdks(null)

        then:
        !result.available
        result.sdk == null

        when:
        result.explain(visitor)

        then:
        1 * visitor.node("fail")
    }
}
