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
# BouncyCastle：运行时生成 FTPS 自签证书、算法提供者通过反射加载
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Apache MINA SSHD：SFTP/SCP 服务端，内部大量使用 ServiceLoader 与反射
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# slf4j 绑定
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
