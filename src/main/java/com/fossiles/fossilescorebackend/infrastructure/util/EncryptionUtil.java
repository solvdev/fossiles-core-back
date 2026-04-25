package com.fossiles.fossilescorebackend.infrastructure.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utilidad para encriptar y desencriptar contraseñas usando AES-256-CBC
 *
 * Este util soporta:
 * 1) Formato seguro: Base64( IV(16 bytes) || ciphertext )
 * 2) Fallback para pruebas: si el payload es solo ciphertext Base64, intentará
 *    desencriptar usando el IV fijo configurado en 'encryption.iv' (si existe).
 */
@Component
public class EncryptionUtil {

    @Value("${encryption.key}")
    private String encryptionKey;

    @Value("${encryption.algorithm}")
    private String algorithm;

    @Value("${encryption.transformation}")
    private String transformation;

    private static final int KEY_LENGTH = 32; // 256 bits para AES-256
    private static final int IV_LENGTH = 16;  // 128 bits para IV

    /**
     * Desencripta un texto encriptado con AES-256-CBC
     * Formato esperado: Base64( IV(16 bytes) || ciphertext )
     * 
     * @param encryptedText String en Base64 que contiene: [IV (16 bytes)] + [datos encriptados con AES]
     * @return Contraseña desencriptada en texto plano
     */
    public String decrypt(String encryptedText) {
        try {
            // Paso 1: Decodificar Base64 (solo para obtener los bytes binarios)
            byte[] decoded = Base64.getDecoder().decode(encryptedText);

            // Validar que tenga al menos IV + algún dato
            if (decoded.length <= IV_LENGTH) {
                throw new IllegalArgumentException(
                    String.format("Encrypted data too short. Got %d bytes, need at least %d bytes (IV + ciphertext).", 
                        decoded.length, IV_LENGTH + 1)
                );
            }

            // Paso 2: Extraer IV (Initialization Vector) - primeros 16 bytes
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // Paso 3: Extraer datos encriptados (resto de bytes después del IV)
            byte[] cipherText = new byte[decoded.length - IV_LENGTH];
            System.arraycopy(decoded, IV_LENGTH, cipherText, 0, cipherText.length);

            // Paso 4: Desencriptar con AES-256-CBC
            return decryptWithIvAndKey(cipherText, ivSpec);
        } catch (IllegalArgumentException e) {
            // Re-lanzar IllegalArgumentException tal cual
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting password: " + e.getMessage() + 
                " (encryptedText length: " + (encryptedText != null ? encryptedText.length() : 0) + ")", e);
        }
    }

    public String encrypt(String plainText) {
        try {
            byte[] keyBytes = getKeyBytes();
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, algorithm);

            byte[] iv = new byte[IV_LENGTH];
            java.security.SecureRandom random = new java.security.SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance(transformation);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + encryptedBytes.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encryptedBytes, 0, combined, IV_LENGTH, encryptedBytes.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting password: " + e.getMessage(), e);
        }
    }

    private String decryptWithIvAndKey(byte[] cipherText, IvParameterSpec ivSpec) {
        try {
            byte[] keyBytes = getKeyBytes();
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, algorithm);
            Cipher cipher = Cipher.getInstance(transformation);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            byte[] decryptedBytes = cipher.doFinal(cipherText);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error during AES decryption: " + e.getMessage(), e);
        }
    }

    private byte[] getKeyBytes() {
        byte[] keyBytes = encryptionKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == KEY_LENGTH) {
            return keyBytes;
        } else if (keyBytes.length < KEY_LENGTH) {
            byte[] padded = new byte[KEY_LENGTH];
            for (int i = 0; i < KEY_LENGTH; i++) {
                padded[i] = keyBytes[i % keyBytes.length];
            }
            return padded;
        } else {
            byte[] truncated = new byte[KEY_LENGTH];
            System.arraycopy(keyBytes, 0, truncated, 0, KEY_LENGTH);
            return truncated;
        }
    }
}
