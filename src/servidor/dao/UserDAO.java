package servidor.dao;

import comum.Datas;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import servidor.modelo.SenhaHash;
import servidor.modelo.Usuario;

public final class UserDAO {
    private final BancoDAO banco;

    public UserDAO(BancoDAO banco) {
        this.banco = banco;
    }

    public long cadastrar(String user, String email, SenhaHash senha, LocalDateTime agora) throws SQLException {
        try (Connection conexao = banco.abrir()) {
            return inserir(conexao, user, email, senha, "user", agora);
        }
    }

    boolean criarAdminInicial() throws SQLException {
        try (Connection conexao = banco.abrir()) {
            conexao.setAutoCommit(false);
            try {
                boolean existeAdmin;
                try (PreparedStatement comando = conexao.prepareStatement("SELECT 1 FROM users WHERE role = 'admin' LIMIT 1");
                        ResultSet resultado = comando.executeQuery()) {
                    existeAdmin = resultado.next();
                }
                if (existeAdmin) {
                    conexao.setAutoCommit(true);
                    return false;
                }
                inserir(conexao, "admin", "admin@campus.com", SenhaHash.criar("admin123"), "admin", Datas.agora());
                conexao.setAutoCommit(true);
                return true;
            } catch (SQLException | RuntimeException ex) {
                BancoDAO.rollback(conexao, ex);
                throw ex;
            }
        }
    }

    private static long inserir(Connection conexao, String user, String email, SenhaHash senha,
            String role, LocalDateTime agora) throws SQLException {
        try (PreparedStatement comando = conexao.prepareStatement("""
                INSERT INTO users(user, email, password_hash, salt, role, created_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """)) {
            comando.setString(1, user);
            comando.setString(2, email);
            comando.setString(3, senha.hash());
            comando.setString(4, senha.salt());
            comando.setString(5, role);
            comando.setString(6, Datas.formatar(agora));
            try (ResultSet resultado = comando.executeQuery()) {
                if (!resultado.next()) {
                    throw new SQLException("Usuario nao inserido");
                }
                return resultado.getLong("id");
            }
        }
    }

    public Optional<Usuario> buscarPorId(long id) throws SQLException {
        return buscar("SELECT * FROM users WHERE id = ?", id);
    }

    public Optional<Usuario> buscarPorUser(String user) throws SQLException {
        return buscar("SELECT * FROM users WHERE user = ?", user);
    }

    public Optional<Usuario> buscarPorEmail(String email) throws SQLException {
        return buscar("SELECT * FROM users WHERE email = ?", email);
    }

    private Optional<Usuario> buscar(String sql, Object valor) throws SQLException {
        try (Connection conexao = banco.abrir(); PreparedStatement comando = conexao.prepareStatement(sql)) {
            comando.setObject(1, valor);
            try (ResultSet resultado = comando.executeQuery()) {
                return resultado.next() ? Optional.of(mapear(resultado)) : Optional.empty();
            }
        }
    }

    public List<Usuario> listar() throws SQLException {
        try (Connection conexao = banco.abrir();
                PreparedStatement comando = conexao.prepareStatement("SELECT * FROM users ORDER BY user");
                ResultSet resultado = comando.executeQuery()) {
            List<Usuario> usuarios = new ArrayList<>();
            while (resultado.next()) {
                usuarios.add(mapear(resultado));
            }
            return usuarios;
        }
    }

    public int contarAdmins() throws SQLException {
        try (Connection conexao = banco.abrir();
                PreparedStatement comando = conexao.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'admin'");
                ResultSet resultado = comando.executeQuery()) {
            resultado.next();
            return resultado.getInt(1);
        }
    }

    public boolean atualizar(long id, String user, SenhaHash senha, String role) throws SQLException {
        try (Connection conexao = banco.abrir(); PreparedStatement comando = conexao.prepareStatement("""
                UPDATE users SET user = ?, password_hash = ?, salt = ?, role = ? WHERE id = ?
                """)) {
            comando.setString(1, user);
            comando.setString(2, senha.hash());
            comando.setString(3, senha.salt());
            comando.setString(4, role);
            comando.setLong(5, id);
            return comando.executeUpdate() == 1;
        }
    }

    public boolean remover(long id) throws SQLException {
        try (Connection conexao = banco.abrir();
                PreparedStatement comando = conexao.prepareStatement("DELETE FROM users WHERE id = ?")) {
            comando.setLong(1, id);
            return comando.executeUpdate() == 1;
        }
    }

    static Usuario mapear(ResultSet resultado) throws SQLException {
        return new Usuario(resultado.getLong("id"), resultado.getString("user"), resultado.getString("email"),
                resultado.getString("role"), new SenhaHash(resultado.getString("password_hash"), resultado.getString("salt")),
                LocalDateTime.parse(resultado.getString("created_at"), Datas.DATA_HORA));
    }
}
