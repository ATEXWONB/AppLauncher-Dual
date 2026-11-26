import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.*;
import java.util.zip.*;

/** 在 VClient#bindApplicationMainThread 入口插入 LspVector.onBindApplicationStart(pkg, process)。 */
public class PatchVClient {

    static final String TARGET = "com/lody/virtual/client/VClient.class";
    static final String METHOD = "bindApplicationMainThread";
    static final String DESC = "(Ljava/lang/String;Ljava/lang/String;Landroid/os/ConditionVariable;)V";
    static final String HOOK = "com/applauncher/dual/LspVector";
    static final String HOOK_METHOD = "onBindApplicationStart";
    static final String HOOK_DESC = "(Ljava/lang/String;Ljava/lang/String;)V";

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

        byte[] patchedJar = patchJar(classesJar);
        File tmp = new File(aar.getAbsolutePath() + ".new");
        ZipInputStream in = new ZipInputStream(new FileInputStream(aar));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmp));
        while ((e = in.getNextEntry()) != null) {
            out.putNextEntry(new ZipEntry(e.getName()));
            if ("classes.jar".equals(e.getName())) out.write(patchedJar);
            else out.write(readAll(in));
            out.closeEntry();
        }
        out.close();
        in.close();
        if (!tmp.renameTo(aar)) { copy(tmp, aar); tmp.delete(); }
        System.out.println("PATCHED VClient -> " + aar.getAbsolutePath());
    }

    static byte[] patchJar(byte[] jar) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar));
        ZipOutputStream zo = new ZipOutputStream(out);
        ZipEntry e;
        boolean patched = false;
        while ((e = in.getNextEntry()) != null) {
            byte[] d = readAll(in);
            if (TARGET.equals(e.getName())) { d = patchClass(d); patched = true; }
            zo.putNextEntry(new ZipEntry(e.getName()));
            zo.write(d);
            zo.closeEntry();
        }
        zo.close();
        in.close();
        if (!patched) throw new IllegalStateException("VClient.class not found");
        return out.toByteArray();
    }

    static byte[] patchClass(byte[] data) {
        ClassReader cr = new ClassReader(data);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        boolean done = false;
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (METHOD.equals(m.name) && DESC.equals(m.desc)) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, HOOK_METHOD, HOOK_DESC, false));
                m.instructions.insert(il);
                done = true;
                System.out.println("  inserted hook at entry of " + METHOD);
            }
        }
        if (!done) throw new IllegalStateException("bindApplicationMainThread not found");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}
