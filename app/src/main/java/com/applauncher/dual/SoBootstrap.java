package com.applauncher.dual;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.carlos.common.App;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

/**
 * 由 AAR 中 com.carlos.common.App.onCreate() 开头的字节码注入直接调用。
 * 不依赖 VirtualCore.initialize() 的 onVirtualProcess 回调（实测高版本上该分支可能不触发）。
 *
 * 只在 VA 虚拟进程（进程名形如 com.applauncher.dual:p0）里做两件事：
 *   1) 进程名追加 "(分身)"
 *   2) 延迟 2 秒后在进程内 System.load(选中的 .so)
 * 所有关键节点写入 files/log.txt。
 */
public final class SoBootstrap {

    private static final String HOST_PKG = "com.applauncher.dual";
    private static volatile boolean scheduled = false;

    private SoBootstrap() {}

    /** ASM 注入点：Application.onCreate() 的第一条指令之后执行 */
    public static void onAppCreate(Context ctx) {
        try {
            String proc = processName(ctx);
            App.log("SoBootstrap.onAppCreate pid=" + Process.myPid()
                    + " proc=" + proc + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS));
            App.log("SoBootstrap.env: " + Compat.deviceLine());
            if (Compat.pageSizeKb() > 4) {
                App.log("SoBootstrap: 16KB page device detected (pageSizeKb=" + Compat.pageSizeKb()
                        + ") — 4KB 对齐的 native 库可能加载失败，如出问题请记录此值");
            }
            if (proc == null || !isVirtualProcess(proc)) {
                App.log("SoBootstrap: not a VA guest process, skip");
                return;
            }
            renameProcess(proc);
            if (scheduled) {
                App.log("SoBootstrap: already scheduled in this process, skip");
                return;
            }
            scheduled = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { deferredLoad(ctx.getApplicationContext()); }
            }, "so-bootstrap");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            App.log("SoBootstrap.onAppCreate FAILED", t);
        }
    }

    private static boolean isVirtualProcess(String p) {
        int i = p.lastIndexOf(':');
        if (i < 0) return false;
        String s = p.substring(i + 1);
        return s.length() > 1 && s.charAt(0) == 'p' && Character.isDigit(s.charAt(1)) && !s.contains("(");
    }

    private static void renameProcess(String proc) {
        // Android 9+ 把 Process.setArgV0 拉进 hidden API 黑名单，不豁免的话反射直接失败
        exemptHiddenApi();
        try {
            Class<?> c = Class.forName("android.os.Process");
            java.lang.reflect.Method m = c.getDeclaredMethod("setArgV0", String.class);
            m.setAccessible(true);
            m.invoke(null, proc + "(分身)");
            App.log("SoBootstrap: setArgV0 -> " + proc + "(分身)");
        } catch (Throwable t) {
            App.log("SoBootstrap: setArgV0 FAILED", t);
        }
    }

    private static String processName(Context ctx) {
        // 优先读 /proc/self/cmdline：VA hook 过 AMS，getRunningAppProcesses 可能拿不到真实进程名
        java.io.FileInputStream in = null;
        try {
            byte[] b = new byte[256];
            in = new java.io.FileInputStream("/proc/self/cmdline");
            int n = in.read(b);
            if (n > 0) {
                String raw = new String(b, 0, n, "UTF-8");
                int z = raw.indexOf(0);
                String first = (z >= 0 ? raw.substring(0, z) : raw).trim();
                if (!first.isEmpty()) return first;
            }
        } catch (Throwable t) { App.log("SoBootstrap: /proc/self/cmdline failed", t); }
        finally { if (in != null) try { in.close(); } catch (Throwable ignored) { } }
        try {
            int pid = Process.myPid();
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getRunningAppProcesses() != null) {
                for (ActivityManager.RunningAppProcessInfo i : am.getRunningAppProcesses()) {
                    if (i.pid == pid) return i.processName;
                }
            }
        } catch (Throwable t) { App.log("SoBootstrap: processName lookup failed", t); }
        return null;
    }

    /** API 28+ 解除 hidden API 限制；Android 11+ 可能仍被拦，失败就只记日志 */
    private static void exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            Class<?> vr = Class.forName("dalvik.system.VMRuntime");
            Object rt = vr.getDeclaredMethod("getRuntime").invoke(null);
            java.lang.reflect.Method m = vr.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            m.invoke(rt, (Object) new String[]{"Landroid/os/Process;", "Landroid/app/ActivityThread;"});
            App.log("SoBootstrap: hidden api exemptions set (sdk=" + Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            App.log("SoBootstrap: hidden api exemption failed (sdk=" + Build.VERSION.SDK_INT + ") " + t);
        }
    }

    private static void deferredLoad(Context ctx) {
        int delay = 2000;
        try {
            delay = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    .getInt("inject_delay_ms", 2000);
        } catch (Throwable ignored) { }
        try { Thread.sleep(Math.max(0, delay)); } catch (InterruptedException ignored) { }
        App.log("=== SoBootstrap.deferredLoad begin pid=" + Process.myPid() + " ===");
        File flag = firstExisting(flagCandidates(ctx));
        if (flag == null) {
            App.log("SoBootstrap: no auto_inject.so flag -> skip (no SO selected)");
            InjectLog.w(ctx, "guest pid=" + Process.myPid() + " SKIP: no auto_inject.so flag");
            return;
        }
        App.log("SoBootstrap: flag=" + flag.getAbsolutePath());
        File so = newestReadable(soCandidates(ctx));
        if (so == null) {
            App.log("SoBootstrap: no readable temp.so -> skip");
            InjectLog.w(ctx, "guest pid=" + Process.myPid() + " SKIP: temp.so missing");
            return;
        }
        describeElf(so);
        // 架构不匹配就别 load：全架构包里内置的是 arm64 库，32 位/x86 进程强载会直接崩
        int machine = elfMachine(so);
        if (machine > 0 && !archMatches(machine)) {
            App.log("SoBootstrap: arch mismatch, skip load machine=0x" + Integer.toHexString(machine)
                    + " is64=" + Process.is64Bit());
            InjectLog.w(ctx, "guest pid=" + Process.myPid() + " SKIP: arch mismatch 0x"
                    + Integer.toHexString(machine));
            return;
        }
        try { so.setReadable(true, false); } catch (Throwable ignored) { }
        App.log("SoBootstrap: System.load begin -> " + so.getAbsolutePath());
        InjectLog.w(ctx, "guest pid=" + Process.myPid() + " LOAD begin " + so.getAbsolutePath()
                + " len=" + so.length() + " delay=" + delay + "ms");
        try {
            System.load(so.getAbsolutePath());
            App.log("SoBootstrap: System.load OK -> " + so.getAbsolutePath());
            InjectLog.w(ctx, "guest pid=" + Process.myPid() + " LOAD OK");
        } catch (Throwable t) {
            App.log("SoBootstrap: System.load FAILED -> " + so.getAbsolutePath(), t);
            InjectLog.w(ctx, "guest pid=" + Process.myPid() + " LOAD FAILED: " + t);
        }
    }

    private static File[] flagCandidates(Context ctx) {
        return new File[]{
                new File("/data/user/0/" + HOST_PKG + "/cache/auto_inject.so"),
                new File("/data/user/0/" + HOST_PKG + "/files/auto_inject.so"),
                new File("/data/data/" + HOST_PKG + "/cache/auto_inject.so"),
                new File("/data/data/" + HOST_PKG + "/files/auto_inject.so"),
                new File(ctx.getCacheDir(), "auto_inject.so"),
                new File(ctx.getFilesDir(), "auto_inject.so")
        };
    }

    private static File[] soCandidates(Context ctx) {
        return new File[]{
                new File("/data/user/0/" + HOST_PKG + "/cache/temp.so"),
                new File("/data/user/0/" + HOST_PKG + "/files/temp.so"),
                new File("/data/data/" + HOST_PKG + "/cache/temp.so"),
                new File("/data/data/" + HOST_PKG + "/files/temp.so"),
                new File(ctx.getCacheDir(), "temp.so"),
                new File(ctx.getFilesDir(), "temp.so")
        };
    }

    private static File firstExisting(File[] cands) {
        for (File f : cands) {
            try {
                if (f != null && f.isFile()) return f;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static File newestReadable(File[] cands) {
        File best = null;
        for (File f : cands) {
            try {
                if (f == null || !f.isFile() || !f.canRead() || f.length() <= 0) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            } catch (Throwable ignored) { }
        }
        return best;
    }

    private static int elfMachine(File so) {
        FileInputStream in = null;
        try {
            byte[] h = new byte[20];
            in = new FileInputStream(so);
            int n = in.read(h);
            if (n <= 18) return -1;
            return (h[18] & 0xff) | ((h[19] & 0xff) << 8);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 0xB7=AArch64 0x28=ARMv7 0x3E=x86_64 0x03=x86，与当前进程位数对齐 */
    private static boolean archMatches(int machine) {
        boolean is64;
        try { is64 = Process.is64Bit(); } catch (Throwable t) {
            is64 = machine == 0xB7 || machine == 0x3E;
        }
        if (is64 && (machine == 0xB7 || machine == 0x3E)) return true;
        if (!is64 && (machine == 0x28 || machine == 0x03)) return true;
        return false;
    }

    private static void describeElf(File so) {
        FileInputStream in = null;
        try {
            byte[] h = new byte[20];
            in = new FileInputStream(so);
            int n = in.read(h);
            String elfClass = n > 4 ? (h[4] == 1 ? "ELF32" : (h[4] == 2 ? "ELF64" : "?")) : "?";
            int machine = n > 18 ? ((h[18] & 0xff) | ((h[19] & 0xff) << 8)) : -1;
            String arch = machine == 0xB7 ? "AArch64" : machine == 0x28 ? "ARMv7"
                    : machine == 0x3E ? "x86_64" : machine == 0x03 ? "x86" : ("0x" + Integer.toHexString(machine));
            App.log("SoBootstrap: so=" + so.getAbsolutePath() + " size=" + so.length()
                    + " class=" + elfClass + " machine=" + arch
                    + " primaryAbi=" + Build.SUPPORTED_ABIS[0]
                    + " magic=" + (n > 3 ? String.format("%02x%02x%02x%02x", h[0], h[1], h[2], h[3]) : "?"));
        } catch (Throwable t) {
            App.log("SoBootstrap: describeElf FAILED", t);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
