# Consumer R8/ProGuard rules for rain-core-android — merged into any host app that minifies.
#
# web3j decodes ABI values reflectively: a TypeReference<T> subclass is resolved via
# getGenericSuperclass() (requires the Signature attribute) and the target datatype is
# instantiated through Class.getDeclaredConstructor(...) (requires the datatype classes
# and their constructors to survive shrinking). The SDK only constructs these types
# directly on encode paths, so a host that only reads (balances/allowances) has no code
# reference to them — without these rules R8 strips them and decoding crashes in
# TypeReference.getClassType().
-keepattributes Signature
-keep class org.web3j.abi.datatypes.** { <init>(...); }
