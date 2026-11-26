package com.applauncher.dual;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.carlos.common.App;

import java.io.FileInputStream;

/**
 * 高低版本兼容集中处理（minSdk 21 / targetSdk 26）。
 *
 * 这里放所有 Build.VERSION.SDK_INT 分支，避免各 Activity 里散落魔数：
 *  - MATCH_ALL 从 API 23 才有意义（21/22 传 0 等价于全量可见）
 *  - 存储权限：6.0~10 走 READ_EXTERNAL_STORAGE 运行时权限；11+ 走「所有文件访问」
 *  - 页大小探测：Android 15+ 出现 16KB 页设备，native 库不按 16KB 对齐会加载失败，先把值写进日志便于排查
 */
public final class Compat {

    public static final int REQ_STORAGE = 1001;

    private Compat() { }

    public static boolean atLeast(int v) {
        return Build.VERSION.SDK_INT >= v;
    }

    /** API 23+ 用 MATCH_ALL，21/22 用 0（编译期常量会被内联，不会 NoSuchFieldError） */
    public static int matchAll() {
        return atLeast(23) ? PackageManager.MATCH_ALL : 0;
    }

    /** API 21 起 getInstalledApplications 都可用；这里只统一入口便于以后加分支 */
    public static int matchEnabled() {
        return 0;
    }

    /** 6.0~10 的「存储读」权限；11+ 返回 true（改走所有文件访问） */
    public static boolean hasLegacyStorageRead(Context c) {
        if (!atLeast(23) || atLeast(30)) return true;
        try {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            App.log("Compat: check storage failed", t);
            return true;
        }
    }

    public static void askLegacyStorageRead(Activity a, int req) {
        if (!atLeast(23) || atLeast(30)) return;
        if (hasLegacyStorageRead(a)) return;
        try {
            ActivityCompat.requestPermissions(a,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, req);
            App.log("Compat: requested READ_EXTERNAL_STORAGE (sdk=" + Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            App.log("Compat: request storage failed", t);
        }
    }

    /** 13+ 通知权限，缺了 guest 的通知会被静默丢弃 */
    public static boolean hasPostNotification(Context c) {
        if (!atLeast(33)) return true;
        try {
            return ContextCompat.checkSelfPermission(c, "android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 内存页大小（KB）。Android 15+ 有 16KB 页设备，此时 4KB 对齐的 native 库（libvbox/libshadowhook 等）
     * 会 load 失败，日志里能第一时间看出来。读 /proc/self/smaps 的 KernelPageSize，失败返回 -1。
     */
    public static int pageSizeKb() {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/self/smaps");
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return -1;
            String s = new String(buf, 0, n, "UTF-8");
            int i = s.indexOf("KernelPageSize:");
            if (i < 0) return -1;
            int j = i + "KernelPageSize:".length();
            StringBuilder sb = new StringBuilder();
            while (j < s.length() && sb.length() < 8) {
                char ch = s.charAt(j);
                if (ch >= '0' && ch <= '9') sb.append(ch);
                else if (sb.length() > 0) break;
                j++;
            }
            return sb.length() == 0 ? -1 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 设备信息一行，写进 log.txt 便于按机型/版本排查 */
    public static String deviceLine() {
        String abi;
        try {
            String[] a = Build.SUPPORTED_ABIS;
            abi = (a != null && a.length > 0) ? a[0] : "?";
        } catch (Throwable t) {
            abi = "?";
        }
        return "sdk=" + Build.VERSION.SDK_INT + " abi=" + abi
                + " pageSizeKb=" + pageSizeKb()
                + " model=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " release=" + Build.VERSION.RELEASE;
    }
}
