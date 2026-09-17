package com.semmle.inmemory.alloc;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Read-only observer for a benign, owned GitHub Actions layout measurement. */
public final class CodeqlLayoutAgent {
  private CodeqlLayoutAgent() {}

  public static void premain(String ignored, Instrumentation instrumentation) {
    instrumentation.addTransformer(new Transformer(), false);
  }

  public static void arena(long base, long bytes) {
    System.err.printf(
        "HOSTED_LAYOUT_ENV malloc_arena_max=%s processors=%d%n",
        System.getenv().getOrDefault("MALLOC_ARENA_MAX", "<unset>"),
        Runtime.getRuntime().availableProcessors());
    System.err.printf(
        "HOSTED_LAYOUT_ARENA base=0x%x end=0x%x bytes=0x%x%n",
        base, base + bytes, bytes);
    try {
      for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
        String[] fields = line.trim().split("\\s+", 6);
        if (fields.length < 2 || !fields[1].startsWith("rwx")) continue;
        String[] range = fields[0].split("-", 2);
        long start = Long.parseUnsignedLong(range[0], 16);
        long end = Long.parseUnsignedLong(range[1], 16);
        System.err.printf(
            "HOSTED_LAYOUT_RWX start=0x%x end=0x%x delta=0x%x size=0x%x%n",
            start, end, start - base, end - start);
      }
    } catch (Throwable error) {
      System.err.println("HOSTED_LAYOUT_ERROR " + error);
    }
  }

  private static final class Transformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer) {
      if (!className.equals("com/semmle/inmemory/alloc/OffHeapBulkProvider")) return null;

      ClassReader reader = new ClassReader(classfileBuffer);
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
      ClassVisitor visitor =
          new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions) {
              MethodVisitor downstream =
                  super.visitMethod(access, name, descriptor, signature, exceptions);
              if (!name.equals("<init>") || !descriptor.equals("(J)V")) return downstream;
              return new MethodVisitor(Opcodes.ASM9, downstream) {
                @Override
                public void visitInsn(int opcode) {
                  if (opcode == Opcodes.RETURN) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(
                        Opcodes.GETFIELD, className, "baseAddress", "J");
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(
                        Opcodes.GETFIELD, className, "numberOfBytes", "J");
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "com/semmle/inmemory/alloc/CodeqlLayoutAgent",
                        "arena",
                        "(JJ)V",
                        false);
                  }
                  super.visitInsn(opcode);
                }
              };
            }
          };
      reader.accept(visitor, 0);
      return writer.toByteArray();
    }
  }
}
