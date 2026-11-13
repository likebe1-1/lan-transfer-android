# JSch 通过反射加载加密实现（jce/zlib 等），必须整体保留
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Commons Net
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# kotlinx.coroutines
-dontwarn kotlinx.coroutines.**

# org.json 由系统提供
-dontwarn org.json.**
