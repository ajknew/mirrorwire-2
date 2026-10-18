package com.example.adb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.example.log.SystemLogger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Handles ADB RSA key generation, persistence, token signing, and public key formatting
 * into the standard Android ADB RSAPublicKey binary structure.
 */
public class AdbCrypto {
    private static final String PREF_NAME = "mirrorwire_adb_crypto";
    private static final String PREF_PRIV_KEY = "priv_key";
    private static final String PREF_PUB_KEY = "pub_key";

    public static final int KEY_LENGTH_BITS = 2048;
    public static final int KEY_LENGTH_WORDS = KEY_LENGTH_BITS / 32; // 64 words

    // ASN.1 DER prefix for SHA-1
    private static final byte[] SHA1_ASN1_PREFIX = new byte[] {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final KeyPair mKeyPair;

    public AdbCrypto(KeyPair keyPair) {
        mKeyPair = keyPair;
    }

    public static AdbCrypto loadOrCreate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String privStr = prefs.getString(PREF_PRIV_KEY, null);
        String pubStr = prefs.getString(PREF_PUB_KEY, null);

        if (privStr != null && pubStr != null) {
            try {
                byte[] privBytes = Base64.decode(privStr, Base64.DEFAULT);
                byte[] pubBytes = Base64.decode(pubStr, Base64.DEFAULT);

                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPrivateKey privKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                RSAPublicKey pubKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(pubBytes));

                SystemLogger.getInstance().log("ADB", "Loaded existing RSA-2048 ADB key pair");
                return new AdbCrypto(new KeyPair(pubKey, privKey));
            } catch (Exception e) {
                SystemLogger.getInstance().log("ADB", "Failed to restore RSA key pair: " + e.getMessage() + ", regenerating...");
            }
        }

        try {
            SystemLogger.getInstance().log("ADB", "Generating new 2048-bit RSA key pair for ADB...");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(KEY_LENGTH_BITS);
            KeyPair kp = kpg.generateKeyPair();

            String newPrivStr = Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP);
            String newPubStr = Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP);

            prefs.edit()
                    .putString(PREF_PRIV_KEY, newPrivStr)
                    .putString(PREF_PUB_KEY, newPubStr)
                    .apply();

            SystemLogger.getInstance().log("ADB", "Successfully generated and persisted RSA key pair");
            return new AdbCrypto(kp);
        } catch (Exception e) {
            SystemLogger.getInstance().log("ERROR", "Error generating RSA keys: " + e.getMessage());
            throw new RuntimeException("Cannot initialize ADB crypto", e);
        }
    }

    /**
     * Signs the token sent by the device during AUTH challenge (type=1).
     */
    public byte[] signAdbToken(byte[] token) throws Exception {
        byte[] input;
        if (token.length == 20) {
            // Prepend SHA-1 ASN.1 prefix to 20-byte token
            input = new byte[SHA1_ASN1_PREFIX.length + token.length];
            System.arraycopy(SHA1_ASN1_PREFIX, 0, input, 0, SHA1_ASN1_PREFIX.length);
            System.arraycopy(token, 0, input, SHA1_ASN1_PREFIX.length, token.length);
        } else {
            input = token;
        }

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, mKeyPair.getPrivate());
        return cipher.doFinal(input);
    }

    /**
     * Formats the RSA public key into standard Android adb RSAPublicKey binary format:
     * struct RSAPublicKey {
     *     int len;                  // 64 (for 2048-bit)
     *     uint32_t n0inv;           // -1 / n[0] mod 2^32
     *     uint32_t n[64];           // modulus as little-endian words
     *     uint32_t rr[64];          // R^2 mod n as little-endian words
     *     int exponent;             // 65537
     * }
     * Then Base64 encoded and suffixed with " host@mirrorwire\0"
     */
    public byte[] getAdbPublicKeyBlob() {
        RSAPublicKey pubKey = (RSAPublicKey) mKeyPair.getPublic();
        BigInteger n = pubKey.getModulus();
        BigInteger e = pubKey.getPublicExponent();

        // R = 2^2048
        BigInteger r = BigInteger.ONE.shiftLeft(KEY_LENGTH_BITS);
        // RR = R^2 mod n
        BigInteger rr = r.multiply(r).mod(n);

        // n0inv = -1 / n[0] mod 2^32
        BigInteger b32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.remainder(b32);
        BigInteger n0inv = n0.modInverse(b32).negate().remainder(b32);
        if (n0inv.signum() < 0) {
            n0inv = n0inv.add(b32);
        }

        // Buffer: 4 (len) + 4 (n0inv) + 64*4 (n) + 64*4 (rr) + 4 (exponent) = 524 bytes
        ByteBuffer bb = ByteBuffer.allocate(524);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        bb.putInt(KEY_LENGTH_WORDS);
        bb.putInt((int) n0inv.longValue());

        // Modulus words (little endian 32-bit words)
        BigInteger remN = n;
        for (int i = 0; i < KEY_LENGTH_WORDS; i++) {
            bb.putInt((int) remN.remainder(b32).longValue());
            remN = remN.divide(b32);
        }

        // RR words
        BigInteger remRR = rr;
        for (int i = 0; i < KEY_LENGTH_WORDS; i++) {
            bb.putInt((int) remRR.remainder(b32).longValue());
            remRR = remRR.divide(b32);
        }

        bb.putInt(e.intValue());

        byte[] rawAdbKey = bb.array();
        String b64Key = Base64.encodeToString(rawAdbKey, Base64.NO_WRAP);
        String finalPayload = b64Key + " host@mirrorwire\0";
        return finalPayload.getBytes();
    }
}
