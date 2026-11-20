package io.github.skb8.jadxandroid;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.api.DecompilationMode;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;

final class Project {
    final List<Uri> inputs;
    boolean deobfuscate;
    boolean inconsistent;
    DecompilationMode mode = DecompilationMode.AUTO;
    final Map<JadxNodeRef, String> renames = new LinkedHashMap<>();
    final Map<JadxNodeRef, String> comments = new LinkedHashMap<>();
    final Map<String, String> pluginOptions = new LinkedHashMap<>();

    Project(List<Uri> inputs) {
        this.inputs = List.copyOf(inputs);
    }

    JadxCodeData codeData() {
        JadxCodeData data = new JadxCodeData();
        data.setRenames(new ArrayList<>(renames.entrySet().stream()
                .map(e -> new JadxCodeRename(e.getKey(), e.getValue())).collect(java.util.stream.Collectors.toList())));
        data.setComments(new ArrayList<>(comments.entrySet().stream()
                .map(e -> new JadxCodeComment(e.getKey(), e.getValue())).collect(java.util.stream.Collectors.toList())));
        return data;
    }

    String serialize() throws JSONException {
        JSONArray files = new JSONArray();
        for (Uri uri : inputs) {
            files.put(uri.toString());
        }
        return new JSONObject()
                .put("version", 1)
                .put("inputs", files)
                .put("deobfuscate", deobfuscate)
                .put("inconsistent", inconsistent)
                .put("mode", mode.name())
                .put("renames", encode(renames))
                .put("comments", encode(comments))
                .put("pluginOptions", new JSONObject(pluginOptions))
                .toString(2);
    }

    static Project parse(String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        if (json.getInt("version") != 1) {
            throw new JSONException("Неподдерживаемая версия проекта");
        }
        JSONArray files = json.getJSONArray("inputs");
        List<Uri> inputs = new ArrayList<>();
        for (int index = 0; index < files.length(); index++) {
            Uri uri = Uri.parse(files.getString(index));
            if (!"content".equals(uri.getScheme())) {
                throw new JSONException("Проект должен ссылаться на документы Android");
            }
            inputs.add(uri);
        }
        Project project = new Project(inputs);
        project.deobfuscate = json.optBoolean("deobfuscate");
        project.inconsistent = json.optBoolean("inconsistent");
        project.mode = DecompilationMode.valueOf(json.optString("mode", "AUTO"));
        decode(json.getJSONArray("renames"), project.renames);
        decode(json.getJSONArray("comments"), project.comments);
        JSONObject options = json.optJSONObject("pluginOptions");
        if (options != null) {
            for (var keys = options.keys(); keys.hasNext();) {
                String key = keys.next();
                project.pluginOptions.put(key, options.getString(key));
            }
        }
        return project;
    }

    private static JSONArray encode(Map<JadxNodeRef, String> entries) throws JSONException {
        JSONArray array = new JSONArray();
        for (var entry : entries.entrySet()) {
            JadxNodeRef ref = entry.getKey();
            array.put(new JSONObject()
                    .put("type", ref.getType().name())
                    .put("class", ref.getDeclaringClass())
                    .put("id", ref.getShortId())
                    .put("text", entry.getValue()));
        }
        return array;
    }

    private static void decode(JSONArray array, Map<JadxNodeRef, String> entries) throws JSONException {
        for (int index = 0; index < array.length(); index++) {
            JSONObject entry = array.getJSONObject(index);
            JadxNodeRef ref = new JadxNodeRef(RefType.valueOf(entry.getString("type")),
                    entry.getString("class"), entry.isNull("id") ? null : entry.getString("id"));
            entries.put(ref, entry.getString("text"));
        }
    }
}
