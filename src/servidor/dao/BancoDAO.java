package servidor.dao;

import comum.Mensagens;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public final class BancoDAO {
    private final String url;

    public BancoDAO() {
        this("jdbc:sqlite:sistema.db");
    }

    public BancoDAO(String url) {
        if (!url.startsWith("jdbc:sqlite:")) {
            throw new IllegalArgumentException("URL SQLite invalida");
        }
        this.url = url;
    }

    Connection abrir() throws SQLException {
        Properties propriedades = new Properties();
        propriedades.setProperty("transaction_mode", "IMMEDIATE");
        Connection conexao = DriverManager.getConnection(url, propriedades);
        try (Statement comando = conexao.createStatement()) {
            comando.execute("PRAGMA busy_timeout=5000");
            comando.execute("PRAGMA foreign_keys=ON");
            comando.execute("PRAGMA journal_mode=WAL");
            return conexao;
        } catch (SQLException ex) {
            try {
                conexao.close();
            } catch (SQLException fechamento) {
                ex.addSuppressed(fechamento);
            }
            throw ex;
        }
    }

    public void inicializar() throws SQLException {
        try (Connection conexao = abrir()) {
            conexao.setAutoCommit(false);
            try (Statement comando = conexao.createStatement()) {
                comando.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS users (
                            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(id <= 9999999999),
                            user TEXT NOT NULL COLLATE NOCASE UNIQUE,
                            email TEXT NOT NULL COLLATE NOCASE UNIQUE,
                            password_hash TEXT NOT NULL CHECK(length(password_hash) = 64),
                            salt TEXT NOT NULL CHECK(length(salt) = 64),
                            role TEXT NOT NULL CHECK(role IN ('user', 'admin')),
                            created_at TEXT NOT NULL
                        )
                        """);
                comando.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            token TEXT PRIMARY KEY NOT NULL CHECK(length(token) = 64),
                            user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
                            last_used INTEGER NOT NULL,
                            invalidated INTEGER NOT NULL DEFAULT 0 CHECK(invalidated IN (0, 1)),
                            CHECK(user_id IS NOT NULL OR invalidated = 1)
                        )
                        """);
                comando.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_active_user ON sessions(user_id) WHERE invalidated = 0");
                comando.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)");
                // Atualiza tambem bancos da versao anterior, sem recriar as contas.
                comando.executeUpdate("DROP TRIGGER IF EXISTS invalidate_user_sessions");
                comando.executeUpdate("""
                        CREATE TRIGGER IF NOT EXISTS invalidate_user_sessions
                        BEFORE DELETE ON users
                        BEGIN
                            DELETE FROM sessions WHERE user_id = OLD.id;
                        END
                        """);
                comando.executeUpdate("DELETE FROM sessions WHERE user_id IS NULL");
                conexao.setAutoCommit(true);
            } catch (SQLException ex) {
                rollback(conexao, ex);
                throw ex;
            }
        }
        if (new UserDAO(this).criarAdminInicial()) {
            System.out.println(Mensagens.ADMIN_INICIAL);
        }
    }

    static void rollback(Connection conexao, Throwable causa) {
        try {
            conexao.rollback();
        } catch (SQLException ex) {
            causa.addSuppressed(ex);
        }
    }

    public static boolean violacaoUnicidade(SQLException ex) {
        for (SQLException atual = ex; atual != null; atual = atual.getNextException()) {
            int codigo = atual.getErrorCode();
            String texto = atual.getMessage();
            if (codigo == 1555 || codigo == 2067 || (codigo == 19 && texto != null
                    && (texto.contains("SQLITE_CONSTRAINT_UNIQUE") || texto.contains("SQLITE_CONSTRAINT_PRIMARYKEY")))) {
                return true;
            }
        }
        return false;
    }
}
