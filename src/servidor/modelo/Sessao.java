package servidor.modelo;

import java.time.Duration;
import java.time.Instant;

public record Sessao(String token, Long userId, Instant lastUsed, boolean invalidated) {
    public static final Duration VALIDADE = Duration.ofMinutes(30);

    public boolean ativa(Instant agora) {
        return !invalidated && userId != null && lastUsed.plus(VALIDADE).isAfter(agora);
    }

    @Override
    public String toString() {
        return "Sessao[token=" + token.substring(0, Math.min(8, token.length()))
                + ", userId=" + userId + ", invalidated=" + invalidated + "]";
    }
}
