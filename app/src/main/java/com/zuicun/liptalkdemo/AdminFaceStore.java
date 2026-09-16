package com.zuicun.liptalkdemo;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores face embeddings only; the source photos are never persisted. */
public final class AdminFaceStore {
    private static final String KEY_ALIAS = "lip_demo_admin_faces";
    private static final String FILE_NAME = "admin_faces.dat";
    private static final int FORMAT_VERSION = 2;
    private final File file;
    private final List<Record> records = new ArrayList<>();

    public AdminFaceStore(Context context) throws Exception {
        file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) records.addAll(decode(decrypt(Files.readAllBytes(file.toPath()))));
    }

    public synchronized Record add(
            String name, float[] embedding, byte[] avatarJpeg, long createdAt
    ) throws Exception {
        Record record = new Record(
                UUID.randomUUID().toString(),
                name,
                embedding.clone(),
                avatarJpeg.clone(),
                createdAt);
        records.add(record);
        save();
        return record;
    }

    public synchronized void delete(String id) throws Exception {
        records.removeIf(item -> item.id.equals(id));
        save();
    }

    public synchronized void clear() throws Exception {
        records.clear();
        save();
    }

    public synchronized List<Record> list() {
        return new ArrayList<>(records);
    }

    public synchronized void reload() throws Exception {
        records.clear();
        if (file.exists()) records.addAll(decode(decrypt(Files.readAllBytes(file.toPath()))));
    }

    private void save() throws Exception {
        byte[] encrypted = encrypt(encode(records));
        Files.write(file.toPath(), encrypted);
    }

    private static byte[] encode(List<Record> items) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_VERSION);
            output.writeInt(items.size());
            for (Record item : items) {
                output.writeUTF(item.id);
                output.writeUTF(item.name);
                output.writeInt(item.embedding.length);
                for (float value : item.embedding) output.writeFloat(value);
                output.writeLong(item.createdAt);
                output.writeInt(item.avatarJpeg.length);
                output.write(item.avatarJpeg);
            }
        }
        return bytes.toByteArray();
    }

    private static List<Record> decode(byte[] bytes) throws Exception {
        List<Record> items = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readInt();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IllegalStateException("管理员数据版本不兼容");
            }
            int count = input.readInt();
            if (count < 0 || count > 100) throw new IllegalStateException("管理员数据损坏");
            for (int index = 0; index < count; index++) {
                String id = input.readUTF();
                String name = input.readUTF();
                int length = input.readInt();
                if (length <= 0 || length > 4096) throw new IllegalStateException("人脸特征数据损坏");
                float[] embedding = new float[length];
                for (int position = 0; position < length; position++) {
                    embedding[position] = input.readFloat();
                }
                long createdAt = version >= 2 ? input.readLong() : 0L;
                byte[] avatar = new byte[0];
                if (version >= 2) {
                    int avatarLength = input.readInt();
                    if (avatarLength < 0 || avatarLength > 2_000_000) {
                        throw new IllegalStateException("管理员头像数据损坏");
                    }
                    avatar = new byte[avatarLength];
                    input.readFully(avatar);
                }
                items.add(new Record(id, name, embedding, avatar, createdAt));
            }
        }
        return items;
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(iv.length);
            output.write(iv);
            output.write(encrypted);
        }
        return bytes.toByteArray();
    }

    private static byte[] decrypt(byte[] stored) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(stored))) {
            int ivLength = input.readInt();
            if (ivLength < 12 || ivLength > 32) throw new IllegalStateException("管理员数据损坏");
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            byte[] encrypted = new byte[input.available()];
            input.readFully(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return cipher.doFinal(encrypted);
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static final class Record {
        public final String id;
        public final String name;
        public final float[] embedding;
        public final byte[] avatarJpeg;
        public final long createdAt;

        Record(String id, String name, float[] embedding, byte[] avatarJpeg, long createdAt) {
            this.id = id;
            this.name = name;
            this.embedding = embedding;
            this.avatarJpeg = avatarJpeg;
            this.createdAt = createdAt;
        }
    }
}
