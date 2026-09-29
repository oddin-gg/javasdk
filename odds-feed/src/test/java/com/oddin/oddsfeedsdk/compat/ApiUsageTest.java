package com.oddin.oddsfeedsdk.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Source compatibility, checked by the compiler: code that uses the 0.0.x public API compiles against
 * this module.
 *
 * <p>The code is generated from the baseline jar: a call of every public method and constructor and a
 * read of every public field of every public API type, and a class implementing every public
 * interface. It is compiled twice, against the baseline - which shows the generated code is right - and
 * against this module. The second compile finds what the class-file comparison in {@link
 * PublicShapeTest} does not: a checked exception added to a method, an abstract method added to an
 * interface a client implements, a return type a caller cannot assign where it did. The old {@code
 * examples} are compiled the same way.
 *
 * <p>Calls go through raw types and typed nulls; generic signatures are PublicShapeTest's to compare.
 * The accepted differences in {@code api/differences.txt} are left out.
 */
class ApiUsageTest {

    private static Path generated;

    @BeforeAll
    static void generateTheUsage() throws Exception {
        PublicShapeTest.readBothSides();
        // under the module's target directory, where the build runs the tests; emptied first
        generated = Path.of("target", "api-usage", "sources").toAbsolutePath();
        if (Files.exists(generated.getParent())) {
            try (Stream<Path> old = Files.walk(generated.getParent())) {
                for (Path path : old.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(generated);
        var sources = new LinkedHashMap<String, String>();
        int index = 0;
        for (ClassModel type : PublicShapeTest.baseline.values()) {
            if (PublicShapeTest.inScope(type)
                    && !PublicShapeTest.NOT_HERE.containsKey(PublicShapeTest.relative(internalName(type)))) {
                sources.put("Usage" + index++, usage(type));
            }
        }
        for (var source : sources.entrySet()) {
            Files.writeString(generated.resolve(source.getKey() + ".java"), source.getValue());
        }
    }

    @Test
    void theGeneratedUsageIsRightForTheBaseline() throws IOException {
        // the control: were this to fail, the usage below would prove nothing
        assertThat(compile(javaFiles(generated), baselineClasspath()))
                .as("the generated usage, compiled against the baseline")
                .isEmpty();
    }

    @Test
    void codeUsingTheBaselineApiCompilesHere() throws IOException {
        assertThat(compile(javaFiles(generated), moduleClasspath()))
                .as(
                        "the generated usage of the %s API, compiled against this module",
                        System.getProperty("baseline.version"))
                .isEmpty();
    }

    @Test
    void theOldExamplesCompileHere() throws IOException {
        List<Path> examples = javaFiles(Path.of(System.getProperty("examples.sources")));
        assertThat(examples).as("the examples").isNotEmpty();
        assertThat(compile(examples, baselineClasspath()))
                .as("the examples against the baseline")
                .isEmpty();
        assertThat(compile(examples, moduleClasspath()))
                .as("the examples against this module")
                .isEmpty();
    }

    // ------------------------------------------------------------------ generation

    private static String usage(ClassModel type) {
        String name = sourceName(internalName(type));
        boolean isInterface = type.flags().has(AccessFlag.INTERFACE);
        var body = new StringBuilder();
        int n = 0;
        for (MethodModel method : PublicShapeTest.publicMethods(type).values()) {
            String key = PublicShapeTest.relative(internalName(type)) + "."
                    + method.methodName().stringValue() + " "
                    + method.methodType().stringValue();
            if (PublicShapeTest.MEMBERS_NOT_HERE.containsKey(key)
                    || !method.flags().has(AccessFlag.PUBLIC)
                    || method.methodName().stringValue().equals("<clinit>")) {
                continue;
            }
            String call = call(type, name, method);
            if (call != null) {
                body.append("    void m")
                        .append(n++)
                        .append("() {\n        ")
                        .append(call)
                        .append("\n    }\n\n");
            }
        }
        for (var field : PublicShapeTest.publicFields(type).values()) {
            String key = PublicShapeTest.relative(internalName(type)) + "."
                    + field.fieldName().stringValue() + " " + field.fieldType().stringValue();
            if (PublicShapeTest.MEMBERS_NOT_HERE.containsKey(key)) {
                continue;
            }
            String owner = field.flags().has(AccessFlag.STATIC) ? name : "((" + name + ") null)";
            body.append("    void f")
                    .append(n++)
                    .append("() {\n        ")
                    .append(source(field.fieldTypeSymbol()))
                    .append(" value = ")
                    .append(owner)
                    .append('.')
                    .append(field.fieldName().stringValue())
                    .append(";\n    }\n\n");
        }
        if (isInterface) {
            body.append(implementation(type, name));
        }
        return """
                package apiusage;

                @SuppressWarnings("all")
                class %s {
                %s}
                """.formatted("Usage_" + internalName(type).replaceAll("[/$]", "_"), body);
    }

    /** One statement using the method as a client would, or null for one a client cannot call. */
    private static @Nullable String call(ClassModel type, String name, MethodModel method) {
        MethodTypeDesc descriptor = method.methodTypeSymbol();
        String arguments =
                descriptor.parameterList().stream().map(ApiUsageTest::typedNull).collect(Collectors.joining(", "));
        String methodName = method.methodName().stringValue();
        if (methodName.equals("<init>")) {
            boolean creatable = !type.flags().has(AccessFlag.ABSTRACT)
                    && !type.flags().has(AccessFlag.INTERFACE)
                    && !type.flags().has(AccessFlag.ENUM);
            return creatable ? name + " value = new " + name + "(" + arguments + ");" : null;
        }
        String receiver = PublicShapeTest.isStatic(method) ? name : "((" + name + ") null)";
        String invocation = receiver + "." + methodName + "(" + arguments + ");";
        ClassDesc result = descriptor.returnType();
        return result.descriptorString().equals("V") ? invocation : source(result) + " value = " + invocation;
    }

    /** A class implementing the interface and every abstract method it has, its own and inherited. */
    private static String implementation(ClassModel type, String name) {
        var methods = new StringBuilder();
        for (MethodModel method : abstractMethods(type).values()) {
            MethodTypeDesc descriptor = method.methodTypeSymbol();
            var parameters = new ArrayList<String>();
            for (int i = 0; i < descriptor.parameterCount(); i++) {
                parameters.add(source(descriptor.parameterType(i)) + " p" + i);
            }
            ClassDesc result = descriptor.returnType();
            methods.append("        @Override public ")
                    .append(source(result))
                    .append(' ')
                    .append(method.methodName().stringValue())
                    .append('(')
                    .append(String.join(", ", parameters))
                    .append(") {")
                    .append(result.descriptorString().equals("V") ? "" : " return " + typedNull(result) + ";")
                    .append(" }\n");
        }
        return "    static final class Implementation implements " + name + " {\n" + methods + "    }\n";
    }

    /**
     * The interface's abstract methods and those of its superinterfaces, as the baseline declares them.
     * One per name and parameters: the nearest declaration wins, which is the one with the most
     * specific return type where a subinterface narrows it.
     */
    private static Map<String, MethodModel> abstractMethods(ClassModel type) {
        var methods = new LinkedHashMap<String, MethodModel>();
        var pending = new ArrayList<ClassModel>(List.of(type));
        while (!pending.isEmpty()) {
            ClassModel next = pending.removeFirst();
            for (MethodModel method : next.methods()) {
                if (method.flags().has(AccessFlag.ABSTRACT)) {
                    String descriptor = method.methodType().stringValue();
                    String parameters = descriptor.substring(0, descriptor.indexOf(')') + 1);
                    methods.putIfAbsent(method.methodName().stringValue() + parameters, method);
                }
            }
            next.interfaces().forEach(i -> {
                ClassModel parent = PublicShapeTest.baseline.get(i.asInternalName());
                if (parent != null) {
                    pending.add(parent);
                }
            });
        }
        return methods;
    }

    private static String typedNull(ClassDesc type) {
        return switch (type.descriptorString()) {
            case "Z" -> "false";
            case "C" -> "(char) 0";
            case "B" -> "(byte) 0";
            case "S" -> "(short) 0";
            case "I" -> "0";
            case "J" -> "0L";
            case "F" -> "0F";
            case "D" -> "0D";
            default -> "(" + source(type) + ") null";
        };
    }

    /** The type as source code: erased, nested classes with dots. */
    private static String source(ClassDesc type) {
        if (type.isArray()) {
            return source(type.componentType()) + "[]";
        }
        if (type.isPrimitive()) {
            return type.displayName();
        }
        String descriptor = type.descriptorString();
        return sourceName(descriptor.substring(1, descriptor.length() - 1));
    }

    private static String sourceName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    private static String internalName(ClassModel type) {
        return type.thisClass().asInternalName();
    }

    // ------------------------------------------------------------------ compiling

    /** The compiler's errors, each with the line it points at; warnings are not the question. */
    private static List<String> compile(List<Path> sources, List<Path> classpath) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            Path output = Files.createTempDirectory(generated.getParent(), "api-usage-classes");
            var options = List.of(
                    "-proc:none",
                    "-nowarn",
                    "-Xlint:none",
                    "-d",
                    output.toString(),
                    "-classpath",
                    classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
            compiler.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources))
                    .call();
        }
        var errors = new ArrayList<String>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(where(diagnostic) + ": " + diagnostic.getMessage(null));
            }
        }
        return errors;
    }

    private static String where(Diagnostic<? extends JavaFileObject> diagnostic) throws IOException {
        JavaFileObject file = diagnostic.getSource();
        if (file == null) {
            return "(no file)";
        }
        List<String> lines = Files.readAllLines(Path.of(file.toUri()));
        int line = (int) diagnostic.getLineNumber();
        String text = line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
        return Path.of(file.toUri()).getFileName() + ":" + line + " `" + text + "`";
    }

    /** The baseline jar and what its signatures and the examples need; never this module. */
    private static List<Path> baselineClasspath() {
        return List.of(Path.of(System.getProperty("baseline.jar")), jarOf("org.jetbrains.annotations.NotNull"));
    }

    /** This module's classes and dependencies, as the tests see them. */
    private static List<Path> moduleClasspath() {
        return Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }

    private static Path jarOf(String className) {
        try {
            return Path.of(Class.forName(className)
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (ReflectiveOperationException | java.net.URISyntaxException e) {
            throw new IllegalStateException("cannot find the jar of " + className, e);
        }
    }

    private static List<Path> javaFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }
}
