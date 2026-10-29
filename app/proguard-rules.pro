# Minification is disabled by default. If you enable it, keep the PDFBox and
# kotlinx.serialization classes.
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-keepclassmembers class com.budgetingtool.app.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
