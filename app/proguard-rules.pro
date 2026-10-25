-dontobfuscate

-keep class com.eza.spicyex.xposed.SpicyEXModule { *; }
-keepclassmembers class com.eza.spicyex.lyrics.reading.ReadingModels$* { <fields>; }
-keep class com.eza.hyperglow.bridge.ISpicyLyricBridge { *; }
-keep class com.eza.hyperglow.bridge.ISpicyLyricBridge$* { *; }
-keep class com.eza.spicyex.auto.IAutoPrototype { *; }
-keep class com.eza.spicyex.auto.IAutoPrototype$* { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
