# kotlinx.serialization, OkHttp, Coil and AndroidX ship their own consumer R8 rules.
# Keep serializable model/DTO classes' generated serializers (belt and braces for enum/sealed lookups).
-keepclassmembers @kotlinx.serialization.Serializable class dev.steamshelf.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
