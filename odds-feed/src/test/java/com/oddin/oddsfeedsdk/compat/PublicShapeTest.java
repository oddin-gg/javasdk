package com.oddin.oddsfeedsdk.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Compares the public types this module declares with the ones the 0.0.x release had.
 *
 * <p>Both sides are read as class files, never loaded: the baseline's classes have the same names
 * as ours. For every public type of the baseline in {@link #PACKAGES}, minus the ones listed in
 * {@link #NOT_HERE}, this module must have a type of the same kind and generic shape, and every
 * public member with the same name, erased signature, generic signature and nullability. Nullability
 * matters because Kotlin clients read it: the baseline carries it as JetBrains annotations, this
 * module as JSpecify ones.
 *
 * <p>Every difference the design accepts is a line in {@code api/differences.txt}, so a reviewer sees
 * each of them and the release notes can list them, and a new one fails the build until someone adds
 * it there on purpose.
 */
class PublicShapeTest {

    private static final String ROOT = "com/oddin/oddsfeedsdk/";

    /** The packages this module declares. */
    private static final Set<String> PACKAGES = Set.of(
            ROOT + "api/entities",
            ROOT + "api/entities/sportevent",
            ROOT + "api/factories",
            ROOT + "cache",
            ROOT + "exceptions",
            ROOT + "mq",
            ROOT + "mq/entities",
            ROOT + "schema/feed/v1",
            ROOT + "schema/utils");

    /** Packages that were Java in 0.0.x already: their callers never had nullability to keep. */
    private static final Set<String> UNMARKED = Set.of(ROOT + "schema/feed/v1", ROOT + "schema/utils");

    /**
     * Kotlin compiler output that has no Java counterpart and that no client code names: interface
     * default holders, when-mapping tables, file facades, lambdas and local classes, and every
     * implementation class.
     */
    private static final Pattern KOTLIN_OR_IMPL = Pattern.compile(
            ".*(\\$DefaultImpls|\\$WhenMappings.*|Kt|Kt\\$.*|\\$\\d+|\\$[a-z][A-Za-z]*\\$\\d+.*)"
                    + "|.*Impl(\\$.*)?");

    /** In the cache package only the static data types are reachable from the entities; the rest is the cache itself. */
    private static final Set<String> CACHE_TYPES = Set.of(ROOT + "cache/StaticData", ROOT + "cache/LocalizedStaticData");

    /** The accepted differences, from {@code api/differences.txt}; see its header. */
    private static final Differences DIFFERENCES = Differences.read("/api/differences.txt");

    /** Public types of the baseline that are not here, and why. */
    private static final Map<String, String> NOT_HERE = DIFFERENCES.types("gone");

    /** Public members of the baseline that are not here, and why. Keys are {@code type.name descriptor}. */
    private static final Map<String, String> MEMBERS_NOT_HERE = DIFFERENCES.members("gone");

    /** Public types here that the baseline did not have. */
    private static final Set<String> ADDED_TYPES = DIFFERENCES.types("added").keySet();

    /** Public members here that the baseline did not have. */
    private static final Set<String> ADDITIONS = DIFFERENCES.members("added").keySet();

    /** Generic signatures that differ on purpose, member to its new signature. */
    private static final Map<String, String> SIGNATURE_CHANGES = DIFFERENCES.signatures();

    /** Members deprecated here that the baseline did not deprecate. */
    private static final Set<String> NEWLY_DEPRECATED = DIFFERENCES.members("deprecated").keySet();

    /** Kotlin data class members Java has no use for; the design accepts losing them. */
    private static final Pattern DATA_CLASS_EXTRAS = Pattern.compile("component\\d+|copy|copy\\$default");

    private static final String KOTLIN_MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;";
    private static final String JETBRAINS_NULLABLE = "Lorg/jetbrains/annotations/Nullable;";
    private static final String JETBRAINS_NOT_NULL = "Lorg/jetbrains/annotations/NotNull;";
    private static final String JSPECIFY_NULLABLE = "Lorg/jspecify/annotations/Nullable;";
    private static final String JSPECIFY_NULL_MARKED = "Lorg/jspecify/annotations/NullMarked;";

    private static Map<String, ClassModel> baseline;
    private static Map<String, ClassModel> current;

    @BeforeAll
    static void readBothSides() throws Exception {
        baseline = readBaseline();
        current = readCurrent();
    }

    @Test
    void everyBaselineTypeHasACounterpartOfTheSameShape() {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (ClassModel old : baseline.values()) {
            String name = old.thisClass().asInternalName();
            if (!inScope(old) || NOT_HERE.containsKey(relative(name))) {
                continue;
            }
            compared++;
            ClassModel now = current.get(name);
            if (now == null) {
                problems.add(relative(name) + ": missing");
                continue;
            }
            compareType(old, now, problems);
            compareMembers(old, now, problems);
        }
        // A baseline that yields nothing would pass everything below.
        assertThat(compared).as("baseline types compared").isGreaterThan(60);
        assertThat(problems).as("differences from the %s public API", System.getProperty("baseline.version")).isEmpty();
    }

    @Test
    void everyPublicTypeHereExistedInTheBaseline() {
        Set<String> extra = new TreeSet<>();
        for (ClassModel now : current.values()) {
            String name = now.thisClass().asInternalName();
            // internal packages are never public API, whatever their classes' modifiers say
            if (isPublic(now) && !name.endsWith("/package-info") && !name.contains("/internal/")
                    && !baseline.containsKey(name) && !ADDED_TYPES.contains(relative(name))) {
                extra.add(relative(name));
            }
        }
        assertThat(extra).as("public types without a baseline counterpart").isEmpty();
    }

    /**
     * The nullability comparison reads a missing {@code @Nullable} as not null, which holds only
     * inside a {@code @NullMarked} package. Without it Kotlin callers would see platform types
     * everywhere while every comparison above still passed. The packages that were Java already are
     * the exception: their callers saw platform types before too.
     */
    @Test
    void everyPackageThatWasKotlinIsNullMarked() {
        for (String pkg : PACKAGES) {
            ClassModel info = current.get(pkg + "/package-info");
            boolean marked = info != null && info.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .map(a -> a.annotations().stream().anyMatch(an -> an.className().stringValue().equals(JSPECIFY_NULL_MARKED)))
                    .orElse(false);
            assertThat(marked).as("%s is @NullMarked", relative(pkg)).isEqualTo(!UNMARKED.contains(pkg));
        }
    }

    @Test
    void theListsAboveNameOnlyWhatExists() {
        // A stale entry would quietly allow a difference nobody still needs.
        assertThat(NOT_HERE.keySet()).allSatisfy(name -> assertThat(baseline).containsKey(ROOT + name));
        assertThat(NOT_HERE.keySet()).allSatisfy(name -> assertThat(current).doesNotContainKey(ROOT + name));
        assertThat(ADDED_TYPES).allSatisfy(name -> assertThat(current).containsKey(ROOT + name));
        assertThat(ADDED_TYPES).allSatisfy(name -> assertThat(baseline).doesNotContainKey(ROOT + name));
        Set<String> baselineMembers = members(baseline);
        Set<String> currentMembers = members(current);
        assertThat(baselineMembers).containsAll(MEMBERS_NOT_HERE.keySet());
        assertThat(baselineMembers).containsAll(SIGNATURE_CHANGES.keySet());
        assertThat(baselineMembers).containsAll(NEWLY_DEPRECATED);
        assertThat(currentMembers).doesNotContainAnyElementsOf(MEMBERS_NOT_HERE.keySet());
        assertThat(currentMembers).containsAll(ADDITIONS);
        assertThat(baselineMembers).doesNotContainAnyElementsOf(ADDITIONS);
    }

    // ------------------------------------------------------------------ comparison

    private static void compareType(ClassModel old, ClassModel now, List<String> problems) {
        String name = relative(old.thisClass().asInternalName());
        if (!isPublic(now)) {
            problems.add(name + ": not public");
        }
        if (!kind(old).equals(kind(now))) {
            problems.add(name + ": is " + kind(now) + ", was " + kind(old));
        }
        String oldSuper = old.superclass().map(ClassEntry::asInternalName).orElse("");
        String newSuper = now.superclass().map(ClassEntry::asInternalName).orElse("");
        if (!oldSuper.equals(newSuper)) {
            problems.add(name + ": extends " + newSuper + ", was " + oldSuper);
        }
        if (!interfaces(old).equals(interfaces(now))) {
            problems.add(name + ": implements " + interfaces(now) + ", was " + interfaces(old));
        }
        if (!signature(old).equals(signature(now))) {
            problems.add(name + ": generic signature " + signature(now) + ", was " + signature(old));
        }
    }

    private static void compareMembers(ClassModel old, ClassModel now, List<String> problems) {
        String type = relative(old.thisClass().asInternalName());
        Map<String, MethodModel> newMethods = publicMethods(now);
        Map<String, MethodModel> oldMethods = publicMethods(old);
        for (Map.Entry<String, MethodModel> entry : oldMethods.entrySet()) {
            String key = type + "." + entry.getKey();
            if (MEMBERS_NOT_HERE.containsKey(key)) {
                continue;
            }
            MethodModel before = entry.getValue();
            MethodModel after = newMethods.get(entry.getKey());
            if (after == null) {
                problems.add(key + ": missing");
                continue;
            }
            if (isStatic(before) != isStatic(after)) {
                problems.add(key + ": static " + isStatic(after) + ", was " + isStatic(before));
            }
            String expected = SIGNATURE_CHANGES.getOrDefault(key, signature(before));
            if (!expected.equals(signature(after))) {
                problems.add(key + ": generic signature " + signature(after) + ", expected " + expected);
            }
            boolean deprecated = isDeprecated(before) || NEWLY_DEPRECATED.contains(key);
            if (deprecated != isDeprecated(after)) {
                problems.add(key + ": deprecated " + isDeprecated(after) + ", expected " + deprecated);
            }
            compareNullability(key, before, after, problems);
        }
        for (String member : newMethods.keySet()) {
            String key = type + "." + member;
            if (!oldMethods.containsKey(member) && !ADDITIONS.contains(key)) {
                problems.add(key + ": not in the baseline");
            }
        }
        Map<String, FieldModel> newFields = publicFields(now);
        Map<String, FieldModel> oldFields = publicFields(old);
        for (var entry : oldFields.entrySet()) {
            String key = type + "." + entry.getKey();
            FieldModel after = newFields.get(entry.getKey());
            if (after == null) {
                if (!MEMBERS_NOT_HERE.containsKey(key)) {
                    problems.add(key + ": missing");
                }
                continue;
            }
            boolean deprecated = isDeprecated(entry.getValue()) || NEWLY_DEPRECATED.contains(key);
            if (deprecated != isDeprecated(after)) {
                problems.add(key + ": deprecated " + isDeprecated(after) + ", expected " + deprecated);
            }
        }
        for (String field : newFields.keySet()) {
            String key = type + "." + field;
            if (!oldFields.containsKey(field) && !ADDITIONS.contains(key)) {
                problems.add(key + ": not in the baseline");
            }
        }
    }

    /**
     * Where the baseline says nullable or not null, this module must say the same. Where it says
     * nothing - primitives, and the classes that were Java already - there is nothing to keep.
     */
    private static void compareNullability(String key, MethodModel before, MethodModel after, List<String> problems) {
        Optional<Boolean> oldReturn = kotlinNullness(before.findAttribute(Attributes.runtimeInvisibleAnnotations())
                .map(a -> a.annotations()).orElse(List.of()));
        if (oldReturn.isPresent() && oldReturn.get() != jspecifyNullable(after, -1)) {
            problems.add(key + ": return nullable " + jspecifyNullable(after, -1) + ", was " + oldReturn.get());
        }
        List<List<Annotation>> oldParameters = before.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                .map(a -> a.parameterAnnotations()).orElse(List.of());
        for (int i = 0; i < oldParameters.size(); i++) {
            Optional<Boolean> oldParameter = kotlinNullness(oldParameters.get(i));
            if (oldParameter.isPresent() && oldParameter.get() != jspecifyNullable(after, i)) {
                problems.add(key + ": parameter " + i + " nullable " + jspecifyNullable(after, i) + ", was " + oldParameter.get());
            }
        }
    }

    private static Optional<Boolean> kotlinNullness(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            String descriptor = annotation.className().stringValue();
            if (descriptor.equals(JETBRAINS_NULLABLE)) {
                return Optional.of(true);
            }
            if (descriptor.equals(JETBRAINS_NOT_NULL)) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    /** Whether the return type (index -1) or a parameter carries JSpecify's {@code @Nullable} at its top level. */
    private static boolean jspecifyNullable(MethodModel method, int parameter) {
        List<TypeAnnotation> annotations = method.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
                .map(a -> a.annotations()).orElse(List.of());
        for (TypeAnnotation annotation : annotations) {
            if (!annotation.targetPath().isEmpty()
                    || !annotation.annotation().className().stringValue().equals(JSPECIFY_NULLABLE)) {
                continue;
            }
            boolean matches = parameter < 0
                    ? annotation.targetInfo() instanceof TypeAnnotation.EmptyTarget
                            && annotation.targetInfo().targetType() == TypeAnnotation.TargetType.METHOD_RETURN
                    : annotation.targetInfo() instanceof TypeAnnotation.FormalParameterTarget target
                            && target.formalParameterIndex() == parameter;
            if (matches) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ class file helpers

    private static boolean inScope(ClassModel model) {
        String name = model.thisClass().asInternalName();
        String pkg = name.substring(0, name.lastIndexOf('/'));
        if (!isPublic(model) || !PACKAGES.contains(pkg)) {
            return false;
        }
        if (pkg.equals(ROOT + "cache") && !CACHE_TYPES.contains(name)) {
            return false;
        }
        return !KOTLIN_OR_IMPL.matcher(name.substring(pkg.length() + 1)).matches();
    }

    private static Map<String, MethodModel> publicMethods(ClassModel model) {
        Map<String, MethodModel> methods = new TreeMap<>();
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            String descriptor = method.methodType().stringValue();
            boolean visible = method.flags().has(AccessFlag.PUBLIC) || method.flags().has(AccessFlag.PROTECTED);
            if (!visible || method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE)
                    || DATA_CLASS_EXTRAS.matcher(name).matches() || name.endsWith("$annotations")
                    || descriptor.contains(KOTLIN_MARKER)) {
                continue;
            }
            methods.put(name + " " + descriptor, method);
        }
        return methods;
    }

    private static Map<String, FieldModel> publicFields(ClassModel model) {
        Map<String, FieldModel> fields = new TreeMap<>();
        for (FieldModel field : model.fields()) {
            if (field.flags().has(AccessFlag.PUBLIC) && !field.flags().has(AccessFlag.SYNTHETIC)) {
                fields.put(field.fieldName().stringValue() + " " + field.fieldType().stringValue(), field);
            }
        }
        return fields;
    }

    /** Every public member of every type, as {@code type.name descriptor}. */
    private static Set<String> members(Map<String, ClassModel> classes) {
        Set<String> members = new TreeSet<>();
        for (ClassModel model : classes.values()) {
            String type = relative(model.thisClass().asInternalName());
            publicMethods(model).keySet().forEach(m -> members.add(type + "." + m));
            publicFields(model).keySet().forEach(f -> members.add(type + "." + f));
        }
        return members;
    }

    private static String kind(ClassModel model) {
        if (model.flags().has(AccessFlag.ANNOTATION)) {
            return "annotation";
        }
        if (model.flags().has(AccessFlag.INTERFACE)) {
            return "interface";
        }
        if (model.flags().has(AccessFlag.ENUM)) {
            return "enum";
        }
        return model.flags().has(AccessFlag.ABSTRACT) ? "abstract class" : "class";
    }

    private static Set<String> interfaces(ClassModel model) {
        return model.interfaces().stream().map(ClassEntry::asInternalName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String signature(ClassModel model) {
        return model.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse("");
    }

    private static String signature(MethodModel method) {
        return method.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse("");
    }

    private static boolean isDeprecated(MethodModel method) {
        return method.findAttribute(Attributes.deprecated()).isPresent();
    }

    private static boolean isDeprecated(FieldModel field) {
        return field.findAttribute(Attributes.deprecated()).isPresent();
    }

    private static boolean isPublic(ClassModel model) {
        return model.flags().has(AccessFlag.PUBLIC);
    }

    private static boolean isStatic(MethodModel method) {
        return method.flags().has(AccessFlag.STATIC);
    }

    private static String relative(String internalName) {
        return internalName.startsWith(ROOT) ? internalName.substring(ROOT.length()) : internalName;
    }

    // ------------------------------------------------------------------ reading

    /** The baseline jar, after checking it is the artifact the system tests pin. */
    private static Map<String, ClassModel> readBaseline() throws IOException, NoSuchAlgorithmException {
        Path jar = Path.of(System.getProperty("baseline.jar"));
        String version = System.getProperty("baseline.version");
        assertThat(jar).as("the %s jar - run scripts/fetch-sdk.sh first", version).isRegularFile();

        Properties pins = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(System.getProperty("baseline.checksums")))) {
            pins.load(in);
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        assertThat(digest).as("SHA-256 of %s", jar).isEqualTo(pins.getProperty(version + ".jar"));

        Map<String, ClassModel> classes = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var entry : file.stream().toList()) {
                if (entry.getName().startsWith(ROOT) && entry.getName().endsWith(".class")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        ClassModel model = ClassFile.of().parse(in.readAllBytes());
                        classes.put(model.thisClass().asInternalName(), model);
                    }
                }
            }
        }
        return classes;
    }

    /** This module's compiled classes. */
    private static Map<String, ClassModel> readCurrent() throws IOException, URISyntaxException {
        Path classes = Path.of(Producer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, ClassModel> models = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                models.put(model.thisClass().asInternalName(), model);
            }
        }
        return models;
    }

    /** The lines of {@code api/differences.txt}. */
    private record Differences(List<String[]> entries) {

        static Differences read(String resource) {
            try (InputStream in = PublicShapeTest.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(resource + " is not on the test classpath");
                }
                var entries = new ArrayList<String[]>();
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(" \\| ");
                    if (fields.length < 3 || !Set.of("gone", "added", "signature", "deprecated").contains(fields[0])
                            || (fields[0].equals("signature") && fields.length < 4)) {
                        throw new IllegalStateException(resource + ": cannot read the line: " + line);
                    }
                    entries.add(fields);
                }
                return new Differences(entries);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Types of this kind: an entry without a member part. */
        Map<String, String> types(String kind) {
            return select(kind, false);
        }

        /** Members of this kind: an entry of the form {@code type.name descriptor}. */
        Map<String, String> members(String kind) {
            return select(kind, true);
        }

        Map<String, String> signatures() {
            var signatures = new TreeMap<String, String>();
            entries.stream().filter(e -> e[0].equals("signature")).forEach(e -> signatures.put(e[1], e[2]));
            return signatures;
        }

        private Map<String, String> select(String kind, boolean members) {
            var selected = new TreeMap<String, String>();
            for (String[] entry : entries) {
                if (entry[0].equals(kind) && entry[1].contains(" ") == members) {
                    if (selected.put(entry[1], entry[2]) != null) {
                        throw new IllegalStateException("listed twice: " + entry[1]);
                    }
                }
            }
            return selected;
        }
    }
}
