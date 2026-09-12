# Consumer R8/ProGuard rules for rain-turnkey-android — merged into any host app that minifies.
#
# Nothing to keep here. The adapter has no reflective code of its own; the web3j keep rules a host
# needs come from rain-core-android/consumer-rules.pro. AGP collects consumer rules from the app's
# runtime classpath, and core is on it for every consumer of this module (an `implementation`
# dependency would put it there just as well as the `api` one we declare).
