# Consumer R8/ProGuard rules for rain-turnkey-android — merged into any host app that minifies.
#
# Nothing to keep here. The adapter has no reflective code of its own; the web3j keep rules a host
# needs come from rain-core-android/consumer-rules.pro. AGP collects consumer rules from the app's
# runtime classpath, and core is on it for every consumer of this module (an `implementation`
# dependency would put it there just as well as the `api` one we declare).

# Key export makes the vendor's decryptExportBundle reachable. Its Solana branch, which the SDK never
# takes (it builds the Solana keypair itself), calls com.turnkey.encoding's Base58Check helper, and
# that helper links org.bitcoinj.core.Base58, a class bitcoinj 0.17.1 (the project's CVE floor) no
# longer ships. R8 in a host without -ignorewarnings treats the dangling reference as an error, so
# it is declared harmless here: the branch is dead code on this classpath. Upstream issue pending.
-dontwarn org.bitcoinj.core.Base58

