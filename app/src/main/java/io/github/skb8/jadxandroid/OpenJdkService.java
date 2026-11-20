package io.github.skb8.jadxandroid;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
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
        if (started || message.what != 1 || message.replyTo == null) {
            return false;
        }
        started = true;
        Bundle job = message.getData();
        Messenger reply = message.replyTo;
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
            } catch (IOException ignored) {
                // The binder reply carries the same result.
            }
            try {
                Message response = Message.obtain(null, 2);
                Bundle data = new Bundle();
                data.putString("result", result);
                response.setData(data);
                reply.send(response);
            } catch (RemoteException ignored) {
                // The Android UI was closed during the CLI operation.
            } finally {
                Process.killProcess(Process.myPid());
            }
        }, "openjdk");
        worker.start();
        return true;
    }
}
