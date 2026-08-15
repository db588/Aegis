# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep VpnService subclass name (referenced from manifest)
-keep class com.example.aegis.vpn.DnsVpnService { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Strip debug logging from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
