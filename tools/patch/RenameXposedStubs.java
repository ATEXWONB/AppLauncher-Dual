import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import java.io.*;
import java.util.zip.*;

/**
 * 把 aar classes.jar 里的 de/robv/android/xposed/* 全部改名到 com/lody/xpstub/*（含类名与引用）。
 * 目的：宿主 APK 的 PathClassLoader 不再占用 de.robv.android.xposed.* 这些类名，
 * guest 里 Vector 的 InMemoryDexClassLoader（父优先）才能解析到自己那份完整实现。
 */
public class RenameXposedStubs {

    static final String OLD = "de/robv/android/xposed/";
    static final String NEW = "com/lody/xpstub/xposed/";

    public static void main(String[] args) throws Exception {
        File aar = new File(args[0]);
        byte[] classesJar = null;
        ZipInputStream zis = new ZipInputStream(new FileInputStream(aar));
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if ("classes.jar".equals(e.getName())) classesJar = readAll(zis);
        }
        zis.close();
        if (classesJar == null) throw new IllegalStateException("classes.jar not found");

        byte[] patched = remapJar(classesJar);

        File tmp = new File(aar.getAbsolutePath() + ".new");
        ZipInputStream in = new ZipInputStream(new FileInputStream(aar));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmp));
        while ((e = in.getNextEntry()) != null) {
            out.putNextEntry(new ZipEntry(e.getName()));
            if ("classes.jar".equals(e.getName())) out.write(patched);
            else out.write(readAll(in));
            out.closeEntry();
        }
        out.close();
        in.close();
        if (!tmp.renameTo(aar)) {
            copy(tmp, aar);
            tmp.delete();
        }
        System.out.println("RENAMED xposed stubs -> " + NEW + " in " + aar.getAbsolutePath());
    }

    static byte[] remapJar(byte[] jar) throws Exception {
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName != null && internalName.startsWith(OLD))
                    return NEW + internalName.substring(OLD.length());
                return internalName;
            }
        };
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar));
        ZipOutputStream zo = new ZipOutputStream(bos);
        ZipEntry e;
        int changed = 0;
        while ((e = in.getNextEntry()) != null) {
            byte[] data = readAll(in);
            String name = e.getName();
            if (name.endsWith(".class")) {
                ClassReader cr = new ClassReader(data);
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassRemapper(cw, remapper), 0);
                byte[] out = cw.toByteArray();
                String newName = name;
                if (name.startsWith(OLD)) newName = NEW + name.substring(OLD.length());
                zo.putNextEntry(new ZipEntry(newName));
                zo.write(out);
                changed++;
            } else if (name.startsWith("META-INF/")) {
                continue;
            } else {
                zo.putNextEntry(new ZipEntry(name));
                zo.write(data);
            }
            zo.closeEntry();
        }
        zo.close();
        System.err.println("remapped " + changed + " class entries");
        return bos.toByteArray();
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) b.write(buf, 0, r);
        return b.toByteArray();
    }

    static void copy(File a, File b) throws IOException {
        InputStream i = new FileInputStream(a);
        OutputStream o = new FileOutputStream(b);
        byte[] buf = new byte[8192];
        int r;
        while ((r = i.read(buf)) > 0) o.write(buf, 0, r);
        i.close();
        o.close();
    }
}
