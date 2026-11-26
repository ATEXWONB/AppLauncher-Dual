import org.objectweb.asm.*;

import java.io.*;
import java.util.zip.*;

/**
 * 把 com/lody/virtual/server/pm/PackageSetting.isRunInExtProcess()Z 改成 return false。
 * 原因：aar 里 isRunInExtProcess = !is64bitPackage，而我们的单 APK 没有单独的 ext 包，
 * 一旦命中就 startProcessIfNeeded 直接返回 null（"ext package not install"），分身永远起不来。
 */
public class PatchExtProcess {

    public static void main(String[] args) throws Exception {
        String aarPath = args[0];
        String target = "com/lody/virtual/server/pm/PackageSetting";
        String methodName = "isRunInExtProcess";

        ZipFile zin = new ZipFile(aarPath);
        ZipEntry classesEntry = null;
        java.util.Enumeration<? extends ZipEntry> en = zin.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.getName().equals("classes.jar")) { classesEntry = e; break; }
        }
        if (classesEntry == null) throw new IllegalStateException("classes.jar not found");

        ByteArrayOutputStream jarBuf = new ByteArrayOutputStream();
        InputStream in = zin.getInputStream(classesEntry);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) jarBuf.write(buf, 0, n);
        in.close();

        ZipInputStream jzin = new ZipInputStream(new ByteArrayInputStream(jarBuf.toByteArray()));
        ByteArrayOutputStream outJar = new ByteArrayOutputStream();
        ZipOutputStream jzout = new ZipOutputStream(outJar);
        ZipEntry je;
        int patched = 0;
        while ((je = jzin.getNextEntry()) != null) {
            byte[] data = readAll(jzin);
            if (je.getName().equals(target + ".class")) {
                final boolean[] done = {false};
                ClassReader cr = new ClassReader(data);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        if (name.equals(methodName) && desc.equals("()Z")) {
                            done[0] = true;
                            return null; // 丢掉原方法
                        }
                        return super.visitMethod(access, name, desc, sig, ex);
                    }

                    @Override
                    public void visitEnd() {
                        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()Z", null, null);
                        mv.visitCode();
                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitInsn(Opcodes.IRETURN);
                        mv.visitMaxs(1, 1);
                        mv.visitEnd();
                        super.visitEnd();
                    }
                }, 0);
                data = cw.toByteArray();
                if (!done[0]) throw new IllegalStateException("method not found: " + methodName);
                patched++;
            }
            ZipEntry ne = new ZipEntry(je.getName());
            jzout.putNextEntry(ne);
            jzout.write(data);
            jzout.closeEntry();
            jzin.closeEntry();
        }
        jzin.close();
        jzout.close();
        zin.close();

        if (patched == 0) throw new IllegalStateException("patched 0 methods");

        String tmp = aarPath + ".tmp";
        ZipFile zin2 = new ZipFile(aarPath);
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp));
        java.util.Enumeration<? extends ZipEntry> en2 = zin2.entries();
        while (en2.hasMoreElements()) {
            ZipEntry e = en2.nextElement();
            if (e.getName().equals("classes.jar")) continue;
            ZipEntry ne = new ZipEntry(e.getName());
            zout.putNextEntry(ne);
            InputStream is = zin2.getInputStream(e);
            while ((n = is.read(buf)) > 0) zout.write(buf, 0, n);
            is.close();
            zout.closeEntry();
        }
        zin2.close();
        ZipEntry cj = new ZipEntry("classes.jar");
        zout.putNextEntry(cj);
        zout.write(outJar.toByteArray());
        zout.closeEntry();
        zout.close();
        File f = new File(aarPath);
        File t = new File(tmp);
        if (!t.renameTo(f)) {
            f.delete();
            if (!t.renameTo(f)) throw new IllegalStateException("rename failed");
        }
        System.out.println("PATCHED " + patched + " -> " + aarPath);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
