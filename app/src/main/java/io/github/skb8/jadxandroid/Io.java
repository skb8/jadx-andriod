package io.github.skb8.jadxandroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Io {
    private Io() {}

    static void copy(InputStream input, OutputStream output, long max) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int size;
        while ((size = input.read(buffer)) != -1) {
            total += size;
            if (total > max) {
                throw new IOException("Превышен допустимый размер файла");
            }
            output.write(buffer, 0, size);
        }
    }

    static byte[] read(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, max);
        return output.toByteArray();
    }

    static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    static void writeText(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static void deleteTree(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    static void zip(Path folder, Path archive) throws IOException {
        try (var files = Files.walk(folder); var out = new ZipOutputStream(Files.newOutputStream(archive))) {
            var iterator = files.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                out.putNextEntry(new ZipEntry(folder.relativize(file).toString()));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }
}
