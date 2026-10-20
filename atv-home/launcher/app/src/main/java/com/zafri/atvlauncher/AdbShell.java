package com.zafri.atvlauncher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * Runs shell commands through the TV's own network debugging (adbd on 127.0.0.1:5555), which is
 * how the launcher can really force-stop apps: that needs the shell user, not an app permission.
 * The first connection shows the system "Allow debugging?" prompt for the launcher's key; after
 * "Always allow" it connects silently. Blocking: call off the main thread.
 */
final class AdbShell {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000, MAX_DATA = 256 * 1024;
    /** DER prefix of a SHA-1 DigestInfo: adbd verifies the token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
            0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbShell(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** Connects and authenticates, waiting up to promptMs for the user to accept the prompt. */
    static AdbShell connect(Context ctx, int promptMs) throws IOException {
        KeyPair keys = loadOrCreateKeys(ctx);
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setSoTimeout(10_000);
        AdbShell adb = new AdbShell(s);
        try {
            adb.handshake(keys, promptMs);
            return adb;
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    private void handshake(KeyPair keys, int promptMs) throws IOException {
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentSignature = false;
        while (true) {
            Message m = read();
            if (m.command == CNXN) return;
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (!sentSignature) {
                send(AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data));
                sentSignature = true;
            } else {
                // Key not trusted yet: offer it, which shows the "Allow debugging?" prompt on screen.
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyString((RSAPublicKey) keys.getPublic()));
                socket.setSoTimeout(promptMs);
            }
        }
    }

    /** Runs one command and returns its output. */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        socket.setSoTimeout(30_000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                output.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                send(CLSE, local, m.arg0, new byte[0]);
                return output.toString("UTF-8");
            }
        }
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- wire format

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        out.write(header.array());
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > MAX_DATA) throw new IOException("bad adb message length " + length);
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- keys

    private static KeyPair loadOrCreateKeys(Context ctx) throws IOException {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub.der");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair keys = gen.generateKeyPair();
            Files.write(priv.toPath(), keys.getPrivate().getEncoded());
            Files.write(pub.toPath(), keys.getPublic().getEncoded());
            return keys;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** PKCS#1 v1.5 type 1 padding around SHA1_PREFIX + token, then a raw RSA private-key operation. */
    private static byte[] sign(KeyPair keys, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            Arrays.fill(block, (byte) 0xFF);
            block[0] = 0;
            block[1] = 1;
            int start = block.length - SHA1_PREFIX.length - token.length;
            block[start - 1] = 0;
            System.arraycopy(SHA1_PREFIX, 0, block, start, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, start + SHA1_PREFIX.length, token.length);
            Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
            rsa.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
            return rsa.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The key in adbd's format: base64 of Android's RSAPublicKey struct, then a name. */
    private static byte[] publicKeyString(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = n.mod(r32).modInverse(r32).negate().intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer struct = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        struct.putInt(64).putInt(n0inv).put(littleEndian(n)).put(littleEndian(rr))
                .putInt(key.getPublicExponent().intValue());
        String b64 = android.util.Base64.encodeToString(struct.array(), android.util.Base64.NO_WRAP);
        return (b64 + " atv-home launcher\0").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v) {
        byte[] be = v.toByteArray(), le = new byte[256];
        for (int i = 0; i < 256 && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
