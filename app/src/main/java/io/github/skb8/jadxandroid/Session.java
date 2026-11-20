package io.github.skb8.jadxandroid;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import jadx.api.ICodeInfo;
import jadx.api.ICodeWriter;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.JavaMethod;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.ResourcesLoader;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.impl.SimpleCodeWriter;
import jadx.api.security.JadxSecurityFlag;
import jadx.api.security.impl.JadxSecurity;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.plugins.files.IJadxFilesGetter;
import jadx.core.xmlgen.ResContainer;

final class Session implements AutoCloseable {
    private static final int MAX_PREVIEW = 8 * 1024 * 1024;
    private static final long MAX_INPUT = 2L * 1024 * 1024 * 1024;
    final Project project;
    final List<String> names = new ArrayList<>();
    final JadxDecompiler jadx;
    final List<JavaClass> classes;
    final List<ResourceFile> resources;
    final boolean reducedClasspath;
    private final Context context;
    private final Path directory;
    private final Function<JadxArgs, ICodeWriter> defaultCodeWriter;

    record Hit(JavaClass cls, int position, String text) {}
    record MethodCall(String label, JavaMethod target) {}
    record CallGraph(List<MethodCall> callers, List<MethodCall> callees) {}
    record CfgBlock(int id, int offset, List<String> instructions, List<Integer> successors) {}
    record Preview(String text, Bitmap image, ResContainer resource) {}

    Session(Context context, Project project, PluginStore plugins) throws Exception {
        this(context, project, plugins, false);
    }

    Session(Context context, Project project, PluginStore plugins, boolean reducedClasspath) throws Exception {
        this.context = context;
        this.project = project;
        this.reducedClasspath = reducedClasspath;
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "session-");
        JadxDecompiler engine = null;
        try {
            List<java.io.File> inputs = new ArrayList<>();
            for (Uri uri : project.inputs) {
                String name = displayName(context, uri);
                Path folder = Files.createDirectories(directory.resolve("input-" + inputs.size()));
                Path file = folder.resolve(name);
                try (var in = open(context, uri); var out = Files.newOutputStream(file)) {
                    Io.copy(in, out, MAX_INPUT);
                }
                names.add(name);
                inputs.add(file.toFile());
            }
            JadxArgs args = new JadxArgs();
            defaultCodeWriter = args.getCodeWriterProvider();
            args.setInputFiles(inputs);
            args.setRootDir(directory.toFile());
            args.setThreadsCount(1);
            args.setCodeCache(NoOpCodeCache.INSTANCE);
            args.setDecompilationMode(project.mode);
            args.setDeobfuscationOn(project.deobfuscate);
            args.setShowInconsistentCode(project.inconsistent);
            args.setCodeData(project.codeData());
            args.setPluginOptions(project.pluginOptions);
            args.setPluginLoader(plugins.loader());
            args.setSecurity(new AndroidSecurity());
            args.setLoadJadxClsSetFile(!reducedClasspath);
            Path config = Files.createDirectories(context.getFilesDir().toPath().resolve("jadx"));
            Path cache = Files.createDirectories(directory.resolve("cache"));
            Path temporary = Files.createDirectories(directory.resolve("tmp"));
            args.setFilesGetter(new IJadxFilesGetter() {
                @Override public Path getConfigDir() { return config; }
                @Override public Path getCacheDir() { return cache; }
                @Override public Path getTempDir() { return temporary; }
            });
            engine = new JadxDecompiler(args);
            engine.getResourcesLoader().addResContainerFactory((resource, stream) ->
                    resource.getType() == ResourceType.IMG && resource.getDeobfName().endsWith(".9.png")
                            ? AndroidNinePatch.decode(resource, stream) : null);
            engine.load();
            jadx = engine;
            classes = new ArrayList<>(jadx.getClasses());
            classes.sort(Comparator.comparing(JavaClass::getFullName));
            resources = new ArrayList<>(jadx.getResources());
            resources.sort(Comparator.comparing(ResourceFile::getDeobfName));
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            try {
                if (engine != null) {
                    engine.close();
                }
            } catch (Exception | LinkageError | OutOfMemoryError cleanupError) {
                if (error != cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            } finally {
                try {
                    Io.deleteTree(directory);
                } catch (IOException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw error;
        }
    }

    List<Hit> search(String text, boolean regex, boolean caseSensitive, boolean source) {
        Pattern pattern = Pattern.compile(regex ? text : Pattern.quote(text),
                caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Hit> hits = new ArrayList<>();
        for (JavaClass cls : classes) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (pattern.matcher(cls.getFullName()).find()) {
                hits.add(new Hit(cls, 0, cls.getFullName()));
            }
            if (source && hits.size() < 400) {
                String code = cls.getCode();
                for (JavaNode member : cls.getMethods()) {
                    if (pattern.matcher(member.getName()).find()) {
                        hits.add(new Hit(cls, member.getDefPos(), member.getFullName()));
                        if (hits.size() == 400) {
                            break;
                        }
                    }
                }
                var match = pattern.matcher(code);
                while (hits.size() < 400 && match.find()) {
                    hits.add(new Hit(cls, match.start(), hitText(cls, code, match.start())));
                }
                cls.unload();
            }
            if (hits.size() >= 400) {
                return new ArrayList<>(hits.subList(0, 400));
            }
        }
        return hits;
    }

    List<Hit> usages(JavaNode target) {
        List<Hit> hits = new ArrayList<>();
        for (JavaClass cls : target.getUseIn().stream().map(JavaNode::getTopParentClass).distinct()
                .collect(java.util.stream.Collectors.toList())) {
            ICodeInfo info = cls.getCodeInfo();
            for (int position : cls.getUsePlacesFor(info, target)) {
                hits.add(new Hit(cls, position, hitText(cls, info.getCodeStr(), position)));
                if (hits.size() >= 400) {
                    return hits;
                }
            }
        }
        return hits;
    }

    CallGraph calls(JavaMethod method) throws Exception {
        List<MethodCall> callers = new ArrayList<>();
        for (JavaNode caller : method.getUseIn()) {
            if (caller instanceof JavaMethod from) {
                callers.add(new MethodCall(from.toString(), from));
            }
        }
        var callees = new LinkedHashMap<String, MethodCall>();
        var node = method.getMethodNode();
        if (!node.isNoCode()) {
            try {
                node.unload();
                node.load();
                for (var instruction : node.getInstructions()) {
                    if (instruction instanceof InvokeNode invoke) {
                        var info = invoke.getCallMth();
                        var resolved = node.root().resolveMethod(info);
                        JavaNode target = resolved == null ? null : jadx.getJavaNodeByRef(resolved);
                        String label = target == null ? info.getFullId() : target.toString();
                        callees.putIfAbsent(info.getRawFullId(),
                                new MethodCall(label, target instanceof JavaMethod reachable ? reachable : null));
                    }
                }
            } finally {
                node.unload();
            }
        }
        return new CallGraph(callers, new ArrayList<>(callees.values()));
    }

    List<CfgBlock> controlFlow(JavaMethod method, boolean raw) {
        var node = method.getMethodNode();
        try {
            boolean processed = node.root().getProcessClasses().processMethodUntilVisitor(node,
                    raw ? "SSATransform" : "RegionMakerVisitor", false);
            if (!processed || node.getBasicBlocks() == null) {
                return List.of();
            }
            List<CfgBlock> graph = new ArrayList<>();
            for (BlockNode block : node.getBasicBlocks()) {
                List<String> instructions = new ArrayList<>();
                block.getInstructions().forEach(instruction -> instructions.add(instruction.toString()));
                List<Integer> successors = new ArrayList<>();
                block.getSuccessors().forEach(next -> successors.add(next.getId()));
                graph.add(new CfgBlock(block.getId(), block.getStartOffset(), instructions, successors));
            }
            return graph;
        } finally {
            node.unload();
        }
    }

    private static String hitText(JavaClass cls, String code, int position) {
        int lineStart = code.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int lineEnd = code.indexOf('\n', position);
        if (lineEnd == -1) {
            lineEnd = code.length();
        }
        String line = code.substring(Math.min(lineStart, lineEnd), lineEnd).trim();
        return cls.getFullName() + "\n" + (line.length() > 180 ? line.substring(0, 180) + "…" : line);
    }

    void edit(JavaNode node, String value, boolean rename) {
        JadxNodeRef reference = Objects.requireNonNull(JadxNodeRef.forJavaNode(node));
        var entries = rename ? project.renames : project.comments;
        if (value.isBlank()) {
            entries.remove(reference);
            if (rename) {
                node.removeAlias();
            }
        } else {
            entries.put(reference, value);
        }
        jadx.getArgs().setCodeData(project.codeData());
        jadx.reloadPasses();
        for (JavaClass cls : classes) {
            cls.unload();
        }
        classes.sort(Comparator.comparing(JavaClass::getFullName));
    }

    Preview preview(ResourceFile file) throws Exception {
        return preview(file.loadContent());
    }

    private static Bitmap thumbnail(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    Preview preview(ResContainer resource) throws Exception {
        return switch (resource.getDataType()) {
            case TEXT, RES_TABLE -> new Preview(resource.getText().getCodeStr(), null, resource);
            case DECODED_DATA -> new Preview("", thumbnail(resource.getDecodedData()), resource);
            case RES_LINK -> {
                ResourceFile link = resource.getResLink();
                yield ResourcesLoader.decodeStream(link, (size, in) -> {
                    if (size > MAX_PREVIEW) {
                        return new Preview("Файл слишком велик для предпросмотра", null, resource);
                    }
                    byte[] bytes = Io.read(in, MAX_PREVIEW);
                    if (link.getType() == ResourceType.IMG) {
                        Bitmap image = thumbnail(bytes);
                        if (image != null) {
                            return new Preview("", image, resource);
                        }
                    }
                    boolean binary = false;
                    for (int i = 0; i < Math.min(bytes.length, 4096); i++) {
                        if (bytes[i] == 0) {
                            binary = true;
                            break;
                        }
                    }
                    return new Preview(binary ? "Двоичный ресурс · " + bytes.length + " байт"
                            : new String(bytes, StandardCharsets.UTF_8), null, resource);
                });
            }
        };
    }

    void saveResource(ResContainer resource, Uri destination) throws Exception {
        try (var out = Objects.requireNonNull(context.getContentResolver().openOutputStream(destination, "wt"))) {
            switch (resource.getDataType()) {
                case TEXT, RES_TABLE -> out.write(resource.getText().getCodeStr().getBytes(StandardCharsets.UTF_8));
                case DECODED_DATA -> out.write(resource.getDecodedData());
                case RES_LINK -> ResourcesLoader.decodeStream(resource.getResLink(), (size, in) -> {
                    Io.copy(in, out, MAX_INPUT);
                    return null;
                });
            }
        }
    }

    void export(Uri destination, boolean gradle, boolean json) throws Exception {
        Path export = Files.createTempDirectory(directory, "export-");
        try {
            Path output = Files.createDirectory(export.resolve("output"));
            JadxArgs args = jadx.getArgs();
            args.setOutDir(output.toFile());
            args.setOutDirSrc(output.resolve("sources").toFile());
            args.setOutDirRes(output.resolve("resources").toFile());
            args.setExportAsGradleProject(gradle);
            args.setOutputFormat(json ? JadxArgs.OutputFormatEnum.JSON : JadxArgs.OutputFormatEnum.JAVA);
            Function<JadxArgs, ICodeWriter> writer = args.getCodeWriterProvider();
            try {
                if (!json && writer == defaultCodeWriter) {
                    args.setCodeWriterProvider(SimpleCodeWriter::new);
                }
                jadx.save();
            } finally {
                args.setCodeWriterProvider(writer);
                args.setExportAsGradleProject(false);
                args.setOutputFormat(JadxArgs.OutputFormatEnum.JAVA);
            }
            Path archive = export.resolve("project.zip");
            Io.zip(output, archive);
            try (var stream = Objects.requireNonNull(context.getContentResolver().openOutputStream(destination, "wt"))) {
                Files.copy(archive, stream);
            }
        } finally {
            Io.deleteTree(export);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            jadx.close();
        } finally {
            Io.deleteTree(directory);
        }
    }

    static InputStream open(Context context, Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) {
            throw new IOException("Требуется URI документа Android");
        }
        return Objects.requireNonNull(context.getContentResolver().openInputStream(uri));
    }

    static String displayName(Context context, Uri uri) {
        String name = "input-" + Integer.toUnsignedString(uri.hashCode());
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0);
            }
        }
        name = name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        return name.isBlank() || name.equals(".") || name.equals("..") ? "input" : name;
    }

    private static final class AndroidSecurity extends JadxSecurity {
        AndroidSecurity() {
            super(JadxSecurityFlag.all());
        }

        @Override
        public Document parseXml(InputStream input) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setExpandEntityReferences(false);
                var builder = factory.newDocumentBuilder();
                builder.setEntityResolver((publicId, systemId) -> {
                    throw new SAXException("External XML entity");
                });
                Document document = builder.parse(input);
                if (document.getDoctype() != null) {
                    throw new SAXException("XML document types are unsupported");
                }
                return document;
            } catch (Exception error) {
                throw new IllegalArgumentException("Не удалось прочитать XML", error);
            }
        }
    }
}
