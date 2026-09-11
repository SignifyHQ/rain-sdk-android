package com.rain.sdk.turnkey

import org.junit.Assume.assumeTrue

/**
 * Test doubles and fixtures for the Turnkey adapter's own suites.
 *
 * Core's equivalents live in its test source set, which does not cross a Gradle module boundary,
 * so each adapter module carries its own — the same arrangement as `PortalTestFixtures`. Keep these
 * in step with core's when a shared seam changes.
 */
internal object TurnkeyTestFixtures {
    const val RECIPIENT_ADDRESS = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc"
    const val TOKEN_ADDRESS = "0x9876543210987654321098765432109876543210"
    const val USDC_ADDRESS = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
}

/**
 * Turnkey's published AAR is compiled to class-file major version 68 (Java 24), so loading any
 * Turnkey type on an older JVM fails before a test body runs. Unit tests run on a JDK 24 launcher
 * (see the root build); this guard keeps a local run on an older JDK from reporting a hard failure.
 */
internal fun assumeJdk24() {
    val major = System.getProperty("java.version")?.substringBefore('.')?.toIntOrNull() ?: 0
    assumeTrue(
        "Turnkey SDK types load JDK-24 class files. Current: $major",
        major >= 24
    )
}

/**
 * Runs a suspending [block] and returns the [T] it threw. Fails the test when it threw nothing;
 * rethrows anything of another type so a wrong error class is never mistaken for the right one.
 */
internal suspend inline fun <reified T : Throwable> expectThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw t
    }
    throw AssertionError("Expected ${T::class.simpleName} to be thrown")
}
