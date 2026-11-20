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
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.ResourcesLoader;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.security.JadxSecurityFlag;
import jadx.api.security.impl.JadxSecurity;
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
    private final Context context;
    private final Path directory;

    record Hit(JavaClass cls, int position, String text) {}
    record Preview(String text, Bitmap image, ResContainer resource) {}

    Session(Context context, Project project, PluginStore plugins) throws Exception {
        this.context = context;
        this.project = project;
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
            args.setInputFiles(inputs);
            args.setRootDir(directory.toFile());
            args.setThreadsCount(Math.min(2, JadxArgs.DEFAULT_THREADS_COUNT));
            args.setCodeCache(NoOpCodeCache.INSTANCE);
            args.setDecompilationMode(project.mode);
            args.setDeobfuscationOn(project.deobfuscate);
            args.setShowInconsistentCode(project.inconsistent);
            args.setCodeData(project.codeData());
            args.setPluginOptions(project.pluginOptions);
            args.setPluginLoader(plugins.loader());
            args.setSecurity(new AndroidSecurity());
            args.setLoadJadxClsSetFile(true);
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
                            ? ResContainer.resourceFileLink(resource) : null);
            engine.load();
            jadx = engine;
            classes = new ArrayList<>(jadx.getClasses());
            classes.sort(Comparator.comparing(JavaClass::getFullName));
            resources = new ArrayList<>(jadx.getResources());
            resources.sort(Comparator.comparing(ResourceFile::getDeobfName));
        } catch (Exception | LinkageError error) {
            try {
                if (engine != null) {
                    engine.close();
                }
                Io.deleteTree(directory);
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
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
            if (source) {
                for (JavaNode member : cls.getMethods()) {
                    if (pattern.matcher(member.getName()).find()) {
                        cls.getCode();
                        hits.add(new Hit(cls, member.getDefPos(), member.getFullName()));
                    }
                }
                String code = cls.getCode();
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
            try {
                jadx.save();
            } finally {
                args.setExportAsGradleProject(false);
                args.setOutputFormat(JadxArgs.OutputFormatEnum.JAVA);
            }
            writeArchive(destination, output, export);
        } finally {
            Io.deleteTree(export);
        }
    }

    boolean exportOpenJdk(Uri destination, OpenJdk runtime, int version) throws Exception {
        Path export = Files.createTempDirectory(directory, "jvm-export-");
        try {
            Path output = Files.createDirectory(export.resolve("output"));
            var arguments = new ArrayList<>(List.of("--quiet", "--threads-count", "2", "--output-dir",
                    output.toString(), "--decompilation-mode", project.mode.name().toLowerCase(java.util.Locale.ROOT)));
            if (project.deobfuscate) {
                arguments.add("--deobf");
            }
            if (project.inconsistent) {
                arguments.add("--show-bad-code");
            }
            for (var input : jadx.getArgs().getInputFiles()) {
                arguments.add(input.getAbsolutePath());
            }
            String result = runtime.run(version, export, arguments.toArray(String[]::new));
            try (var files = Files.list(output)) {
                if (files.findAny().isEmpty()) {
                    throw new IOException("JADX CLI не записала выходные файлы");
                }
            }
            writeArchive(destination, output, export);
            return "3".equals(result);
        } finally {
            Io.deleteTree(export);
        }
    }

    private void writeArchive(Uri destination, Path output, Path export) throws IOException {
        Path archive = export.resolve("project.zip");
        Io.zip(output, archive);
        try (var stream = Objects.requireNonNull(context.getContentResolver().openOutputStream(destination, "wt"))) {
            Files.copy(archive, stream);
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
