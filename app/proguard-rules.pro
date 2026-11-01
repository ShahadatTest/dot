# Media3 and Compose ship their own consumer rules.

# The Glyph SDK talks to a system service over AIDL; keep its classes intact.
-keep class com.nothing.** { *; }
-dontwarn com.nothing.**
