# 当前 release 未开启 minify（isMinifyEnabled = false），规则仅作预留。
# 飞牛网关签名常量以字节码混淆形式存放，开启混淆时需保留相关类。
-keep class com.nasmusic.player.data.FnosApi { *; }
-keep class com.nasmusic.player.data.OnlineMusicApi { *; }
-dontwarn okhttp3.**
