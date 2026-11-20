package io.github.skb8.jadxandroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

    static void deleteTree(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
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
