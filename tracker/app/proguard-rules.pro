# BouncyCastle is reached through JCA lookups by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.taksolutions.takbeacon.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
