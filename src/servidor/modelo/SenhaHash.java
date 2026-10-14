package servidor.modelo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public record SenhaHash(String hash, String salt) {
    private static final SecureRandom ALEATORIO = new SecureRandom();

    public static SenhaHash criar(String senha) {
        byte[] sal = new byte[32];
        ALEATORIO.nextBytes(sal);
        return new SenhaHash(calcular(senha, sal), HexFormat.of().formatHex(sal));
    }

    public boolean confere(String senha) {
        byte[] esperado = HexFormat.of().parseHex(hash);
        byte[] informado = HexFormat.of().parseHex(calcular(senha, HexFormat.of().parseHex(salt)));
        return MessageDigest.isEqual(esperado, informado);
    }

    public static String novoToken() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String calcular(String senha, byte[] sal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sal);
            return HexFormat.of().formatHex(digest.digest(senha.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponivel", ex);
        }
    }

    @Override
    public String toString() {
        return "SenhaHash[protegida]";
    }
}
