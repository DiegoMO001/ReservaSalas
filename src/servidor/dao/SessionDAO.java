package servidor.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import servidor.modelo.SenhaHash;
import servidor.modelo.Sessao;
import servidor.modelo.Usuario;

public final class SessionDAO {
    private final BancoDAO banco;

    public SessionDAO(BancoDAO banco) {
        this.banco = banco;
    }

    public Optional<Sessao> criarSeLivre(long userId, Instant agora) throws SQLException {
        try (Connection conexao = banco.abrir()) {
            conexao.setAutoCommit(false);
            try {
                try (PreparedStatement comando = conexao.prepareStatement("""
                        UPDATE sessions SET invalidated = 1
                        WHERE user_id = ? AND invalidated = 0 AND last_used <= ?
                        """)) {
                    comando.setLong(1, userId);
                    comando.setLong(2, agora.minus(Sessao.VALIDADE).toEpochMilli());
                    comando.executeUpdate();
                }
                boolean existeSessao;
                try (PreparedStatement comando = conexao.prepareStatement("SELECT 1 FROM sessions WHERE user_id = ? AND invalidated = 0")) {
                    comando.setLong(1, userId);
                    try (ResultSet resultado = comando.executeQuery()) {
                        existeSessao = resultado.next();
                    }
                }
                if (existeSessao) {
                    conexao.setAutoCommit(true);
                    return Optional.empty();
                }
                String token;
                while (true) {
                    token = SenhaHash.novoToken();
                    try (PreparedStatement comando = conexao.prepareStatement("""
                            INSERT INTO sessions(token, user_id, last_used, invalidated) VALUES (?, ?, ?, 0)
                            ON CONFLICT(token) DO NOTHING
                            """)) {
                        comando.setString(1, token);
                        comando.setLong(2, userId);
                        comando.setLong(3, agora.toEpochMilli());
                        if (comando.executeUpdate() == 1) {
                            break;
                        }
                    }
                }
                conexao.setAutoCommit(true);
                return Optional.of(new Sessao(token, userId, Instant.ofEpochMilli(agora.toEpochMilli()), false));
            } catch (SQLException | RuntimeException ex) {
                BancoDAO.rollback(conexao, ex);
                throw ex;
            }
        }
    }

    public Optional<Usuario> autenticarERenovar(String token, Instant agora) throws SQLException {
        try (Connection conexao = banco.abrir()) {
            conexao.setAutoCommit(false);
            try {
                try (PreparedStatement comando = conexao.prepareStatement("""
                        UPDATE sessions SET invalidated = 1
                        WHERE token = ? AND invalidated = 0 AND last_used <= ?
                        """)) {
                    comando.setString(1, token);
                    comando.setLong(2, agora.minus(Sessao.VALIDADE).toEpochMilli());
                    comando.executeUpdate();
                }
                int renovadas;
                try (PreparedStatement comando = conexao.prepareStatement("""
                        UPDATE sessions SET last_used = MAX(last_used, ?)
                        WHERE token = ? AND invalidated = 0 AND user_id IS NOT NULL
                        """)) {
                    comando.setLong(1, agora.toEpochMilli());
                    comando.setString(2, token);
                    renovadas = comando.executeUpdate();
                }
                Optional<Usuario> autor = Optional.empty();
                if (renovadas == 1) {
                    try (PreparedStatement comando = conexao.prepareStatement("""
                            SELECT u.* FROM users u JOIN sessions s ON s.user_id = u.id WHERE s.token = ?
                            """)) {
                        comando.setString(1, token);
                        try (ResultSet resultado = comando.executeQuery()) {
                            if (resultado.next()) {
                                autor = Optional.of(UserDAO.mapear(resultado));
                            }
                        }
                    }
                }
                conexao.setAutoCommit(true);
                return autor;
            } catch (SQLException | RuntimeException ex) {
                BancoDAO.rollback(conexao, ex);
                throw ex;
            }
        }
    }

    public boolean invalidar(String token) throws SQLException {
        try (Connection conexao = banco.abrir();
                PreparedStatement comando = conexao.prepareStatement("UPDATE sessions SET invalidated = 1 WHERE token = ? AND invalidated = 0")) {
            comando.setString(1, token);
            return comando.executeUpdate() == 1;
        }
    }

    public int invalidarDoUsuario(long userId) throws SQLException {
        try (Connection conexao = banco.abrir();
                PreparedStatement comando = conexao.prepareStatement("UPDATE sessions SET invalidated = 1 WHERE user_id = ? AND invalidated = 0")) {
            comando.setLong(1, userId);
            return comando.executeUpdate();
        }
    }

    public Optional<Sessao> buscar(String token) throws SQLException {
        try (Connection conexao = banco.abrir();
                PreparedStatement comando = conexao.prepareStatement("SELECT * FROM sessions WHERE token = ?")) {
            comando.setString(1, token);
            try (ResultSet resultado = comando.executeQuery()) {
                if (!resultado.next()) {
                    return Optional.empty();
                }
                long id = resultado.getLong("user_id");
                Long userId = resultado.wasNull() ? null : id;
                return Optional.of(new Sessao(resultado.getString("token"), userId,
                        Instant.ofEpochMilli(resultado.getLong("last_used")), resultado.getInt("invalidated") != 0));
            }
        }
    }
}
