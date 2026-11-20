package io.github.skb8.jadxandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.zip.ZipInputStream;

import jadx.api.data.impl.JadxNodeRef;

@RunWith(AndroidJUnit4.class)
public final class JadxSmokeTest {
    private static final String SMALI = """
            .class public final Lsample/Smoke;
            .super Ljava/lang/Object;

            .method public static answer()I
                .registers 1
                const/16 v0, 0x2a
                return v0
            .end method

            .method public static caller()I
                .registers 1
                invoke-static {}, Lsample/Smoke;->answer()I
                move-result v0
                return v0
            .end method
            """;

    @Test
    public void opensSmaliSearchesAndExportsJava() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri input = document(context, "Smoke.smali");
        Uri output = document(context, "project.zip");
        try {
            try (var stream = Objects.requireNonNull(context.getContentResolver().openOutputStream(input))) {
                stream.write(SMALI.getBytes(StandardCharsets.UTF_8));
            }
            Project project = Project.parse(new Project(List.of(input)).serialize());
            try (Session session = new Session(context, project, new PluginStore(context))) {
                assertEquals(1, session.classes.size());
                var cls = session.classes.get(0);
                assertEquals("sample.Smoke", cls.getFullName());
                assertTrue(cls.getCode().contains("return 42"));
                assertTrue(cls.getSmali().contains("answer()I"));
                assertTrue(session.search("return 42", false, true, true).size() > 0);
                var method = cls.getMethods().stream().filter(node -> node.getName().equals("answer"))
                        .findFirst().orElseThrow();
                assertTrue(session.search("answer", false, true, true).stream()
                        .anyMatch(hit -> hit.text().equals(method.getFullName()) && hit.position() > 0));
                var caller = cls.getMethods().stream().filter(node -> node.getName().equals("caller"))
                        .findFirst().orElseThrow();
                assertTrue(session.calls(caller).callees().stream().anyMatch(call -> method.equals(call.target())));
                assertTrue(session.calls(method).callers().stream().anyMatch(call -> caller.equals(call.target())));
                session.edit(method, "test comment", false);
                assertEquals("test comment", project.comments.get(JadxNodeRef.forMth(method)));
                assertTrue(cls.getCode().contains("test comment"));
                session.export(output, false, false);
            }
            try (var archive = new ZipInputStream(Session.open(context, output))) {
                var entry = archive.getNextEntry();
                assertNotNull(entry);
                while (!entry.getName().equals("sources/sample/Smoke.java")) {
                    entry = archive.getNextEntry();
                    assertNotNull(entry);
                }
                assertTrue(new String(archive.readAllBytes(), StandardCharsets.UTF_8).contains("test comment"));
            }
            try (Session reduced = new Session(context, project, new PluginStore(context), true)) {
                var cls = reduced.classes.get(0);
                assertTrue(cls.getCode().contains("return 42"));
                assertTrue(reduced.search("return 42", false, true, true).size() > 0);
            }
        } finally {
            context.getContentResolver().delete(input, null, null);
            context.getContentResolver().delete(output, null, null);
        }
    }

    @Test
    public void returnsToTheOpenPackageAfterReadingCodeAndSwitchingSections() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri input = document(context, "Navigation.smali");
        var preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        String previous = preferences.getString("lastProject", null);
        try {
            try (var stream = Objects.requireNonNull(context.getContentResolver().openOutputStream(input))) {
                StringBuilder fields = new StringBuilder();
                for (int i = 0; i < 2_200; i++) {
                    fields.append(".field public static final testField").append(i)
                            .append(":I = 0x").append(Integer.toHexString(i)).append('\n');
                }
                String largeClass = SMALI.replace(".method public static answer()I", fields + "\n.method public static answer()I");
                stream.write(largeClass.getBytes(StandardCharsets.UTF_8));
            }
            preferences.edit().putString("lastProject", new Project(List.of(input)).serialize()).commit();
            try (var scenario = ActivityScenario.launch(MainActivity.class)) {
                visitView(scenario, "sample/", view -> hasLabel(view, "sample/"), true);
                visitView(scenario, "Smoke", view -> hasLabel(view, "Smoke"), true);
                visitView(scenario, "sample.Smoke", view -> hasLabel(view, "sample.Smoke"), false);
                visitView(scenario, "Следующая страница кода",
                        view -> TextUtils.equals("Следующая страница кода", view.getContentDescription()), true);
                visitView(scenario, "вторая страница кода", JadxSmokeTest::showsSecondPage, false);
                visitView(scenario, "Вернуться к списку",
                        view -> TextUtils.equals("Вернуться к списку", view.getContentDescription()), true);
                visitView(scenario, "класс Smoke в списке",
                        view -> view.getParent() instanceof ListView && hasLabel(view, "Smoke"), false);
                visitView(scenario, "вкладка Smoke",
                        view -> view instanceof android.widget.Button && hasLabel(view, "Smoke"), true);
                visitView(scenario, "сохранённая вторая страница кода", JadxSmokeTest::showsSecondPage, false);
                visitView(scenario, "Вернуться к списку",
                        view -> TextUtils.equals("Вернуться к списку", view.getContentDescription()), true);
                visitView(scenario, "Ресурсы", view -> hasLabel(view, "Ресурсы"), true);
                visitView(scenario, "Классы", view -> hasLabel(view, "Классы"), true);
                visitView(scenario, "класс Smoke в списке",
                        view -> view.getParent() instanceof ListView && hasLabel(view, "Smoke"), false);
            }
        } finally {
            preferences.edit().putString("lastProject", previous).commit();
            context.getContentResolver().delete(input, null, null);
        }
    }

    private static void visitView(ActivityScenario<MainActivity> scenario, String label,
                                  Predicate<View> selector, boolean click) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        AtomicBoolean found = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                View view = find(activity.getWindow().getDecorView(), selector);
                if (view == null) {
                    return;
                }
                found.set(true);
                if (click && view.getParent() instanceof ListView list) {
                    int position = list.getPositionForView(view);
                    assertTrue(list.performItemClick(view, position, list.getItemIdAtPosition(position)));
                } else if (click) {
                    assertTrue(view.performClick());
                }
            });
            if (found.get()) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Не найден элемент " + label);
    }

    private static boolean hasLabel(View view, String label) {
        return view instanceof TextView text && label.contentEquals(text.getText());
    }

    private static boolean showsSecondPage(View view) {
        return view instanceof TextView text && text.getText().toString().startsWith("2 / ");
    }

    private static View find(View view, Predicate<View> selector) {
        if (!view.isShown()) {
            return null;
        }
        if (selector.test(view)) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), selector);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private Uri document(Context context, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/JadxSmoke");
        return Objects.requireNonNull(context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values));
    }
}
