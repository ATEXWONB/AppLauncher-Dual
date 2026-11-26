import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.util.zip.*;

/** 把 com/carlos/common/reverse/wechat/WeChat.hook(...) 改成空实现（只影响日志级别 hook，不影响微信功能） */
public class PatchWeChatHook {

    static final String TARGET = "com/carlos/common/reverse/wechat/WeChat.class";

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

        // 重写 aar：其他条目原样复制，classes.jar 替换
        File tmp = new File(aar.getAbsolutePath() + ".new");
        ZipInputStream in = new ZipInputStream(new FileInputStream(aar));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmp));
        while ((e = in.getNextEntry()) != null) {
            out.putNextEntry(new ZipEntry(e.getName()));
            if ("classes.jar".equals(e.getName())) out.write(patchedJar);
            else {
                byte[] d = readAll(in);
                out.write(d);
            }
            out.closeEntry();
        }
        out.close();
        in.close();
        if (!tmp.renameTo(aar)) {
            // renameTo 可能失败，退回复制
            copy(tmp, aar);
            tmp.delete();
        }
        System.out.println("PATCHED " + aar.getAbsolutePath());
    }

    static byte[] patchJar(byte[] jar) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar));
        ZipOutputStream zo = new ZipOutputStream(out);
        ZipEntry e;
        boolean patched = false;
        while ((e = in.getNextEntry()) != null) {
            byte[] d = readAll(in);
            if (TARGET.equals(e.getName())) {
                d = patchClass(d);
                patched = true;
            }
            zo.putNextEntry(new ZipEntry(e.getName()));
            zo.write(d);
            zo.closeEntry();
        }
        zo.close();
        in.close();
        if (!patched) throw new IllegalStateException("WeChat.class not found in classes.jar");
        return out.toByteArray();
    }

    static byte[] patchClass(byte[] data) {
        ClassReader cr = new ClassReader(data);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        boolean done = false;
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if ("hook".equals(m.name)
                    && "(Ljava/lang/ClassLoader;Landroid/app/Application;)V".equals(m.desc)) {
                m.instructions.clear();
                m.instructions.add(new InsnNode(Opcodes.RETURN));
                m.tryCatchBlocks.clear();
                m.localVariables = null;
                m.maxStack = 0;
                m.maxLocals = 3;
                done = true;
            }
        }
        if (!done) throw new IllegalStateException("hook(...) not found");
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
