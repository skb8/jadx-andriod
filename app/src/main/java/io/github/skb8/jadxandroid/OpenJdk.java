package io.github.skb8.jadxandroid;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import javax.net.ssl.HttpsURLConnection;

final class OpenJdk {
    private static final String TAG = "openjdk-2026.11.01-0456";
    private static final String RUNTIMES =
            "https://github.com/Project-crafting/openjdk-android-hostcraft/releases/download/" + TAG + "/";
    private static final String JADX_URL =
            "https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip";
    private static final String JADX_HASH = "545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974";
    private static final String RESULT_FILE = "openjdk-result";
    private final Context context;

    OpenJdk(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean installed(int version) {
        Path home = home(version);
        return Files.isRegularFile(home.resolve("lib/server/libjvm.so"))
                && Files.isRegularFile(home.resolve("lib/modules"))
                && Files.isRegularFile(cli());
    }

    boolean supported() {
        return switch (Build.SUPPORTED_ABIS[0]) {
            case "arm64-v8a", "x86_64" -> true;
            default -> false;
        };
    }

    void install(int version) throws Exception {
        if (installed(version)) {
            return;
        }
        String architecture = switch (Build.SUPPORTED_ABIS[0]) {
            case "arm64-v8a" -> "aarch64";
            case "x86_64" -> "x86_64";
            default -> throw new IOException("OpenJDK недоступна для этой архитектуры");
        };
        String digest = switch (version + "-" + architecture) {
            case "17-aarch64" -> "2fc9cc32518abd63ad50a86c91d208496b47251ec9e1097cf4e05d2fe3ce39c1";
            case "17-x86_64" -> "e37b045cf1a18efd7c65f2ab27aed752467df385f4c4e7b2083f673c406b601b";
            case "21-aarch64" -> "c08cfde14f20ea23df983389beb525e556585dad467f434315a2094d2add3f2f";
            case "21-x86_64" -> "461592a74366f9f9dd0dc1f6bfc44fd266c2df3e54ed1775ab6e51939cbcd267";
            default -> throw new IOException("Версия OpenJDK недоступна");
        };
        Path directory = Files.createDirectories(home(version).getParent());
        Path temporary = Files.createTempDirectory(directory, "install-");
        try {
            if (!Files.isRegularFile(cli())) {
                Path release = temporary.resolve("jadx.zip");
                download(JADX_URL, JADX_HASH, release, 90 * 1024 * 1024);
                Path jar = temporary.resolve("jadx.jar");
                try (ZipFile zip = new ZipFile(release.toFile());
                     InputStream input = zip.getInputStream(zip.getEntry("lib/jadx-1.5.6-all.jar"));
                     var output = Files.newOutputStream(jar)) {
                    Io.copy(input, output, 90 * 1024 * 1024L);
                }
                Files.move(jar, cli(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.isRegularFile(home(version).resolve("lib/server/libjvm.so"))
                    && Files.isRegularFile(home(version).resolve("lib/modules"))) {
                return;
            }
            Path archive = temporary.resolve("runtime.tar.xz");
            download(RUNTIMES + "jre-" + version + "-" + architecture + ".tar.xz", digest, archive, 45 * 1024 * 1024);
            Path staged = Files.createDirectory(temporary.resolve("runtime"));
            extractRuntime(archive, staged, version);
            if (!Files.isRegularFile(staged.resolve("lib/server/libjvm.so"))
                    || !Files.isRegularFile(staged.resolve("lib/modules"))) {
                throw new IOException("Неполный архив OpenJDK");
            }
            Io.deleteTree(home(version));
            Files.move(staged, home(version), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Io.deleteTree(temporary);
        }
    }

    private static void extractRuntime(Path archive, Path target, int version) throws IOException {
        String directory = "java-" + version + "-openjdk";
        String prefix = directory + "/";
        long total = 0;
        try (var tar = new TarArchiveInputStream(new XZInputStream(Files.newInputStream(archive), 96 * 1024))) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (name.isEmpty() || name.equals(".") || name.equals(directory) || name.equals(prefix)) {
                    continue;
                }
                if (!name.startsWith(prefix) || entry.isSymbolicLink() || entry.isLink()
                        || (!entry.isDirectory() && !entry.isFile())) {
                    throw new IOException("Неожиданная запись OpenJDK " + name);
                }
                Path file = target.resolve(name.substring(prefix.length())).normalize();
                if (!file.startsWith(target) || file.equals(target)) {
                    throw new IOException("Недопустимый путь OpenJDK");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                    continue;
                }
                total += entry.getSize();
                if (total > 220 * 1024 * 1024L) {
                    throw new IOException("Превышен размер распакованной OpenJDK");
                }
                Files.createDirectories(file.getParent());
                try (var output = Files.newOutputStream(file)) {
                    Io.copy(tar, output, entry.getSize());
                }
            }
        }
    }

    private static void download(String address, String expected, Path file, int max) throws Exception {
        URL url = new URL(address);
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("Сервер прислал перенаправление без адреса");
                    }
                    url = new URL(url, location);
                    if (!"https".equals(url.getProtocol())) {
                        throw new IOException("Для скачивания требуется HTTPS");
                    }
                    continue;
                }
                if (status != 200) {
                    throw new IOException("Скачивание завершилось с HTTP " + status);
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var in = new DigestInputStream(connection.getInputStream(), digest);
                     var out = Files.newOutputStream(file)) {
                    Io.copy(in, out, max);
                }
                if (!expected.equals(hex(digest.digest()))) {
                    throw new IOException("Контрольная сумма скачанного файла не совпадает");
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Слишком много перенаправлений при скачивании");
    }

    String run(int version, Path work, String... arguments) throws Exception {
        if (!installed(version)) {
            throw new IOException("Сначала скачайте OpenJDK " + version);
        }
        CompletableFuture<String> answer = new CompletableFuture<>();
        Path resultFile = work.resolve(RESULT_FILE);
        Files.deleteIfExists(resultFile);
        Bundle job = new Bundle();
        job.putString("home", home(version).toString());
        job.putString("jar", cli().toString());
        job.putString("work", work.toString());
        job.putStringArray("args", arguments);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (answer.isDone()) {
                    return;
                }
                Message request = Message.obtain(null, 1);
                request.setData(job);
                try {
                    new Messenger(service).send(request);
                } catch (RemoteException error) {
                    answer.completeExceptionally(error);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                try {
                    if (Files.exists(resultFile)) {
                        answer.complete(Files.readString(resultFile));
                    } else {
                        answer.completeExceptionally(new IOException("Процесс OpenJDK завершился досрочно"));
                    }
                } catch (IOException error) {
                    answer.completeExceptionally(error);
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                onServiceDisconnected(name);
            }
        };
        Intent intent = new Intent(context, OpenJdkService.class);
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw new IOException("Не удалось запустить процесс OpenJDK");
        }
        try {
            String result = answer.get(8, TimeUnit.MINUTES);
            if (!result.startsWith("OK ")) {
                throw new IOException(result);
            }
            return result.substring(3);
        } finally {
            context.unbindService(connection);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 0xff) >>> 4, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private Path home(int version) {
        return context.getFilesDir().toPath().resolve("openjdk").resolve(TAG + "-" + version);
    }

    private Path cli() {
        return context.getFilesDir().toPath().resolve("openjdk/jadx-1.5.6-all.jar");
    }
}
