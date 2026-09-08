# Consumer R8/ProGuard rules for rain-core-android — merged into any host app that minifies.
#
# web3j decodes ABI values reflectively: a TypeReference<T> subclass is resolved via
# getGenericSuperclass() (requires the Signature attribute) and the target datatype is
# instantiated through Class.getDeclaredConstructor(...) (requires the datatype classes
# and their constructors to survive shrinking). The SDK only constructs these types
# directly on encode paths, so a host that only reads (balances/allowances) has no code
# reference to them — without these rules R8 strips them and decoding crashes in
# TypeReference.getClassType().
#
# The TypeReference subclass rule keeps only METADATA: in R8 full mode, -keepattributes
# applies solely to classes matched by a keep rule, so without it the anonymous
# `object : TypeReference<T>() {}` subclasses lose their generic signature (verified in
# dexdump: no dalvik.annotation.Signature) and getClassType() throws before the datatype
# rule even matters. allowobfuscation/allowshrinking make it free: R8 may still rename
# and drop unused subclasses. Same pattern as Gson's shipped TypeToken rule.
-keepattributes Signature
-keep class org.web3j.abi.datatypes.** { <init>(...); }
-keep,allowobfuscation,allowshrinking class * extends org.web3j.abi.TypeReference
