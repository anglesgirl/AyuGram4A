package org.telegram.tgwsproxy;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * JNA 绑定到 Rust 核心 libtgwsproxy.so（tg-ws-proxy 的 MTProxy→WSS 引擎）。
 * 与上游 com.amurcanov.tgwsproxy 的 ProxyLibrary 一一对应。
 */
public interface TgWsProxyNative extends Library {
    TgWsProxyNative INSTANCE = Native.load("tgwsproxy", TgWsProxyNative.class);

    /** host=监听地址, port=监听端口, dcIps=逗号分隔 DC IP 映射, secret=MTProxy secret, verbose=日志级别 */
    int StartProxy(String host, int port, String dcIps, String secret, int verbose);

    int StopProxy();

    void SetPoolSize(int size);

    void SetCfProxyCacheDir(String cacheDir);

    /** enabled: 0/1, priority: 0/1, userDomain: 自定义 CF 基础域名（空=内置域名池） */
    void SetCfProxyConfig(int enabled, int priority, String userDomain);

    /** 固定 IP 区间，例如 "104.16.0.1-104.20.255.255"；空=用 DoH */
    void SetFixedIpRange(String range);

    /** 返回带正确前缀（dd 或 ee+domain_hex）的完整 secret，用完必须 FreeString */
    Pointer GetSecretWithPrefix();

    /** 返回 JSON 统计字符串，用完必须 FreeString */
    Pointer GetStats();

    void FreeString(Pointer p);

    final class Util {
        private Util() {}

        public static String ptrToString(Pointer p) {
            if (p == null) {
                return null;
            }
            try {
                return p.getString(0);
            } finally {
                try {
                    TgWsProxyNative.INSTANCE.FreeString(p);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
