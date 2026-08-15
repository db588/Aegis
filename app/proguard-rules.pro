# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep VpnService subclass name (referenced from manifest)
-keep class com.example.aegis.vpn.DnsVpnService { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
