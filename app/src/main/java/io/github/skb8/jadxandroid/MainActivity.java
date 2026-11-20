package io.github.skb8.jadxandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import jadx.api.DecompilationMode;
import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.api.data.impl.JadxNodeRef;
import jadx.core.xmlgen.ResContainer;

public final class MainActivity extends Activity {
    private static final int OPEN_FILES = 1;
    private static final int OPEN_PROJECT = 2;
    private static final int SAVE_PROJECT = 3;
    private static final int EXPORT_ZIP = 4;
    private static final int INSTALL_PLUGIN = 5;
    private static final int SAVE_FILE = 6;
    private static final int BACKGROUND = Color.rgb(17, 25, 22);
    private static final int PANEL = Color.rgb(28, 40, 35);
    private static final int INK = Color.rgb(231, 242, 235);
    private static final int MUTED = Color.rgb(167, 188, 177);
    private static final int ACCENT = Color.rgb(139, 216, 190);

    private static final Pattern TOKENS = Pattern.compile(
            "(?<comment>/\\*[\\s\\S]*?\\*/|//[^\\n]*)"
            + "|(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
            + "|(?<keyword>\\b(?:package|import|class|interface|enum|record|public|private|protected|"
            + "static|final|void|new|return|if|else|switch|case|for|while|do|break|continue|try|"
            + "catch|finally|throws|throw|boolean|byte|char|double|float|int|long|short|null|true|"
            + "false|extends|implements|super|this|synchronized|abstract|default)\\b)"
            + "|(?<number>\\b(?:0x[0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?)\\b)");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<DocumentTab> documents = new ArrayList<>();
    private LinearLayout root;
    private LinearLayout body;
    private LinearLayout navigation;
    private HorizontalScrollView tabs;
    private TextView status;
    private ProgressBar progress;
    private PluginStore plugins;
    private OpenJdk openJdk;
    private Session session;
    private boolean busy;
    private boolean destroyed;
    private Screen screen = Screen.CLASSES;
    private String path = "";
    private DocumentTab current;
    private TextView codeView;
    private ScrollView codeScroll;
    private List<Session.Hit> lastResults = List.of();
    private String searchTitle = "";
    private String pendingExportMode = "";
    private DocumentTab pendingFile;
    private int generation;

    private enum Screen { CLASSES, RESOURCES, RESULTS, CODE }

    private static final class DocumentTab {
        final Object source;
        final String title;
        String text = "";
        Session.Preview preview;
        boolean smali;
        int scroll;

        DocumentTab(Object source, String title) {
            this.source = source;
            this.title = title;
        }
    }

    private record Entry(String label, Runnable open) {}

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        try {
            plugins = new PluginStore(getApplicationContext());
        } catch (IOException e) {
            throw new IllegalStateException("Хранилище плагинов недоступно", e);
        }
        openJdk = new OpenJdk(this);
        root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);

        LinearLayout top = row();
        top.setPadding(dp(18), dp(8), dp(10), dp(8));
        top.setBackgroundColor(PANEL);
        TextView title = text("JADX Android", 22, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        top.addView(button("Открыть", () -> pick(OPEN_FILES, true)));
        top.addView(button("⋮", this::menu));
        top.getChildAt(top.getChildCount() - 1).setContentDescription("Меню проекта");
        root.addView(top);
        navigation = row();
        navigation.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(navigation);
        tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        root.addView(tabs);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        body = column();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text("JADX " + BuildConfig.JADX_VERSION, 12, MUTED);
        status.setPadding(dp(16), 0, dp(16), 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(32)));
        render();

        if (state != null) {
            pendingExportMode = state.getString("exportMode", "");
        }
        String previous = getPreferences(MODE_PRIVATE).getString("lastProject", "");
        if (!previous.isEmpty()) {
            try {
                open(Project.parse(previous));
            } catch (Exception error) {
                reportError(error);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("exportMode", pendingExportMode);
    }

    private void goBack() {
        if (busy) {
            toast("Операция ещё выполняется");
        } else if (screen == Screen.CODE || screen == Screen.RESULTS) {
            show(Screen.CLASSES);
        } else if (!path.isEmpty()) {
            int previous = path.lastIndexOf('/', path.length() - 2);
            path = path.substring(0, previous + 1);
            render();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.execute(() -> {
            if (session != null) {
                closeQuietly(session);
            }
        });
        worker.shutdown();
        super.onDestroy();
    }

    private void menu() {
        if (busy) {
            return;
        }
        List<Entry> options = new ArrayList<>();
        options.add(new Entry("Открыть проект", () -> pick(OPEN_PROJECT, false)));
        if (session != null) {
            options.add(new Entry("Сохранить проект", () -> create(SAVE_PROJECT, "project.jadx-android.json", "application/json")));
            options.add(new Entry("Экспорт", this::exportMenu));
            options.add(new Entry("Настройки декомпиляции", this::settings));
            options.add(new Entry("Параметры плагинов", this::pluginOptions));
            options.add(new Entry("Диагностика проекта", this::diagnostics));
            options.add(new Entry("Закрыть проект", this::close));
        }
        options.add(new Entry("Плагины", this::pluginMenu));
        options.add(new Entry("Среда Java", this::javaMenu));
        choose("Меню", options);
    }

    private void exportMenu() {
        List<Entry> formats = new ArrayList<>(List.of(
                new Entry("Java и ресурсы (ZIP)", () -> exportAs("java")),
                new Entry("JSON и ресурсы (ZIP)", () -> exportAs("json")),
                new Entry("Проект Gradle (ZIP)", () -> exportAs("gradle"))));
        int version = selectedJava();
        if (version != 0 && openJdk.installed(version)) {
            formats.add(new Entry("Java и ресурсы через OpenJDK " + version + " (ZIP)",
                    () -> new AlertDialog.Builder(this).setTitle("Экспорт через OpenJDK " + version)
                            .setMessage("JADX CLI использует исходные файлы и настройки декомпиляции проекта. Для псевдонимов, комментариев и DEX-плагинов используйте обычный экспорт.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Экспорт", (dialog, which) -> exportAs("openjdk-" + version)).show()));
        }
        choose("Экспорт проекта", formats);
    }

    private void exportAs(String type) {
        pendingExportMode = type;
        create(EXPORT_ZIP, "jadx-" + type + ".zip", "application/zip");
    }

    private void pick(int request, boolean multiple) {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        startActivityForResult(intent, request);
    }

    private void create(int request, String name, String mime) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (request == OPEN_FILES) {
            List<Uri> files = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri selected = clip.getItemAt(i).getUri();
                    if (selected != null && !files.contains(selected)) {
                        files.add(selected);
                    }
                }
            } else if (uri != null) {
                files.add(uri);
            }
            if (files.isEmpty()) {
                return;
            }
            for (Uri selected : files) {
                try {
                    getContentResolver().takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some providers grant access for the activity lifetime only.
                }
            }
            open(new Project(files));
            return;
        }
        if (uri == null) {
            return;
        }
        if (request == OPEN_PROJECT) {
            task("Чтение проекта…", () -> {
                try (var input = Session.open(this, uri)) {
                    return Project.parse(new String(Io.read(input, 1_048_576), StandardCharsets.UTF_8));
                }
            }, this::open);
        } else if (request == SAVE_PROJECT && session != null) {
            Project project = session.project;
            task("Сохранение проекта…", () -> {
                write(uri, project.serialize());
                return null;
            }, unused -> toast("Проект сохранён"));
        } else if (request == EXPORT_ZIP && session != null) {
            String type = pendingExportMode;
            Session selected = session;
            task("Экспорт проекта…", () -> {
                if (type.startsWith("openjdk-")) {
                    int version = Integer.parseInt(type.substring("openjdk-".length()));
                    return selected.exportOpenJdk(uri, openJdk, version);
                }
                selected.export(uri, "gradle".equals(type), "json".equals(type));
                return false;
            }, errors -> toast(errors ? "Архив сохранён с ошибками декомпиляции" : "Архив сохранён"));
        } else if (request == INSTALL_PLUGIN) {
            task("Импорт плагина…", () -> {
                try (var input = Session.open(this, uri)) {
                    return plugins.install(input);
                }
            }, plugin -> {
                toast("Импортирован плагин " + plugin.id());
                pluginMenu();
            });
        } else if (request == SAVE_FILE && pendingFile != null) {
            String text = pendingFile.text;
            pendingFile = null;
            task("Сохранение файла…", () -> {
                write(uri, text);
                return null;
            }, unused -> toast("Файл сохранён"));
        }
    }

    private void write(Uri uri, String value) throws IOException {
        try (var output = Objects.requireNonNull(getContentResolver().openOutputStream(uri, "wt"))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void open(Project project) {
        if (project.inputs.isEmpty()) {
            toast("В проекте нет входных файлов");
            return;
        }
        Session previous = session;
        task("Загрузка файлов и анализ…", () -> new Session(getApplicationContext(), project, plugins), loaded -> {
            session = loaded;
            if (previous != null) {
                worker.execute(() -> closeQuietly(previous));
            }
            persist();
            resetViews();
            if (loaded.classes.isEmpty() && !loaded.resources.isEmpty()) {
                show(Screen.RESOURCES);
            } else {
                show(Screen.CLASSES);
            }
        });
    }

    private void close() {
        Session previous = session;
        session = null;
        worker.execute(() -> closeQuietly(previous));
        getPreferences(MODE_PRIVATE).edit().remove("lastProject").apply();
        resetViews();
        show(Screen.CLASSES);
    }

    private static void closeQuietly(Session closing) {
        try {
            closing.close();
        } catch (Exception e) {
            android.util.Log.w("JADX", "Could not close project", e);
        }
    }

    private void resetViews() {
        documents.clear();
        current = null;
        lastResults = List.of();
        searchTitle = "";
        path = "";
    }

    private void persist() {
        try {
            getPreferences(MODE_PRIVATE).edit().putString("lastProject", session.project.serialize()).apply();
        } catch (Exception error) {
            reportError(error);
        }
    }

    private void settings() {
        Session selected = session;
        LinearLayout form = form();
        CheckBox deobfuscate = check("Деобфускация");
        deobfuscate.setChecked(selected.project.deobfuscate);
        CheckBox inconsistent = check("Показывать некорректный код");
        inconsistent.setChecked(selected.project.inconsistent);
        form.addView(deobfuscate);
        form.addView(inconsistent);
        android.widget.Spinner mode = new android.widget.Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, DecompilationMode.values()));
        mode.setSelection(selected.project.mode.ordinal());
        form.addView(mode);
        new AlertDialog.Builder(this).setTitle("Настройки декомпиляции").setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (dialog, which) -> {
                    Project config = copyProject(selected.project);
                    config.deobfuscate = deobfuscate.isChecked();
                    config.inconsistent = inconsistent.isChecked();
                    config.mode = (DecompilationMode) mode.getSelectedItem();
                    open(config);
                }).show();
    }

    private Project copyProject(Project original) {
        Project copy = new Project(original.inputs);
        copy.deobfuscate = original.deobfuscate;
        copy.inconsistent = original.inconsistent;
        copy.mode = original.mode;
        copy.renames.putAll(original.renames);
        copy.comments.putAll(original.comments);
        copy.pluginOptions.putAll(original.pluginOptions);
        return copy;
    }

    private void diagnostics() {
        Session selected = session;
        task("Чтение диагностики…", () -> "Файлы: " + String.join(", ", selected.names)
                + "\nКлассы: " + selected.classes.size() + "\nРесурсы: " + selected.resources.size()
                + "\nОшибки JADX: " + selected.jadx.getErrorsCount()
                + "\nПредупреждения JADX: " + selected.jadx.getWarnsCount(),
                result -> new AlertDialog.Builder(this).setTitle("Диагностика").setMessage(result)
                        .setPositiveButton("Закрыть", null).show());
    }

    private int selectedJava() {
        return getPreferences(MODE_PRIVATE).getInt("openJdkVersion", 0);
    }

    private void javaMenu() {
        int selected = selectedJava();
        List<Entry> runtimes = new ArrayList<>();
        runtimes.add(new Entry("Android ART" + (selected == 0 ? " · выбрано" : ""), () -> {
            getPreferences(MODE_PRIVATE).edit().remove("openJdkVersion").apply();
            toast("Выбрана Android ART");
        }));
        if (openJdk.supported()) {
            for (int version : new int[]{17, 21}) {
                boolean installed = openJdk.installed(version);
                String label = "OpenJDK " + version
                        + (selected == version ? " · выбрано" : installed ? " · установлено" : " · скачать");
                runtimes.add(new Entry(label, () -> {
                    if (installed) {
                        activateJava(version);
                    } else {
                        new AlertDialog.Builder(this).setTitle("Скачать OpenJDK " + version + "?")
                                .setMessage("При первой установке приложение скачает среду и JADX CLI с GitHub. Объём загрузки около 100 МБ.")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Скачать", (dialog, which) -> activateJava(version)).show();
                    }
                }));
            }
        }
        choose("Java для экспорта", runtimes);
    }

    private void activateJava(int version) {
        task("Установка и запуск OpenJDK " + version + "…", () -> {
            openJdk.install(version);
            var temporary = java.nio.file.Files.createTempDirectory(getCacheDir().toPath(), "jdk-check-");
            try {
                return openJdk.run(version, temporary);
            } finally {
                Io.deleteTree(temporary);
            }
        }, runtime -> {
            getPreferences(MODE_PRIVATE).edit().putInt("openJdkVersion", version).apply();
            toast("Выбрана OpenJDK " + runtime);
        });
    }

    private void pluginMenu() {
        task("Чтение списка плагинов…", plugins::list, installed -> {
            List<Entry> options = new ArrayList<>();
            options.add(new Entry("Импорт APK/JAR/ZIP с DEX", () -> pick(INSTALL_PLUGIN, false)));
            for (PluginStore.Plugin plugin : installed) {
                options.add(new Entry(plugin.id() + (plugins.enabled(plugin) ? " · включён" : " · выключен"),
                        () -> pluginActions(plugin)));
            }
            choose("Плагины", options);
        });
    }

    private void pluginActions(PluginStore.Plugin plugin) {
        boolean enabled = plugins.enabled(plugin);
        Runnable reload = () -> {
            if (session == null) {
                pluginMenu();
            } else {
                open(session.project);
            }
        };
        choose(plugin.id(), List.of(
                new Entry(enabled ? "Выключить" : "Включить", () -> {
                    if (enabled) {
                        plugins.enable(plugin, false);
                        reload.run();
                    } else {
                        new AlertDialog.Builder(this).setTitle("Включить " + plugin.id() + "?")
                                .setMessage("Код плагина получит те же права доступа, что и JADX Android.")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Включить", (dialog, which) -> {
                                    plugins.enable(plugin, true);
                                    reload.run();
                                }).show();
                    }
                }),
                new Entry("Удалить", () -> new AlertDialog.Builder(this).setTitle("Удалить " + plugin.id() + "?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить", (dialog, which) -> task("Удаление…", () -> {
                            plugins.remove(plugin);
                            return null;
                        }, unused -> reload.run())).show())));
    }

    private void pluginOptions() {
        List<Entry> options = new ArrayList<>();
        Project project = session.project;
        for (var context : session.jadx.getPluginManager().getResolvedPluginContexts()) {
            var pluginOptions = context.getOptions();
            if (pluginOptions == null) {
                continue;
            }
            for (var option : pluginOptions.getOptionsDescriptions()) {
                options.add(new Entry(option.name(), () -> {
                    Consumer<String> save = value -> {
                        Project edited = copyProject(project);
                        if (value.isBlank()) {
                            edited.pluginOptions.remove(option.name());
                        } else {
                            edited.pluginOptions.put(option.name(), value);
                        }
                        open(edited);
                    };
                    if (option.values().isEmpty()) {
                        prompt(option.name(), option.description(), project.pluginOptions.getOrDefault(option.name(),
                                option.defaultValue() == null ? "" : option.defaultValue()), save);
                    } else {
                        List<Entry> values = new ArrayList<>();
                        for (String value : option.values()) {
                            values.add(new Entry(value, () -> save.accept(value)));
                        }
                        values.add(new Entry("По умолчанию", () -> save.accept("")));
                        choose(option.name(), values);
                    }
                }));
            }
        }
        choose("Параметры плагинов", options);
    }

    private void show(Screen screen) {
        if (this.screen == Screen.CODE && current != null && codeScroll != null) {
            current.scroll = codeScroll.getScrollY();
        }
        this.screen = screen;
        path = "";
        render();
    }

    private void render() {
        generation++;
        body.removeAllViews();
        navigation.removeAllViews();
        tabs.removeAllViews();
        codeView = null;
        codeScroll = null;
        if (session == null) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(24), dp(24), dp(24));
            TextView headline = text("Откройте файл для анализа", 23, INK);
            headline.setGravity(Gravity.CENTER);
            empty.addView(headline, new LinearLayout.LayoutParams(-1, -2));
            TextView description = text("APK, DEX, AAB, JAR, SMALI и архивы", 14, MUTED);
            description.setGravity(Gravity.CENTER);
            description.setPadding(0, dp(9), 0, dp(20));
            empty.addView(description, new LinearLayout.LayoutParams(-1, -2));
            empty.addView(button("Выбрать файлы", () -> pick(OPEN_FILES, true)));
            body.addView(empty, new LinearLayout.LayoutParams(-1, -1));
            if (!busy) {
                status.setText("JADX " + BuildConfig.JADX_VERSION + " · Android");
            }
            return;
        }
        nav("Классы", Screen.CLASSES);
        nav("Ресурсы", Screen.RESOURCES);
        navigation.addView(button("Поиск", this::search));
        if (!searchTitle.isEmpty()) {
            nav("Найдено", Screen.RESULTS);
        }
        if (!documents.isEmpty()) {
            LinearLayout strip = row();
            strip.setPadding(dp(8), 0, dp(8), 0);
            for (DocumentTab tab : documents) {
                Button item = button(tab.title, () -> switchTab(tab, -1));
                item.setTextColor(screen == Screen.CODE && tab == current ? ACCENT : MUTED);
                item.setOnLongClickListener(view -> {
                    if (!busy) {
                        closeTab(tab);
                    }
                    return true;
                });
                strip.addView(item);
            }
            tabs.addView(strip);
        }
        if (!busy) {
            status.setText(session.classes.size() + " классов  ·  " + session.resources.size()
                    + " ресурсов  ·  " + String.join(", ", session.names));
        }
        switch (screen) {
            case CLASSES -> renderTree(true);
            case RESOURCES -> renderTree(false);
            case RESULTS -> renderHits();
            case CODE -> renderCode();
        }
    }

    private void nav(String label, Screen target) {
        Button item = button(label, () -> show(target));
        if (screen == target) {
            item.setTextColor(ACCENT);
            item.setBackground(pill(PANEL, dp(14)));
        }
        navigation.addView(item);
    }

    private void renderTree(boolean classes) {
        LinearLayout location = row();
        if (!path.isEmpty()) {
            location.addView(button("‹", this::goBack));
        }
        TextView title = text(path.isEmpty() ? (classes ? "Пакеты и классы" : "Файлы и ресурсы") : path, 15, MUTED);
        title.setPadding(dp(16), 0, dp(12), 0);
        location.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        body.addView(location);
        Map<String, Entry> folders = new TreeMap<>();
        List<Entry> contents = new ArrayList<>();
        if (classes) {
            for (JavaClass cls : session.classes) {
                String pkg = cls.getPackage().replace('.', '/');
                String key = (pkg.isEmpty() ? "" : pkg + "/") + cls.getName();
                treeEntry(folders, contents, key, () -> openClass(cls, 0));
            }
        } else {
            for (ResourceFile file : session.resources) {
                String key = file.getDeobfName();
                treeEntry(folders, contents, key, () -> openResource(file, key));
            }
        }
        List<Entry> entries = new ArrayList<>(folders.values());
        entries.addAll(contents);
        list(entries, classes ? "Классов в этом разделе нет" : "Ресурсов в этом разделе нет");
    }

    private void treeEntry(Map<String, Entry> folders, List<Entry> contents, String key, Runnable open) {
        if (!key.startsWith(path)) {
            return;
        }
        String relative = key.substring(path.length());
        int delimiter = relative.indexOf('/');
        if (delimiter >= 0) {
            String folder = relative.substring(0, delimiter + 1);
            String next = path + folder;
            folders.putIfAbsent(folder, new Entry(folder, () -> {
                path = next;
                render();
            }));
        } else if (!relative.isEmpty()) {
            contents.add(new Entry(relative, open));
        }
    }

    private void search() {
        LinearLayout form = form();
        EditText query = editText("Имя или фрагмент кода");
        query.setSingleLine();
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        CheckBox source = check("Искать в коде и именах методов");
        source.setChecked(true);
        CheckBox regex = check("Регулярное выражение");
        CheckBox exact = check("Учитывать регистр");
        form.addView(query);
        form.addView(source);
        form.addView(regex);
        form.addView(exact);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Поиск по проекту").setView(form)
                .setNegativeButton("Отмена", null).setPositiveButton("Найти", null).create();
        Runnable run = () -> {
            String text = query.getText().toString();
            if (text.isBlank()) {
                query.setError("Введите запрос");
                return;
            }
            dialog.dismiss();
            boolean byRegex = regex.isChecked();
            boolean byCase = exact.isChecked();
            boolean inSource = source.isChecked();
            Session selected = session;
            task("Поиск по проекту…", () -> selected.search(text, byRegex, byCase, inSource),
                    hits -> showHits("Поиск · " + text, hits));
        };
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> run.run()));
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                run.run();
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private void showHits(String title, List<Session.Hit> hits) {
        searchTitle = title;
        lastResults = hits;
        show(Screen.RESULTS);
    }

    private void renderHits() {
        TextView title = text(searchTitle + " · " + lastResults.size() + (lastResults.size() == 400 ? "+" : ""),
                15, MUTED);
        title.setPadding(dp(16), 0, dp(16), 0);
        body.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        List<Entry> entries = new ArrayList<>();
        for (Session.Hit hit : lastResults) {
            entries.add(new Entry(hit.text(), () -> openClass(hit.cls(), hit.position())));
        }
        list(entries, "Совпадений нет");
    }

    private void openClass(JavaClass cls, int position) {
        JavaClass parent = cls.getTopParentClass();
        DocumentTab existing = findTab(parent);
        if (existing != null && !existing.smali) {
            switchTab(existing, position);
            return;
        }
        DocumentTab tab = existing == null ? new DocumentTab(parent, parent.getName()) : existing;
        loadClass(tab, false, position);
    }

    private void loadClass(DocumentTab tab, boolean smali, int position) {
        JavaClass cls = (JavaClass) tab.source;
        task(smali ? "Чтение smali…" : "Декомпиляция класса…",
                () -> smali ? cls.getSmali() : cls.getCode(), code -> {
                    tab.text = code;
                    tab.smali = smali;
                    switchTab(tab, position);
                });
    }

    private void openResource(Object resource, String name) {
        DocumentTab existing = findTab(resource);
        if (existing != null) {
            switchTab(existing, -1);
            return;
        }
        DocumentTab tab = new DocumentTab(resource, name.substring(name.lastIndexOf('/') + 1));
        Session selected = session;
        task("Чтение ресурса…",
                () -> resource instanceof ResourceFile file ? selected.preview(file) : selected.preview((ResContainer) resource),
                preview -> {
                    tab.preview = preview;
                    tab.text = preview.text();
                    switchTab(tab, 0);
                });
    }

    private DocumentTab findTab(Object source) {
        for (DocumentTab tab : documents) {
            if (tab.source.equals(source)) {
                return tab;
            }
        }
        return null;
    }

    private void switchTab(DocumentTab tab, int position) {
        if (screen == Screen.CODE && current != null && codeScroll != null) {
            current.scroll = codeScroll.getScrollY();
        }
        if (!documents.contains(tab)) {
            if (documents.size() >= 16) {
                DocumentTab oldest = documents.get(0);
                documents.remove(oldest);
            }
            documents.add(tab);
        }
        current = tab;
        screen = Screen.CODE;
        render();
        if (position >= 0) {
            scrollTo(position);
        }
    }

    private void closeTab(DocumentTab tab) {
        documents.remove(tab);
        if (current == tab) {
            current = null;
            if (documents.isEmpty()) {
                show(Screen.CLASSES);
            } else {
                switchTab(documents.get(documents.size() - 1), -1);
            }
        } else {
            render();
        }
    }

    private void renderCode() {
        if (current == null) {
            return;
        }
        DocumentTab tab = current;
        HorizontalScrollView actions = new HorizontalScrollView(this);
        actions.setHorizontalScrollBarEnabled(false);
        LinearLayout links = row();
        links.setPadding(dp(8), 0, dp(8), 0);
        if (tab.source instanceof JavaClass cls) {
            links.addView(button(tab.smali ? "Java" : "Smali", () -> loadClass(tab, !tab.smali, 0)));
            links.addView(button("Символы", () -> symbols(cls)));
            if (!tab.smali) {
                links.addView(button("По позиции", () -> fromSelection(cls)));
            }
        }
        if (tab.preview != null && !tab.preview.resource().getSubFiles().isEmpty()) {
            links.addView(button("Файлы таблицы", () -> {
                List<Entry> entries = new ArrayList<>();
                for (ResContainer sub : tab.preview.resource().getSubFiles()) {
                    entries.add(new Entry(sub.getName(), () -> openResource(sub, sub.getName())));
                }
                choose("Ресурсы таблицы", entries);
            }));
        }
        if (!tab.text.isEmpty()) {
            links.addView(button("В тексте", () -> findInDocument(tab)));
            links.addView(button("Сохранить", () -> {
                pendingFile = tab;
                String extension = tab.source instanceof JavaClass ? (tab.smali ? ".smali" : ".java") : "";
                create(SAVE_FILE, tab.title + extension, "text/plain");
            }));
        }
        links.addView(button("Закрыть", () -> closeTab(tab)));
        actions.addView(links);
        body.addView(actions);

        ScrollView vertical = new ScrollView(this);
        codeScroll = vertical;
        vertical.setFillViewport(true);
        body.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab.preview != null && tab.preview.image() != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(tab.preview.image());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(tab.title);
            vertical.addView(image, new ScrollView.LayoutParams(-1, -2));
            return;
        }
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        vertical.addView(horizontal, new ScrollView.LayoutParams(-1, -2));
        TextView code = text("", 13, INK);
        code.setGravity(Gravity.TOP | Gravity.START);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setPadding(dp(16), dp(12), dp(16), dp(24));
        code.setText(highlight(tab.text));
        horizontal.addView(code, new HorizontalScrollView.LayoutParams(-2, -2));
        codeView = code;
        vertical.post(() -> vertical.scrollTo(0, tab.scroll));
    }

    private CharSequence highlight(String text) {
        if (text.length() > 150_000) {
            return text;
        }
        SpannableString content = new SpannableString(text);
        var matches = TOKENS.matcher(text);
        while (matches.find()) {
            int color;
            if (matches.group("comment") != null) {
                color = MUTED;
            } else if (matches.group("string") != null) {
                color = Color.rgb(233, 197, 137);
            } else if (matches.group("number") != null) {
                color = Color.rgb(220, 168, 234);
            } else {
                color = ACCENT;
            }
            content.setSpan(new ForegroundColorSpan(color), matches.start(), matches.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return content;
    }

    private void scrollTo(int position) {
        TextView view = codeView;
        ScrollView scroll = codeScroll;
        int expected = generation;
        if (view != null && scroll != null) {
            view.post(() -> {
                if (generation == expected && view.getLayout() != null) {
                    int safe = Math.max(0, Math.min(position, view.length()));
                    int line = view.getLayout().getLineForOffset(safe);
                    scroll.scrollTo(0, view.getLayout().getLineTop(line));
                }
            });
        }
    }

    private void findInDocument(DocumentTab tab) {
        prompt("Поиск в файле", "Фрагмент текста", "", query -> {
            if (query.isEmpty()) {
                return;
            }
            var matches = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(tab.text);
            List<Entry> entries = new ArrayList<>();
            while (entries.size() < 400 && matches.find()) {
                int position = matches.start();
                int start = tab.text.lastIndexOf('\n', position - 1) + 1;
                int end = tab.text.indexOf('\n', position);
                String line = tab.text.substring(start, end < 0 ? tab.text.length() : end).trim();
                entries.add(new Entry(line.length() > 180 ? line.substring(0, 180) + "…" : line,
                        () -> scrollTo(position)));
            }
            choose("Совпадения · " + entries.size(), entries);
        });
    }

    private void symbols(JavaClass cls) {
        task("Чтение символов…", () -> {
            List<JavaNode> nodes = new ArrayList<>();
            nodes.add(cls);
            nodes.addAll(cls.getInnerClasses());
            nodes.addAll(cls.getFields());
            nodes.addAll(cls.getMethods());
            return nodes;
        }, nodes -> {
            List<Entry> entries = new ArrayList<>();
            for (JavaNode node : nodes) {
                entries.add(new Entry(node.toString(), () -> symbolActions(node)));
            }
            choose("Символы класса", entries);
        });
    }

    private void fromSelection(JavaClass cls) {
        int chosen = codeView.getSelectionStart();
        if (chosen < 0) {
            toast("Выделите имя символа в коде");
            return;
        }
        Session selected = session;
        task("Поиск символа…", () -> {
            var info = cls.getCodeInfo();
            JavaNode node = selected.jadx.getJavaNodeAtPosition(info, chosen);
            return node != null ? node : selected.jadx.getClosestJavaNode(info, chosen);
        }, node -> {
            if (node == null) {
                toast("Символ не найден");
            } else {
                symbolActions(node);
            }
        });
    }

    private void symbolActions(JavaNode node) {
        List<Entry> options = new ArrayList<>();
        options.add(new Entry("К объявлению", () -> {
            JavaClass parent = node.getTopParentClass();
            task("Чтение объявления…", () -> {
                parent.getCode();
                return node.getDefPos();
            }, position -> openClass(parent, position));
        }));
        options.add(new Entry("Найти использования", () -> {
            Session selected = session;
            task("Поиск использований…", () -> selected.usages(node),
                    hits -> showHits("Использования · " + node.getName(), hits));
        }));
        if (JadxNodeRef.forJavaNode(node) != null) {
            options.add(new Entry("Переименовать", () -> edit(node, true)));
            options.add(new Entry("Комментарий", () -> edit(node, false)));
        }
        options.add(new Entry("Копировать имя", () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("JADX", node.getFullName()));
        }));
        choose(node.getFullName(), options);
    }

    private void edit(JavaNode node, boolean rename) {
        Session selected = session;
        JadxNodeRef reference = JadxNodeRef.forJavaNode(node);
        var entries = rename ? selected.project.renames : selected.project.comments;
        prompt(rename ? "Псевдоним" : "Комментарий", "Пустое поле удаляет запись",
                entries.getOrDefault(reference, ""), value -> {
            if (rename && !value.isBlank() && !Pattern.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*", value)) {
                toast("Введите корректный идентификатор Java");
                return;
            }
            task(rename ? "Обновление имён…" : "Сохранение комментария…", () -> {
                selected.edit(node, value, rename);
                return node.getTopParentClass();
            }, cls -> {
                persist();
                resetViews();
                openClass(cls, 0);
            });
        });
    }

    private <T> void task(String caption, Callable<T> work, Consumer<T> deliver) {
        if (busy || destroyed) {
            return;
        }
        busy = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(caption);
        worker.execute(() -> {
            T value = null;
            Throwable error = null;
            try {
                value = work.call();
            } catch (Exception | LinkageError | OutOfMemoryError failure) {
                error = failure;
            }
            T result = value;
            Throwable failure = error;
            runOnUiThread(() -> {
                if (destroyed) {
                    if (result instanceof Session abandoned) {
                        closeQuietly(abandoned);
                    }
                    return;
                }
                busy = false;
                progress.setVisibility(View.GONE);
                status.setText(session == null ? "JADX " + BuildConfig.JADX_VERSION :
                        session.classes.size() + " классов  ·  " + session.resources.size() + " ресурсов");
                if (failure == null) {
                    deliver.accept(result);
                } else {
                    reportError(failure);
                }
            });
        });
    }

    private void reportError(Throwable error) {
        android.util.Log.e("JADX", "Operation failed", error);
        String message = error.getMessage();
        new AlertDialog.Builder(this).setTitle("Операция не выполнена")
                .setMessage(message == null ? error.getClass().getSimpleName() : message)
                .setPositiveButton("Закрыть", null).show();
    }

    private void choose(String title, List<Entry> entries) {
        var dialog = new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Закрыть", null);
        if (entries.isEmpty()) {
            dialog.setMessage("Список пуст");
        } else {
            dialog.setItems(entries.stream().map(Entry::label).toArray(String[]::new),
                    (ignored, which) -> entries.get(which).open().run());
        }
        dialog.show();
    }

    private void prompt(String title, String hint, String value, Consumer<String> action) {
        LinearLayout form = form();
        EditText input = editText(hint);
        input.setText(value);
        form.addView(input);
        new AlertDialog.Builder(this).setTitle(title).setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("ОК", (dialog, which) -> action.accept(input.getText().toString())).show();
    }

    private void list(List<Entry> entries, String emptyLabel) {
        if (entries.isEmpty()) {
            TextView empty = text(emptyLabel, 15, MUTED);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            body.addView(empty);
            return;
        }
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                entries.stream().map(Entry::label).toArray(String[]::new)));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!busy) {
                entries.get(position).open().run();
            }
        });
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout form() {
        LinearLayout layout = column();
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));
        return layout;
    }

    private EditText editText(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return input;
    }

    private CheckBox check(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        return box;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(INK);
        button.setTextSize(14);
        button.setMinWidth(dp(44));
        button.setMinHeight(dp(48));
        button.setMinimumWidth(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(view -> {
            if (!busy) {
                action.run();
            }
        });
        return button;
    }

    private GradientDrawable pill(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(ColorStateList.valueOf(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
