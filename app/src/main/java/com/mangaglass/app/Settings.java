package com.mangaglass.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Settings {
    // Keep the existing image-profile namespace so upgrades retain its encrypted key and model.
    private static final String PREFIX = "vision_";
    private static final String ALIAS = "mangaglass.api.key.v1";
    private final SharedPreferences preferences;
    public Settings(Context context) {
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        // Replace the legacy single free-text timing summary with numeric timing records.
        if(preferences.contains("last_timing")) preferences.edit().remove("last_timing").apply();
    }
    public Profile profile() throws Exception {
        String p = PREFIX;
        String url = preferences.getString(p + "url", ""), key = key(p);
        return new Profile(url, preferences.getString(p + "model", ""), key, preferences.getString(p + "thinking", "auto"),
                preferences.getBoolean(p + "lossless", false), models(url, key));
    }
    public void saveProfile(String url, String model, String newKey) throws Exception {
        saveProfile(url, model, newKey, "auto", false);
    }
    public void saveProfile(String url, String model, String newKey, String thinking, boolean lossless) throws Exception {
        saveProfile(url,model,newKey,thinking,lossless,null);
    }
    public void saveProfile(String url, String model, String newKey, String thinking, boolean lossless, java.util.List<ModelCatalog.Model> models) throws Exception {
        TranslationProtocol.endpoint(url);
        if (model.trim().isEmpty()) throw new IllegalArgumentException("请填写服务商的模型名称");
        if (newKey.trim().isEmpty()) throw new IllegalArgumentException("请填写 API 密钥");
        String p = PREFIX;
        SharedPreferences.Editor edit = preferences.edit().putString(p + "url", url.trim()).putString(p + "model", model.trim())
                .putString(p + "thinking", RequestPolicy.VALUES[RequestPolicy.index(thinking)]).putBoolean(p + "lossless", lossless);
        if (!scope(url, newKey).equals(preferences.getString(p + "models_scope", ""))) edit.remove(p + "models").remove(p + "models_scope");
        if (models != null) edit.putString(p + "models_scope",scope(url,newKey)).putString(p + "models",ModelCatalog.serialize(models));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret());
        edit.putString(p + "ciphertext", Base64.encodeToString(cipher.doFinal(newKey.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP));
        edit.putString(p + "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        if (!edit.commit()) throw new IllegalStateException("设置保存失败，请重试");
    }
    private String key(String prefix) throws Exception {
        String stored = preferences.getString(prefix + "ciphertext", "");
        if (stored.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(preferences.getString(prefix + "iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    public void clearKey() {
        String p = PREFIX;
        preferences.edit().remove(p + "ciphertext").remove(p + "iv").remove(p + "models").remove(p + "models_scope").apply();
    }
    private java.util.List<ModelCatalog.Model> models() {
        try { return ModelCatalog.parse(preferences.getString(PREFIX + "models", "{\"data\":[]}")); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }
    public java.util.List<ModelCatalog.Model> models(String url, String key) {
        try {
            if (scope(url, key).equals(preferences.getString(PREFIX + "models_scope", ""))) return models();
        } catch (Exception ignored) { }
        return java.util.Collections.emptyList();
    }
    public void saveModels(Profile profile, java.util.List<ModelCatalog.Model> models) throws Exception {
        String p = PREFIX;
        if (!preferences.edit().putString(p + "models_scope", scope(profile.url, profile.key))
                .putString(p + "models", ModelCatalog.serialize(models)).commit()) throw new IllegalStateException("模型列表保存失败");
    }
    private static String scope(String url, String key) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest((ModelCatalog.endpoint(url) + "\n" + key.trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.encodeToString(hash, Base64.NO_WRAP);
    }
    private synchronized SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static final class Profile {
        public final String url, model, key, thinking;
        public final boolean lossless;
        public final ModelCatalog.Model modelInfo;
        public Profile(String url, String model, String key) { this(url, model, key, "auto", false, java.util.Collections.emptyList()); }
        public Profile(String url, String model, String key, String thinking, boolean lossless, java.util.List<ModelCatalog.Model> models) {
            this.url = url.trim(); this.model = model.trim(); this.key = key.trim(); this.thinking = thinking; this.lossless = lossless;
            this.modelInfo = ModelCatalog.find(models, this.model);
        }
        public boolean ready() { return !key.isEmpty() && !model.trim().isEmpty(); }
    }
}
