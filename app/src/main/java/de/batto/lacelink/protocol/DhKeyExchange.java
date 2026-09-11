package de.batto.lacelink.protocol;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Implements the standard MODP-DH and AES primitives required by the shoe handshake. */
public final class DhKeyExchange {
    private static final BigInteger GENERATOR = BigInteger.valueOf(2);
    private static final String[] PRIMES = {
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A3620FFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381FFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA237327FFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF"
    };

    private final DHParameterSpec parameters;
    private final KeyPair keyPair;

    public DhKeyExchange(int group) throws Exception {
        if (group < 0 || group >= PRIMES.length) {
            throw new IllegalArgumentException("Unbekannte MODP-Gruppe: " + group);
        }
        parameters = new DHParameterSpec(new BigInteger(PRIMES[group], 16), GENERATOR);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("DH");
        generator.initialize(parameters);
        keyPair = generator.generateKeyPair();
    }

    public byte[] publicKey() {
        byte[] encoded = ((DHPublicKey) keyPair.getPublic()).getY().toByteArray();
        return encoded.length > 1 && encoded[0] == 0
                ? Arrays.copyOfRange(encoded, 1, encoded.length)
                : encoded;
    }

    public byte[] deriveAuthenticationKey(byte[] peerPublicKey) throws Exception {
        BigInteger peer = new BigInteger(1, peerPublicKey);
        KeyFactory factory = KeyFactory.getInstance("DH");
        KeyAgreement agreement = KeyAgreement.getInstance("DH");
        agreement.init(keyPair.getPrivate());
        agreement.doPhase(factory.generatePublic(
                new DHPublicKeySpec(peer, parameters.getP(), parameters.getG())), true);
        byte[] secret = agreement.generateSecret();
        try {
            return MessageDigest.getInstance("MD5").digest(secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public static byte[] encryptChallenge(byte[] authenticationKey, byte[] challenge) throws Exception {
        if (authenticationKey == null || authenticationKey.length != 16 || challenge == null || challenge.length != 16) {
            throw new IllegalArgumentException("Schlüssel und Challenge müssen 16 Byte lang sein");
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(authenticationKey, "AES"));
        return cipher.doFinal(challenge);
    }
}
