package com.applauncher.dual;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 在分身进程里「免打包」启动 Vector（NPatch 使用的 LSPosed 高版本内核）。
 *
 * 原理：
 *  1) 本项目是 VA 式分身，目标 App 跑在我们自己的进程里，因此不需要像 NPatch/LSPatch 那样
 *     把 dex/so 打进目标 APK —— 我们只要在 guest Application 被创建之前把框架装好即可。
 *  2) 时机：aar 里 com.lody.virtual.client.VClient#bindApplicationMainThread 入口（字节码补丁插入）。
 *     此时 ActivityThread / LoadedApk 都是可用的真实对象，Vector 的 LoadedApk 钩子随后会在
 *     VA 调用 LoadedApk.makeApplication → getClassLoader() 时派发 handleLoadPackage，
 *     并把 guest 的 ClassLoader 交给模块。
 *  3) 框架本体来自 NPatch v1.1.0 的运行时载荷（assets/npatch/loader.bin + libnpatch.so，
 *     Vector core-837，支持 Xposed API 94 与 libxposed API 101/102）。
 */
public final class LspVector {

    private static final String TAG = "LspVector";

    /** 这些包必须优先从载荷 dex 里解析，避免和 aar 自带的 de.robv.android.xposed 打架 */
    private static final String[] CHILD_FIRST = {
            "de.robv.android.xposed.",
            "org.matrix.vector.",
            "io.github.libxposed.",
            "top.nkbe.npatch.",
            "hidden.",
            "org.lsposed.",
            "android.content.res.X",
    };

    private static volatile boolean sStarted;
    private static volatile boolean sFailed;
    private static ClassLoader sPayload;

    public static boolean isStarted() {
        return sStarted;
    }

    /** 由 VClient 字节码补丁在 bindApplicationMainThread 入口调用（每个 guest 进程一次） */
    public static synchronized void onBindApplicationStart(String packageName, String processName) {
        if (sStarted || sFailed) return;
        try {
            Context host = hostContext();
            if (host == null) {
                Log.w(TAG, "host context is null, skip vector bootstrap");
                return;
            }
            // 外部数据 IO 重定向：先于 VA 的 redirectSdcardAndroidData 生效（它在本方法返回后才跑）
            redirectExternalStorage(host);
            Set<String> selected = selectedModules(host);
            if (selected.isEmpty()) {
                sStarted = true;
                Log.i(TAG, "no LSP modules selected, skip");
                return;
            }
            sStarted = true; // 先置位，防止重入
            // 模块清单由宿主进程在启动前写好（guest 里的 PackageManager 是 VA 虚拟 PM，查不到宿主模块）
            File cfg = new File(host.getFilesDir(), "npatch/modules.txt");
            if (!cfg.isFile() || cfg.length() == 0) {
                InjectLog.w(host, "LspVector: modules.txt missing, skip (selected=" + selected + ")");
                return;
            }

            // 载荷解压目录按「APK 最后更新时间」分版本：
            // extractAsset 只在文件不存在时才复制，如果固定用 code_cache/npatch，
            // 升级 APK 后里面还是旧的 loader.dex/libnpatch.so（改了库却不生效）。
            File dir = new File(host.getCodeCacheDir(), "npatch-" + payloadGen(host));
            File dexFile = extractAsset(host, "npatch/loader.bin", new File(dir, "loader.dex"));
            File metaDex = extractAsset(host, "npatch/metaloader.dex", new File(dir, "metaloader.dex"));
            File soFile = extractAsset(host, npatchSoAsset(), new File(dir, "libnpatch.so"));
            if (dexFile != null) dexFile.setReadOnly();
            if (metaDex != null) metaDex.setReadOnly();
            if (dexFile == null || metaDex == null || soFile == null) {
                InjectLog.w(host, "LspVector: payload missing (dex=" + dexFile
                        + ", meta=" + metaDex + ", so=" + soFile + ")");
                return;
            }

            // 载荷 ClassLoader：metaloader（LSPAppComponentFactoryStub）+ 框架 dex
            sPayload = new PayloadLoader(
                    metaDex.getAbsolutePath() + ":" + dexFile.getAbsolutePath(),
                    LspVector.class.getClassLoader());

            // libnpatch.so 的 JNI_OnLoad 第一件事就是找这个 stub 类，并读它的静态字段：
            //   byte[] dex    -> 框架 dex 字节，native 用它建 InMemoryDexClassLoader
            //   boolean hideLibs
            Class<?> stub = Class.forName(
                    "top.nkbe.npatch.metaloader.LSPAppComponentFactoryStub", true, sPayload);
            setStaticField(stub, "hideLibs", Boolean.FALSE);
            setStaticField(stub, "dex", readBytes(dexFile));

            // 驱动（我们的 LSPApplication 实现）从这几个路径读配置
            System.setProperty("applauncher.lsp.cfg", cfg.getAbsolutePath());
            System.setProperty("applauncher.lsp.log", new File(host.getFilesDir(), "inject.log").getAbsolutePath());
            // 模块配置安全区：模块界面（本进程）和目标进程都读写这里（见 LspPrefs）
            preparePrefs(host);

            loadNative(stub, soFile.getAbsolutePath());

            InjectLog.w(host, "LspVector: npatch native loaded pkg=" + packageName
                    + " proc=" + processName + " modules=" + selected);
            Log.i(TAG, "npatch native loaded, modules=" + selected);
        } catch (Throwable t) {
            sFailed = true;
            try {
                InjectLog.w(hostContext(), "LspVector: bootstrap failed " + Log.getStackTraceString(t));
            } catch (Throwable ignored) { }
            Log.e(TAG, "vector bootstrap failed", t);
        }
    }

    // ---------------------------------------------------------------- 外部数据重定向

    private static volatile boolean sExtRedirected;

    /**
     * 把 VA 的「外部（sdcard）数据」物理目录从
     *   /storage/emulated/0/Android/data/com.applauncher.dual/files/Android_va/0/<pkg>/...
     * 挪到
     *   /storage/emulated/0/Android/media/com.applauncher.dual/0/<pkg>/...
     *
     * 原因：Android 11+ 普通用户/文件管理器打不开 Android/data，但 Android/media/<pkg> 可以。
     *
     * 做法：VA 在 VClient#bindApplicationMainThread 里用
     *   VirtualCore.get().getContext().getExternalFilesDir(name + "/" + userId + "/")
     * 算出 sdcard 数据的宿主机物理根，然后 NativeEngine.redirectDirectory 把 guest 的
     * /sdcard/Android/data/<guestPkg> 映射过去。我们在这里（同一方法入口、计算之前）
     * 把 VirtualCore 的 context 换成一层 ContextWrapper，只改 getExternalFilesDir 的落点，
     * 其余全部委托给原 context，副作用最小。
     */
    private static void redirectExternalStorage(Context host) {
        if (sExtRedirected) return;
        sExtRedirected = true;
        try {
            // 默认关闭：Android/media 走 FUSE，LevelDB（Minecraft 存档）在上面会 EINVAL(22)
            // 打不开世界。只有用户在界面上明确切到「外部可见」才启用。
            boolean enabled = host.getSharedPreferences(LspHooks.PREFS, Context.MODE_PRIVATE)
                    .getBoolean("ext_media_dir", false);
            if (!enabled) {
                InjectLog.w(host, "LspVector: ext data stays in app-private dir (adaptive)");
                return;
            }
            Class<?> vc = Class.forName("com.lody.virtual.client.core.VirtualCore");
            Object core = vc.getMethod("get").invoke(null);
            if (core == null) return;
            Context cur = (Context) vc.getMethod("getContext").invoke(core);
            if (cur == null) cur = host;

            File mediaRoot = null;
            // 目标固定为「主包」的 Android/media 目录；64 位进程可能跑在 .ext 包里，
            // 那样它自己的 media 目录会是 com.applauncher.dual.ext，所以先把后缀剥掉。
            String hostPkg = host.getPackageName();
            if (hostPkg != null && hostPkg.endsWith(".ext")) {
                hostPkg = hostPkg.substring(0, hostPkg.length() - 4);
            }
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null && hostPkg != null) {
                File p = new File(ext, "Android/media/" + hostPkg);
                if (p.isDirectory() || p.mkdirs()) mediaRoot = p;
            }
            if (mediaRoot == null) {
                File[] medias = cur.getExternalMediaDirs();
                if (medias != null && medias.length > 0) mediaRoot = medias[0];
            }
            if (mediaRoot == null) {
                InjectLog.w(host, "LspVector: no external storage, ext redirect skipped");
                return;
            }
            if (!mediaRoot.isDirectory() && !mediaRoot.mkdirs()) {
                InjectLog.w(host, "LspVector: cannot create media root " + mediaRoot);
                return;
            }
            final File base = mediaRoot;
            ContextWrapper wrapper = new ContextWrapper(cur) {
                @Override
                public File getExternalFilesDir(String type) {
                    return mapExternal(base, type);
                }

                @Override
                public File[] getExternalFilesDirs(String type) {
                    return new File[]{mapExternal(base, type)};
                }
            };
            java.lang.reflect.Field f = vc.getDeclaredField("context");
            f.setAccessible(true);
            f.set(core, wrapper);
            InjectLog.w(host, "LspVector: ext data redirect -> " + base.getAbsolutePath());
        } catch (Throwable t) {
            try {
                InjectLog.w(host, "LspVector: ext redirect failed " + Log.getStackTraceString(t));
            } catch (Throwable ignored) { }
        }
    }

    /** 把 "Android_va/0/" 这类 VA 内部子目录名压到 media 根下：media/0/... */
    private static File mapExternal(File base, String type) {
        if (type == null || type.isEmpty()) return base;
        String t = type;
        int slash = t.indexOf('/');
        if (slash > 0 && t.substring(0, slash).startsWith("Android")) {
            t = t.substring(slash + 1);
        }
        while (t.startsWith("/")) t = t.substring(1);
        File out = t.isEmpty() ? base : new File(base, t);
        if (!out.isDirectory()) out.mkdirs();
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 模块配置安全区：框架的 getPrefsPath() 会返回这里。
     * 模块自己的界面（在 guest 里跑）写 SharedPreferences 时被重定向到这里，
     * 目标进程里的 XSharedPreferences 也从这里读，两端才是一致的。
     */
    public static void preparePrefs(Context host) {
        try {
            File dir = new File(host.getFilesDir(), LspPrefs.DIR);
            if (!dir.isDirectory()) dir.mkdirs();
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
            System.setProperty("applauncher.lsp.prefs", dir.getAbsolutePath());
            InjectLog.w(host, "LspVector: prefs safe zone=" + dir.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "preparePrefs failed", t);
        }
    }

    /** 载荷版本标识：用 APK 的 lastUpdateTime，重装/升级后必然不同，避免复用旧解压文件 */
    private static long payloadGen(Context host) {
        try {
            android.content.pm.PackageInfo pi = host.getPackageManager()
                    .getPackageInfo(host.getPackageName(), 0);
            if (pi != null) {
                long t = pi.lastUpdateTime > 0 ? pi.lastUpdateTime : pi.firstInstallTime;
                if (t > 0) return t;
            }
        } catch (Throwable ignored) { }
        return 0L;
    }

    /**
     * 按「本进程真实架构」选择 libnpatch 载荷。
     *
     * 坑：雷电/夜神这类模拟器的 SUPPORTED_ABIS 里同时有 arm64-v8a（houdini 翻译），
     * 而且顺序不保证 x86_64 在前；之前按列表顺序取，会误选 arm64 库 —— arm64 库
     * 在翻译层里做 inline hook 必崩。所以先按位数判断，再分两轮找：
     * 64 位进程优先 x86_64，其次 arm64-v8a；32 位进程我们没编库，直接报错而不是乱加载。
     */
    private static String npatchSoAsset() {
        boolean is64 = true;
        try {
            is64 = android.os.Process.is64Bit();
        } catch (Throwable ignored) { }
        String[] abis = Build.SUPPORTED_ABIS;
        StringBuilder seen = new StringBuilder();
        if (abis != null) {
            for (String a : abis) seen.append(a).append(',');
        }
        if (is64) {
            if (abis != null) {
                for (String abi : abis) if ("x86_64".equals(abi)) {
                    InjectLogAbi("x86_64", seen);
                    return "npatch/libnpatch-x86_64.so";
                }
                for (String abi : abis) if ("arm64-v8a".equals(abi)) {
                    InjectLogAbi("arm64-v8a", seen);
                    return "npatch/libnpatch-arm64.so";
                }
            }
        }
        InjectLogAbi("none(64=" + is64 + ")", seen);
        return null;   // 32 位 / 未知 ABI：没有对应库，让上层报 payload missing
    }

    private static void InjectLogAbi(String abi, StringBuilder seen) {
        try {
            Log.i(TAG, "npatch abi=" + abi + " abis=" + seen);
            InjectLog.w(hostContext(), "LspVector: npatch abi=" + abi + " abis=" + seen);
        } catch (Throwable ignored) { }
    }

    private static Context hostContext() {
        try {
            Class<?> vc = Class.forName("com.lody.virtual.client.core.VirtualCore");
            Object core = vc.getMethod("get").invoke(null);
            Context c = (Context) vc.getMethod("getContext").invoke(core);
            if (c != null) return c;
        } catch (Throwable ignored) { }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app != null) return (Context) app;
        } catch (Throwable ignored) { }
        return null;
    }

    private static Set<String> selectedModules(Context host) {
        Set<String> out = new LinkedHashSet<>();
        try {
            SharedPreferences p = host.getSharedPreferences(LspHooks.PREFS, Context.MODE_PRIVATE);
            Set<String> s = p.getStringSet(LspHooks.KEY_MODULES, null);
            if (s != null) out.addAll(s);
        } catch (Throwable ignored) { }
        return out;
    }

    /** 写模块清单文件（每行 "pkg<TAB>apkPath"），驱动类在框架 ClassLoader 里读它 */
    private static String buildModulesFile(Context host, Set<String> selected) {
        PackageManager pm = host.getPackageManager();
        StringBuilder sb = new StringBuilder("# pkg\tapkPath\n");
        int n = 0;
        for (String pkg : selected) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai == null || ai.sourceDir == null) continue;
                if (!new File(ai.sourceDir).exists()) continue;
                sb.append(pkg).append('\t').append(ai.sourceDir).append('\n');
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "module not installed: " + pkg, t);
            }
        }
        if (n == 0) return null;
        try {
            File dir = new File(host.getFilesDir(), "npatch");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File f = new File(dir, "modules.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "write modules.txt failed", t);
            return null;
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            return data;
        } finally {
            raf.close();
        }
    }

    private static void setStaticField(Class<?> c, String name, Object v) throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void loadNative(Class<?> fromClass, String path) throws Throwable {
        // 关键：JNI_OnLoad 里的 FindClass 走的是「调用者类」的 ClassLoader，
        // 必须把它绑到载荷 ClassLoader，否则 RegisterNatives 找不到
        // org.matrix.vector.nativebridge.* 的类，ART 会拿到 null jclass 直接 abort。
        ClassLoader cl = fromClass.getClassLoader();
        Throwable last = null;

        // 1) 最可靠：直接调 Runtime.nativeLoad(filename, loader, caller)
        try {
            Method m = Runtime.class.getDeclaredMethod(
                    "nativeLoad", String.class, ClassLoader.class, Class.class);
            m.setAccessible(true);
            String err = (String) m.invoke(null, path, cl, fromClass);
            if (err != null) throw new UnsatisfiedLinkError("nativeLoad: " + err);
            InjectLog.w(hostContext(), "LspVector: nativeLoad ok " + path);
            return;
        } catch (Throwable t) {
            last = t;
        }

        // 2) load0(Class, String)
        try {
            Method load0 = Runtime.class.getDeclaredMethod("load0", Class.class, String.class);
            load0.setAccessible(true);
            load0.invoke(Runtime.getRuntime(), fromClass, path);
            InjectLog.w(hostContext(), "LspVector: load0(Class,String) ok");
            return;
        } catch (Throwable t) {
            last = t;
        }

        // 3) load0(String, Class)（部分 ROM 的参数顺序不同）
        try {
            Method load0 = Runtime.class.getDeclaredMethod("load0", String.class, Class.class);
            load0.setAccessible(true);
            load0.invoke(Runtime.getRuntime(), path, fromClass);
            InjectLog.w(hostContext(), "LspVector: load0(String,Class) ok");
            return;
        } catch (Throwable t) {
            last = t;
        }

        // 绝不再退化到 System.load：那会绑到 app 的 ClassLoader，必崩
        throw new UnsatisfiedLinkError("cannot load " + path + ": " + last);
    }

    private static File extractAsset(Context ctx, String assetName, File dst) {
        try {
            File dir = dst.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            if (dst.isFile() && dst.length() > 0) {
                dst.setReadable(true, false);
                return dst;
            }
            InputStream in = ctx.getAssets().open(assetName);
            try {
                FileOutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            dst.setReadable(true, false);
            return dst;
        } catch (Throwable t) {
            Log.e(TAG, "extract " + assetName + " failed", t);
            return null;
        }
    }


    /** 载荷专用 ClassLoader：框架包前缀优先自解析，其余走父加载器（能看到 guest 类） */
    private static final class PayloadLoader extends dalvik.system.DexClassLoader {
        PayloadLoader(String dexPath, ClassLoader parent) {
            super(dexPath, null, null, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (isFrameworkClass(name)) {
                synchronized (PayloadLoader.class) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException ignored) { }
                    }
                    if (c != null) {
                        if (resolve) resolveClass(c);
                        return c;
                    }
                }
            }
            return super.loadClass(name, resolve);
        }

        private static boolean isFrameworkClass(String name) {
            for (String p : CHILD_FIRST) {
                if (name.startsWith(p)) return true;
            }
            return false;
        }
    }
}
