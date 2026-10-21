# Mamoot uses only the Android framework + Kotlin stdlib; no reflection-heavy
# libraries, so aggressive shrinking/obfuscation is safe.
-dontwarn org.json.**
-keep class kotlin.Metadata { *; }
