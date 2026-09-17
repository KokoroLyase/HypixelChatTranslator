package com.isomeria.hxtranslate.forge.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 把 {@code EntityPlayerSP.sendChatMessage(String)} 的方法头改成：
 *
 * <pre>
 *   if (HxHooks.onSendChatMessage(message)) {
 *       return;          // 拦下这次发送，交给模组翻译后再重发
 *   }
 *   ...原版逻辑一个字节都没动...
 * </pre>
 *
 * <p><b>为什么匹配「三种名字 + 描述符」而不是只写一种</b>：这个方法在不同阶段会用不同的
 * 命名层出现 —— 混淆名 {@code bew/e}（FML 的补丁文件里就是它）、SRG 名
 * {@code func_71165_d}（转换器最常见的视角）、MCP 名 {@code sendChatMessage}（开发环境）。
 * 只认一种的话，换一个 FML 版本或换一条加载路径就会静默失效。描述符
 * {@code (Ljava/lang/String;)V} 在任何命名层下都不变，所以用它做主判据、名字做辅助判据。
 *
 * <p><b>为什么不重写整个方法体</b>：那样等于把原版逻辑（新建 {@code C01PacketChatMessage}
 * 并塞进发送队列）抄一份到我们这边，原版一改就错，而且抄漏了还很难发现。
 * 现在只在头部插入一个早退分支：不拦的时候，原版指令一条都不动。
 *
 * <p><b>为什么用 {@code ClassWriter(0)} 而不是 {@code COMPUTE_FRAMES}</b>：
 * {@code COMPUTE_FRAMES} 会**重算整个方法**的栈帧，遇到复杂类型合并时一旦
 * {@code getCommonSuperClass} 拿不到准确的类层次（转换期加载类是有风险的），
 * 就会产出错误的帧，表现为 {@code VerifyError} —— 那是核心插件最经典的翻车方式。
 * 这里只往方法头塞了一条不改变原指令的路径，所以：保留原有全部栈帧，只给新分支目标
 * 手写一个帧，并把 maxStack 加 1（插入的代码最多多占一个槽）。
 */
public final class HxTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_MCP = "net.minecraft.client.entity.EntityPlayerSP";
    private static final String TARGET_CLASS_OBF = "bew";

    private static final Set<String> TARGET_METHODS = new HashSet<String>(Arrays.asList(
            "sendChatMessage",   // MCP
            "func_71165_d",      // SRG
            "e"                  // 混淆
    ));

    private static final String TARGET_DESC = "(Ljava/lang/String;)V";

    private static final String HOOKS = "com/isomeria/hxtranslate/forge/asm/HxHooks";
    private static final String HOOK_NAME = "onSendChatMessage";
    private static final String HOOK_DESC = "(Ljava/lang/String;)Z";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        if (!isTargetClass(name) && !isTargetClass(transformedName)) {
            return basicClass;
        }
        try {
            return patch(basicClass);
        } catch (Throwable t) {
            // 注入失败必须原样放行：宁可「发送方向不翻译」，也绝不能让游戏启动就崩。
            // 这里刻意 catch Throwable 而不是 RuntimeException —— 链接期错误是 Error。
            //
            // 但**绝不能静默**：核心插件失效时玩家只会看到「我打的中文没被翻译」，
            // 没有任何线索。用 System.err 而不是日志框架：这条路径执行得极早
            // （FML 还没初始化日志），碰任何游戏/日志类都可能再抛一次。
            System.err.println("[hxtranslate] EntityPlayerSP 字节码注入失败，"
                    + "发送方向将不翻译（其余功能不受影响）: " + t);
            t.printStackTrace();
            return basicClass;
        }
    }

    /**
     * 目标类判定。
     *
     * <p><b>坑（离线验证抓到过一次）</b>：{@code IClassTransformer.transform} 传进来的类名是
     * <b>点号分隔</b>的（{@code net.minecraft.client.entity.EntityPlayerSP}），
     * 而不是字节码里的斜杠内部名。一开始这里写的是斜杠形式，于是 MCP 名永远匹配不上 ——
     * 在游戏里的表现是「注入静默失效、发送方向完全不翻译」，而编译、构建、自检全是绿的。
     * 所以这里把两种写法都认，并把斜杠统一成点号再比。
     */
    private static boolean isTargetClass(String className) {
        if (className == null) {
            return false;
        }
        if (TARGET_CLASS_OBF.equals(className)) {
            return true;
        }
        return TARGET_CLASS_MCP.equals(className.replace('/', '.'));
    }

    private byte[] patch(byte[] basicClass) {
        ClassNode node = new ClassNode();
        // EXPAND_FRAMES 让帧以可编辑的形式读入（我们只加不改，但保持一致更安全）
        new ClassReader(basicClass).accept(node, ClassReader.EXPAND_FRAMES);

        boolean patched = false;
        for (int i = 0; i < node.methods.size(); i++) {
            // ASM 5.0.3（1.8.9 自带的那版）里 ClassNode.methods 是**裸 List**，
            // 所以必须显式转型；新版本 ASM 才有泛型。
            MethodNode method = (MethodNode) node.methods.get(i);
            if (!TARGET_DESC.equals(method.desc) || !TARGET_METHODS.contains(method.name)) {
                continue;
            }
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            injectHead(method, node.name);
            patched = true;
        }
        if (!patched) {
            // 没找到目标方法就别动这个类（原样返回，省得白白多一次写回）
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private void injectHead(MethodNode method, String ownerInternalName) {
        LabelNode original = new LabelNode();
        InsnList injected = new InsnList();
        injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
        injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, HOOK_NAME, HOOK_DESC, false));
        injected.add(new JumpInsnNode(Opcodes.IFEQ, original));
        injected.add(new InsnNode(Opcodes.RETURN));
        injected.add(original);
        // 分支目标必须有栈帧（class 版本 50+ 的校验要求）。
        // 这里的状态就是方法入口状态：局部变量 = [this, String]，操作数栈为空。
        injected.add(new FrameNode(Opcodes.F_FULL, 2,
                new Object[] {ownerInternalName, "java/lang/String"},
                0, new Object[0]));

        AbstractInsnNode first = method.instructions.getFirst();
        method.instructions.insertBefore(first, injected);
        // 插入的代码最多在栈上多停一个引用，所以 +1 足够；同时保底 2（ALOAD + 返回值）
        method.maxStack = Math.max(method.maxStack + 1, 2);
    }
}
