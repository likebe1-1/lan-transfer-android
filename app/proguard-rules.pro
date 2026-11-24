# ---------------------------------------------------------------------------
# LanLink R8 规则
#
# 原则：第三方库中「靠反射/ServiceLoader 加载」的类必须保留类名与签名，
# 但**允许 R8 继续优化**（-keep,allowoptimization），避免整包 keep 让 R8 失效。
# 仅当库明确依赖字段/方法名被反射访问时才使用完整 -keep。
# ---------------------------------------------------------------------------

# JSch：按类名反射加载 JCE / zlib / junixsocket 实现，类名与签名必须保留
-keep,allowoptimization class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Commons Net：FTP/FTPS 客户端，代码直接引用；保留以兼容其内部反射/子类
-keep,allowoptimization class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# BouncyCastle：FTPS 自签证书生成（ASN.1/X.509 构建），内部大量工具类与反射
-keep,allowoptimization class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Apache MINA SSHD：SFTP/SCP 服务端，ServiceLoader + 反射创建工厂，类名必须保留
-keep,allowoptimization class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# slf4j：绑定通过 ServiceLoader 加载（slf4j-nop）
-keep,allowoptimization class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ZXing / zxing-android-embedded：扫码页由 XML/反射与 Manifest 引用
-keep,allowoptimization class com.google.zxing.** { *; }
-keep,allowoptimization class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# 其余仅用于消除第三方库可选依赖的告警
-dontwarn javax.naming.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.json.**

# ---------------------------------------------------------------------------
# Release 日志剥离：R8 直接删除 Log.d/v/i/w 调用（含字符串拼接与参数求值），
# 避免日志泄露文件路径、服务器地址、接收码等运行期信息，也减小包体。
# Log.e / Log.wtf 保留：崩溃与错误诊断需要，且不打印业务敏感字段。
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static boolean isLoggable(...);
}
