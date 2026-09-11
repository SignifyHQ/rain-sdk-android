# Consumer R8/ProGuard rules for rain-turnkey-android — merged into any host app that minifies.
#
# The adapter has no web3j code of its own; core does the ABI work and ships these same keep rules
# in rain-core-android/consumer-rules.pro. They are carried here as well so a host that minifies
# without core's rules still keeps web3j's reflective datatype constructors.
-keepattributes Signature
-keep class org.web3j.abi.datatypes.** { <init>(...); }
-keep,allowobfuscation,allowshrinking class * extends org.web3j.abi.TypeReference
