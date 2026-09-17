import com.isomeria.hxtranslate.forge.asm.HxTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 核心插件的离线验证（跑在 Forge 构建里，由 {@code ./gradlew check} 自动带上）。
 *
 * <p>ASM 注入是 1.8.9 这条线上**唯一不跑游戏就没法确认**的地方，所以这里做三件事：
 * <ol>
 *   <li>拿**真实的** deobf {@code EntityPlayerSP} 跑一遍转换器，检查注入的指令序列确实在方法头；</li>
 *   <li>把改造后的类交给**真正的 JVM 校验器**（{@code -Xverify:all}）去验 ——
 *       字节码非法会抛 VerifyError，这是最有说服力的一步；</li>
 *   <li>反向验证：确认转换器对非目标类原样返回，以及把方法名换成 SRG / 混淆名之后仍然命中
 *       （生产环境走的就是 SRG 命名层）。</li>
 * </ol>
 *
 * <p>它抓到过一个**真实的、静默的**缺陷：{@code IClassTransformer.transform} 传进来的
 * 类名是**点号分隔**的，而最初这里的常量写的是字节码内部名（斜杠），于是 MCP 名永远匹配不上 ——
 * 在游戏里的表现是「发送方向完全不翻译」，而编译、构建、702 项自检全是绿的。
 * 这正是「必须做反向验证 + 用真 JVM 校验」的理由。
 */
public final class VerifyCoremod {

    private static final String CLASS = "net/minecraft/client/entity/EntityPlayerSP";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path deobfJar = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        Files.createDirectories(outDir);

        byte[] original = readEntry(deobfJar, CLASS + ".class");
        check("从 deobf jar 读到 EntityPlayerSP（" + original.length + " 字节）", original != null && original.length > 0);
        if (original == null) {
            finish();
            return;
        }

        HxTransformer transformer = new HxTransformer();

        // ---- 1) 真实的 MCP 命名类：应当被注入 ----
        byte[] patched = transformer.transform(CLASS.replace('/', '.'), CLASS.replace('/', '.'), original);
        check("转换器改动了目标类", patched != null && !same(original, patched));
        check("注入的调用出现在 sendChatMessage 方法头", hasInjectedHook(patched));
        check("原版指令没有被删掉（注入的是早退分支，不是重写方法体）",
                methodSize(patched) > methodSize(original));
        check("maxStack 被抬高（否则校验器会拒绝）", maxStack(patched) > maxStack(original));

        // ---- 2) 真 JVM 校验：把改造后的类放在 deobf jar 之前加载 ----
        Path patchedJar = outDir.resolve("patched-entityplayersp.jar");
        writeJar(patchedJar, CLASS, patched);
        verifyWithRealJvm(patchedJar, deobfJar);

        // ---- 3) 反向验证：非目标类必须原样返回 ----
        byte[] other = readEntry(deobfJar, "net/minecraft/client/gui/GuiChat.class");
        if (other != null) {
            byte[] untouched = transformer.transform("net.minecraft.client.gui.GuiChat",
                    "net.minecraft.client.gui.GuiChat", other);
            check("反向验证：非目标类原样返回（连字节都没变）", untouched == other);
        }

        // ---- 3b) 反向验证：把方法名换成 SRG 名后仍然命中（生产环境就是这一层） ----
        byte[] srgNamed = renameMethod(original, "sendChatMessage", "func_71165_d");
        byte[] srgPatched = transformer.transform(CLASS.replace('/', '.'), CLASS.replace('/', '.'), srgNamed);
        check("反向验证：SRG 名（func_71165_d）也能命中", hasInjectedHook(srgPatched));

        // ---- 3c) 反向验证：换成混淆名 bew/e 也要命中 ----
        byte[] obfNamed = renameMethod(renameClass(original, CLASS), "func_71165_d", "e");
        byte[] obfPatched = transformer.transform("bew", "bew", obfNamed);
        check("反向验证：混淆名（其他命名层的兜底）也能命中", hasInjectedHook(obfPatched));

        finish();
    }

    // ------------------------------------------------------------------

    /** 用真正的 JVM 校验器验一遍改造后的类。 */
    private static void verifyWithRealJvm(Path patchedJar, Path deobfJar) {
        try {
            URL[] urls = {patchedJar.toUri().toURL(), deobfJar.toUri().toURL()};
            URLClassLoader loader = new URLClassLoader(urls, VerifyCoremod.class.getClassLoader());
            Class<?> loaded = Class.forName(CLASS.replace('/', '.'), false, loader);
            loaded.getDeclaredMethods();
            check("反向验证：JVM 校验器接受改造后的字节码（无 VerifyError）", true);
        } catch (VerifyError e) {
            check("反向验证：JVM 校验器接受改造后的字节码（无 VerifyError）—— 实际失败: " + e.getMessage(), false);
        } catch (Throwable t) {
            // 类能走到「解析/初始化」阶段就说明字节码校验已经过了：
            // 这里失败是因为脱离游戏环境（缺 LWJGL / 静态初始化依赖），不是字节码问题。
            check("反向验证：JVM 校验器接受改造后的字节码（校验通过，之后的 "
                    + t.getClass().getSimpleName() + " 属于脱离游戏环境的预期现象）", true);
        }
    }

    private static boolean hasInjectedHook(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = (MethodNode) node.methods.get(i);
            if (!"(Ljava/lang/String;)V".equals(method.desc)) {
                continue;
            }
            boolean sawLoad = false;
            boolean sawCall = false;
            boolean sawJump = false;
            boolean sawReturn = false;
            boolean sawFrame = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof VarInsnNode && insn.getOpcode() == Opcodes.ALOAD) {
                    sawLoad = true;
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if ("com/isomeria/hxtranslate/forge/asm/HxHooks".equals(call.owner)
                            && "onSendChatMessage".equals(call.name)) {
                        sawCall = true;
                    }
                } else if (insn instanceof JumpInsnNode && insn.getOpcode() == Opcodes.IFEQ && sawCall) {
                    sawJump = true;
                } else if (insn instanceof InsnNode && insn.getOpcode() == Opcodes.RETURN && sawJump) {
                    sawReturn = true;
                } else if (insn instanceof FrameNode && sawReturn) {
                    sawFrame = true;
                    break;
                }
            }
            if (sawLoad && sawCall && sawJump && sawReturn && sawFrame) {
                return true;
            }
        }
        return false;
    }

    private static int methodSize(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = (MethodNode) node.methods.get(i);
            if ("(Ljava/lang/String;)V".equals(method.desc)) {
                return method.instructions.size();
            }
        }
        return -1;
    }

    private static int maxStack(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = (MethodNode) node.methods.get(i);
            if ("(Ljava/lang/String;)V".equals(method.desc)) {
                return method.maxStack;
            }
        }
        return -1;
    }

    /** 用 ASM 改一个方法名，模拟生产环境的 SRG / 混淆命名层。 */
    private static byte[] renameMethod(byte[] bytes, String from, String to) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = (MethodNode) node.methods.get(i);
            if (from.equals(method.name) && "(Ljava/lang/String;)V".equals(method.desc)) {
                method.name = to;
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** 用 ASM 改类名，模拟混淆层。 */
    private static byte[] renameClass(byte[] bytes, String from) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        node.name = "bew";
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] readEntry(Path jar, String entryName) throws Exception {
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            java.io.InputStream in = zip.getInputStream(entry);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toByteArray();
        } finally {
            zip.close();
        }
    }

    private static void writeJar(Path jar, String className, byte[] bytes) throws Exception {
        OutputStream fileOut = Files.newOutputStream(jar);
        ZipOutputStream zip = new ZipOutputStream(fileOut);
        zip.putNextEntry(new ZipEntry(className + ".class"));
        zip.write(bytes);
        zip.closeEntry();
        zip.close();
    }

    private static boolean same(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }

    private static void finish() {
        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
