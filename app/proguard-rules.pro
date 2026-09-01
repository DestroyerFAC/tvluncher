# The launcher has no reflection-driven entry points beyond the Android
# components declared in the manifest, which R8 already keeps.
-dontwarn kotlinx.coroutines.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
