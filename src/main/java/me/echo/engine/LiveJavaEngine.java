package me.echo.engine;

import me.echo.Echo;
import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.security.SecureClassLoader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LiveJavaEngine {

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(0);
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    /**
     * Compiles a raw Java code snippet in memory and returns an executable ConstructBehavior.
     */
    public static ConstructBehavior compile(String rawJavaCode) {
        if (rawJavaCode == null || rawJavaCode.trim().isEmpty()) {
            return null;
        }

        if (COMPILER == null) {
            Echo.LOGGER.error("[LiveJavaEngine] JDK JavaCompiler not found! Ensure Minecraft is running on a JDK, not a JRE.");
            return null;
        }

        String className = "DynamicConstructBehavior_" + CLASS_COUNTER.incrementAndGet();
        String fullClassName = "me.echo.engine.generated." + className;

        // Wrap Qwen's code snippet inside a complete Java class structure with standard Minecraft imports
        String sourceCode = """
                package me.echo.engine.generated;
                
                import me.echo.engine.ConstructBehavior;
                import me.echo.entity.ConstructEntity;
                import net.minecraft.server.level.ServerLevel;
                import net.minecraft.server.level.ServerPlayer;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.player.Player;
                import net.minecraft.world.entity.monster.Monster;
                import net.minecraft.world.phys.*;
                import net.minecraft.world.level.Level;
                import net.minecraft.core.particles.ParticleTypes;
                import java.util.*;
                
                public class %s implements ConstructBehavior {
                    @Override
                    public void tick(ConstructEntity construct, ServerLevel level, ServerPlayer owner) {
                        try {
                            %s
                        } catch (Throwable ignored) {}
                    }
                }
                """.formatted(className, rawJavaCode);

        try {
            JavaSourceFromString sourceObject = new JavaSourceFromString(fullClassName, sourceCode);
            MemoryJavaFileManager fileManager = new MemoryJavaFileManager(COMPILER.getStandardFileManager(null, null, null));

            List<String> options = new ArrayList<>();
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && !classPath.isEmpty()) {
                options.add("-classpath");
                options.add(classPath);
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavaCompiler.CompilationTask task = COMPILER.getTask(
                    null, fileManager, diagnostics, options, null, List.of(sourceObject)
            );

            boolean success = task.call();
            if (!success) {
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    Echo.LOGGER.warn("[LiveJavaEngine] AI Code Compile Error on line {}: {}", d.getLineNumber(), d.getMessage(null));
                }
                return null;
            }

            MemoryClassLoader classLoader = new MemoryClassLoader(fileManager.getCompiledBytes(), fullClassName);
            Class<?> compiledClass = classLoader.loadClass(fullClassName);
            return (ConstructBehavior) compiledClass.getDeclaredConstructor().newInstance();

        } catch (Throwable t) {
            Echo.LOGGER.error("[LiveJavaEngine] Failed to compile live AI Java snippet: {}", rawJavaCode, t);
            return null;
        }
    }

    // --- IN-MEMORY COMPILER AUXILIARY CLASSES ---

    private static class JavaSourceFromString extends SimpleJavaFileObject {
        private final String code;
        JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }

    private static class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> compiledBytes = new HashMap<>();
        MemoryJavaFileManager(JavaFileManager fileManager) { super(fileManager); }
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() throws IOException {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    compiledBytes.put(className, stream);
                    return stream;
                }
            };
        }
        Map<String, byte[]> getCompiledBytes() {
            Map<String, byte[]> result = new HashMap<>();
            compiledBytes.forEach((k, v) -> result.put(k, v.toByteArray()));
            return result;
        }
    }

    private static class MemoryClassLoader extends SecureClassLoader {
        private final Map<String, byte[]> compiledBytes;
        MemoryClassLoader(Map<String, byte[]> compiledBytes, String mainClassName) {
            super(LiveJavaEngine.class.getClassLoader());
            this.compiledBytes = compiledBytes;
        }
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = compiledBytes.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }
}