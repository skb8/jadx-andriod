package io.github.skb8.jadxandroid;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OpenJdkService extends Service {
    private static native String execute(String home, String jar, String directory, String[] args);
    private boolean started;

    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::run));

    @Override
    public IBinder onBind(Intent intent) {
        return endpoint.getBinder();
    }

    private boolean run(Message message) {
        if (started || message.what != 1) {
            return false;
        }
        started = true;
        Bundle job = message.getData();
        Thread worker = new Thread(() -> {
            String result;
            try {
                System.loadLibrary("jadx_openjdk");
                result = execute(job.getString("home"), job.getString("jar"),
                        job.getString("work"), job.getStringArray("args"));
            } catch (Throwable error) {
                result = "OpenJDK: " + error;
            }
            try {
                Files.writeString(Path.of(job.getString("work"), "openjdk-result"), result);
            } catch (IOException error) {
                android.util.Log.e("JADX", "Could not store OpenJDK result", error);
            } finally {
                Process.killProcess(Process.myPid());
            }
        }, "openjdk");
        worker.start();
        return true;
    }
}
