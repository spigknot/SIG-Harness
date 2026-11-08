package com.sig.harness;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Small persistent settings store for the app. */
public final class Prefs {
    private static final String FILE = "sig_harness";
    public static final String DEFAULT_CWD = "/storage/emulated/0/Projetos";
    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrate();
    }

    private void migrate() {
        if (!sp.getBoolean("migratedV2", false)) {
            String current = sp.getString("cwd", null);
            if (current == null || current.equals("/data/data/com.termux/files/home")) {
                sp.edit().putString("cwd", DEFAULT_CWD).apply();
            }
            sp.edit().putBoolean("migratedV2", true).apply();
        }
    }

    public String token() {
        String token = sp.getString("token", null);
        if (token == null || token.length() < 16) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            token = sb.toString();
            sp.edit().putString("token", token).apply();
        }
        return token;
    }

    public String cwd() {
        String value = sp.getString("cwd", null);
        if (value == null || value.trim().isEmpty()) value = DEFAULT_CWD;
        return value;
    }

    public void setCwd(String value) {
        sp.edit().putString("cwd", value == null ? "" : value.trim()).apply();
    }

    public int basePort() {
        return 43117;
    }

    public int lastPort() {
        return sp.getInt("lastPort", 0);
    }

    public void setLastPort(int port) {
        sp.edit().putInt("lastPort", port).apply();
    }

    public String lastSessionId() {
        return sp.getString("lastSessionId", null);
    }

    public void setLastSessionId(String id) {
        sp.edit().putString("lastSessionId", id).apply();
    }

    public boolean autoStart() {
        return sp.getBoolean("autoStart", false);
    }

    public void setAutoStart(boolean value) {
        sp.edit().putBoolean("autoStart", value).apply();
    }

    public boolean boot() {
        return sp.getBoolean("boot", true);
    }

    public void setBoot(boolean value) {
        sp.edit().putBoolean("boot", value).apply();
    }

    public String modelValue() {
        return sp.getString("modelValue", "");
    }

    public String modelName() {
        return sp.getString("modelName", "");
    }

    public void setModel(String value, String name) {
        sp.edit().putString("modelValue", value == null ? "" : value)
                .putString("modelName", name == null ? "" : name).apply();
    }

    public String sttProvider() {
        String value = sp.getString("sttProvider", "muse");
        return "gemini".equals(value) ? "gemini" : "muse";
    }

    public void setSttProvider(String value) {
        sp.edit().putString("sttProvider", "gemini".equals(value) ? "gemini" : "muse").apply();
    }

    public String museKey() {
        String value = sp.getString("museKey", "");
        return value == null ? "" : value.trim();
    }

    public void setMuseKey(String value) {
        sp.edit().putString("museKey", value == null ? "" : value.trim()).apply();
    }

    public String geminiKey() {
        String value = sp.getString("geminiKey", "");
        return value == null ? "" : value.trim();
    }

    public void setGeminiKey(String value) {
        sp.edit().putString("geminiKey", value == null ? "" : value.trim()).apply();
    }

    public Set<String> archivedSessions() {
        return new HashSet<String>(sp.getStringSet("archivedSessions", Collections.<String>emptySet()));
    }

    public boolean isArchived(String id) {
        return id != null && archivedSessions().contains(id);
    }

    public void setArchived(String id, boolean archived) {
        if (id == null || id.isEmpty()) return;
        Set<String> set = archivedSessions();
        if (archived) set.add(id); else set.remove(id);
        sp.edit().putStringSet("archivedSessions", set).apply();
    }

    public Set<String> deletedSessions() {
        return new HashSet<String>(sp.getStringSet("deletedSessions", Collections.<String>emptySet()));
    }

    public boolean isDeleted(String id) {
        return id != null && deletedSessions().contains(id);
    }

    public void deleteSession(String id) {
        if (id == null || id.isEmpty()) return;
        Set<String> deleted = deletedSessions();
        deleted.add(id);
        Set<String> archived = archivedSessions();
        archived.remove(id);
        sp.edit().putStringSet("deletedSessions", deleted)
                .putStringSet("archivedSessions", archived).apply();
    }

    public String reasoning() {
        return sp.getString("reasoning", "max");
    }

    public String reasoningName() {
        return sp.getString("reasoningName", "Max");
    }

    public void setReasoning(String value, String name) {
        sp.edit().putString("reasoning", value == null ? "" : value)
                .putString("reasoningName", name == null ? "" : name).apply();
    }
}
