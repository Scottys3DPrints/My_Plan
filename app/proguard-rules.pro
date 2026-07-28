# kotlinx.serialization
#
# The generated serializers are only ever referenced reflectively through the companion,
# so R8 cannot see the link and will otherwise strip them — producing a release build that
# loses every rule on restart while the debug build works fine.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.aegis.core.**$$serializer { *; }
-keepclassmembers class com.aegis.core.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.aegis.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.aegis.app.**$$serializer { *; }
-keepclassmembers class com.aegis.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Sealed hierarchies used polymorphically (RuleDelta) must keep their subclasses.
-keep class com.aegis.core.lockdown.RuleDelta { *; }
-keep class com.aegis.core.lockdown.RuleDelta$* { *; }

# Entry points the system instantiates by name.
-keep class com.aegis.app.service.AegisVpnService { *; }
-keep class com.aegis.app.service.AegisAccessibilityService { *; }
-keep class com.aegis.app.service.BootReceiver { *; }
-keep class com.aegis.app.AegisApplication { *; }
