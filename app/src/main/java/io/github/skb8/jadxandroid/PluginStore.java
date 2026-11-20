package io.github.skb8.jadxandroid;

import android.content.Context;
import android.content.SharedPreferences;

import dalvik.system.DexClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.loader.JadxBasePluginLoader;

import jadx.core.plugins.versions.VerifyRequiredVersion;
import jadx.plugins.input.aab.AabInputPlugin;
import jadx.plugins.input.apkm.ApkmInputPlugin;
import jadx.plugins.input.apks.ApksInputPlugin;
import jadx.plugins.input.dex.DexInputPlugin;
import jadx.plugins.input.java.JavaInputPlugin;
import jadx.plugins.input.smali.SmaliInputPlugin;
import jadx.plugins.input.xapk.XApkInputPlugin;
import jadx.plugins.mappings.RenameMappingsPlugin;

final class PluginStore {
    private static final long MAX_ARCHIVE = 64L * 1024 * 1024;
    private final Path directory;
    private final SharedPreferences settings;

    record Plugin(String id, String provider, Path archive) {}

    PluginStore(Context context) throws IOException {
        directory = Files.createDirectories(context.getFilesDir().toPath().resolve("plugins"));
        settings = context.getSharedPreferences("plugins", Context.MODE_PRIVATE);
    }

    List<Plugin> list() throws IOException {
        List<Plugin> entries = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory, "*.zip")) {
            for (Path path : paths) {
                entries.add(inspect(path));
            }
        }
        entries.sort(Comparator.comparing(Plugin::id));
        return entries;
    }

    Plugin install(InputStream source) throws IOException {
        Path temporary = Files.createTempFile(directory, "import-", ".tmp");
        try {
            // Android 14+ requires a dynamic code file to be made read-only before writing data.
            try (var output = Files.newOutputStream(temporary)) {
                if (!temporary.toFile().setReadOnly()) {
                    throw new IOException("Не удалось сделать архив доступным только для чтения");
                }
                Io.copy(source, output, MAX_ARCHIVE);
            }
            Plugin metadata = inspect(temporary);
            Path target = directory.resolve(metadata.id() + ".zip");
            settings.edit().putBoolean(metadata.id(), false).apply();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new Plugin(metadata.id(), metadata.provider(), target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    boolean enabled(Plugin plugin) {
        return settings.getBoolean(plugin.id(), false);
    }

    void enable(Plugin plugin, boolean enabled) {
        settings.edit().putBoolean(plugin.id(), enabled).apply();
    }

    void remove(Plugin plugin) throws IOException {
        Files.deleteIfExists(plugin.archive());
        settings.edit().remove(plugin.id()).apply();
    }

    static List<JadxPlugin> builtIns() {
        return List.of(new DexInputPlugin(), new JavaInputPlugin(), new SmaliInputPlugin(), new AabInputPlugin(),
                new ApksInputPlugin(), new ApkmInputPlugin(), new XApkInputPlugin(), new RenameMappingsPlugin());
    }

    JadxBasePluginLoader loader() {
        return new JadxBasePluginLoader() {
            @Override
            public List<JadxPlugin> load() {
                List<JadxPlugin> plugins = new ArrayList<>(builtIns());
                try {
                    for (Plugin plugin : list()) {
                        if (!enabled(plugin)) {
                            continue;
                        }
                        ClassLoader loader = new DexClassLoader(plugin.archive().toString(), null, null,
                                JadxPlugin.class.getClassLoader());
                        Class<? extends JadxPlugin> type = Class.forName(plugin.provider(), true, loader)
                                .asSubclass(JadxPlugin.class);
                        if (type.getClassLoader() != loader) {
                            throw new IOException("Класс должен быть объявлен в архиве плагина");
                        }
                        JadxPlugin instance = type.getDeclaredConstructor().newInstance();
                        if (!plugin.id().equals(instance.getPluginInfo().getPluginId())) {
                            throw new IOException("Идентификатор плагина не совпадает с дескриптором");
                        }
                        var info = instance.getPluginInfo();
                        var verifier = new VerifyRequiredVersion(BuildConfig.JADX_VERSION);
                        if (!verifier.isCompatible(info.getRequiredJadxVersion())) {
                            throw new IOException("Несовместимая версия JADX у плагина " + plugin.id());
                        }
                        if (plugins.stream().anyMatch(builtIn -> builtIn.getPluginInfo().getPluginId().equals(plugin.id()))) {
                            throw new IOException("Идентификатор плагина уже используется: " + plugin.id());
                        }
                        plugins.add(instance);
                    }
                    return plugins;
                } catch (Exception | LinkageError error) {
                    throw new IllegalStateException("Не удалось загрузить плагины JADX: " + error, error);
                }
            }
        };
    }

    private static Plugin inspect(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("classes.dex") == null) {
                throw new IOException("В архиве нет classes.dex. JVM-плагин нужно преобразовать в DEX перед импортом.");
            }
            var descriptor = zip.getEntry("jadx-android.properties");
            if (descriptor == null || descriptor.getSize() <= 0 || descriptor.getSize() > 4096) {
                throw new IOException("В корне архива нужен файл jadx-android.properties размером до 4 КБ.");
            }
            Properties properties = new Properties();
            try (var stream = zip.getInputStream(descriptor)) {
                properties.load(new java.io.ByteArrayInputStream(Io.read(stream, 4096)));
            }
            String id = properties.getProperty("id", "");
            String provider = properties.getProperty("provider", "");
            if (!id.matches("[a-z0-9][a-z0-9._-]{0,79}") || !provider.matches("[\\w$]+(\\.[\\w$]+)+")) {
                throw new IOException("Неверные id или provider в дескрипторе");
            }
            if (!BuildConfig.JADX_VERSION.equals(properties.getProperty("jadxVersion"))) {
                throw new IOException("Плагин должен соответствовать JADX " + BuildConfig.JADX_VERSION);
            }
            return new Plugin(id, provider, archive);
        }
    }
}
