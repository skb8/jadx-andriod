package io.github.skb8.jadxandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
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
        } finally {
            context.getContentResolver().delete(input, null, null);
            context.getContentResolver().delete(output, null, null);
        }
    }

    @Test
    public void startsSelectedOpenJdkAndExportsJava() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String version = arguments.getString("openjdk");
        Assume.assumeTrue(version != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int major = Integer.parseInt(version);
        var runtime = new OpenJdk(context);
        var temporary = Files.createTempDirectory(context.getCacheDir().toPath(), "runtime-test-");
        try {
            runtime.install(major);
            assertTrue(runtime.run(major, temporary).startsWith(version + "."));
            var input = temporary.resolve("Smoke.smali");
            var output = Files.createDirectory(temporary.resolve("output"));
            Io.writeText(input, SMALI);
            assertEquals("0", runtime.run(major, temporary, "-q", "-d", output.toString(), input.toString()));
            assertTrue(Io.readText(output.resolve("sources/sample/Smoke.java")).contains("return 42"));
        } finally {
            Io.deleteTree(temporary);
        }
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
