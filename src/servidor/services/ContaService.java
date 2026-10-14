package servidor.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import comum.ErroProtocolo;
import comum.JsonUtil;
import comum.Mensagens;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import servidor.Locks;
import servidor.dao.BancoDAO;
import servidor.dao.SessionDAO;
import servidor.dao.UserDAO;
import servidor.modelo.SenhaHash;
import servidor.modelo.Sessao;
import servidor.modelo.Usuario;

public final class ContaService {
    private final UserDAO usuarios;
    private final SessionDAO sessoes;
    private final Locks locks;
    private final Clock relogio;

    public ContaService(BancoDAO banco, Locks locks, Clock relogio) {
        usuarios = new UserDAO(banco);
        sessoes = new SessionDAO(banco);
        this.locks = locks;
        this.relogio = relogio;
    }

    public Usuario autenticar(String op, String token) throws SQLException {
        return sessoes.autenticarERenovar(token, relogio.instant())
                .orElseThrow(() -> new ErroProtocolo(op, "401", Mensagens.E401_token));
    }

    public JsonObject executar(String op, JsonObject pedido, Usuario autor) throws SQLException {
        return switch (op) {
            case "register" -> cadastrar(pedido);
            case "login" -> login(pedido);
            case "logout" -> {
                if (!sessoes.invalidar(JsonUtil.texto(pedido, "token"))) {
                    throw new ErroProtocolo(op, "401", Mensagens.E401_token);
                }
                yield JsonUtil.resposta(op, "200", Mensagens.LOGOUT);
            }
            case "read_user", "admin_read_user" -> {
                Usuario alvo = alvo(op, pedido, autor);
                JsonObject resposta = JsonUtil.resposta(op, "200", Mensagens.CONSULTA);
                alvo.publico(true).entrySet().forEach(e -> resposta.add(e.getKey(), e.getValue()));
                yield resposta;
            }
            case "admin_list_users" -> {
                JsonArray lista = new JsonArray();
                for (Usuario usuario : usuarios.listar()) {
                    lista.add(usuario.publico(false));
                }
                JsonObject resposta = JsonUtil.resposta(op, "200", Mensagens.CONSULTA);
                resposta.addProperty("count", Integer.toString(lista.size()));
                resposta.add("users", lista);
                yield resposta;
            }
            case "update_user", "admin_update_user" -> atualizar(op, pedido, alvo(op, pedido, autor));
            case "delete_user", "admin_delete_user" -> remover(op, pedido, alvo(op, pedido, autor));
            default -> throw new IllegalArgumentException(Mensagens.E400_operacao);
        };
    }

    private JsonObject cadastrar(JsonObject pedido) throws SQLException {
        locks.cadastro.lock();
        try {
            usuarios.cadastrar(JsonUtil.texto(pedido, "user"), JsonUtil.texto(pedido, "email"),
                    SenhaHash.criar(JsonUtil.texto(pedido, "password")), LocalDateTime.now(relogio));
            return JsonUtil.resposta("register", "201", Mensagens.CADASTRO);
        } catch (SQLException ex) {
            if (BancoDAO.violacaoUnicidade(ex)) {
                throw new ErroProtocolo("register", "409", Mensagens.E409_cadastrado);
            }
            throw ex;
        } finally {
            locks.cadastro.unlock();
        }
    }

    private JsonObject login(JsonObject pedido) throws SQLException {
        locks.login.lock();
        try {
            Usuario usuario = usuarios.buscarPorEmail(JsonUtil.texto(pedido, "email"))
                    .orElseThrow(() -> new ErroProtocolo("login", "401", Mensagens.E401_credenciais));
            if (!usuario.senha().confere(JsonUtil.texto(pedido, "password"))) {
                throw new ErroProtocolo("login", "401", Mensagens.E401_credenciais);
            }
            Sessao sessao = sessoes.criarSeLivre(usuario.id(), relogio.instant())
                    .orElseThrow(() -> new ErroProtocolo("login", "409", Mensagens.E409_sessao_ativa));
            JsonObject resposta = JsonUtil.resposta("login", "200", Mensagens.LOGIN);
            resposta.addProperty("token", sessao.token());
            resposta.addProperty("role", usuario.role());
            return resposta;
        } finally {
            locks.login.unlock();
        }
    }

    public String userDoLogin(JsonObject pedido) throws SQLException {
        return usuarios.buscarPorEmail(JsonUtil.texto(pedido, "email")).map(Usuario::user).orElse(null);
    }

    private Usuario alvo(String op, JsonObject pedido, Usuario autor) throws SQLException {
        if (!op.startsWith("admin_")) {
            return autor;
        }
        return usuarios.buscarPorUser(JsonUtil.texto(pedido, "target_user"))
                .orElseThrow(() -> new ErroProtocolo(op, "404", Mensagens.E404_usuario));
    }

    private JsonObject atualizar(String op, JsonObject pedido, Usuario alvo) throws SQLException {
        String user = valor(pedido, "user", alvo.user());
        String role = op.startsWith("admin_") ? valor(pedido, "role", alvo.role()) : alvo.role();
        if (alvo.admin() && !role.equals("admin")) {
            protegerUltimoAdmin(op, alvo);
        }
        String password = JsonUtil.texto(pedido, "password");
        SenhaHash senha = password.isEmpty() ? alvo.senha() : SenhaHash.criar(password);
        try {
            usuarios.atualizar(alvo.id(), user, senha, role);
        } catch (SQLException ex) {
            if (BancoDAO.violacaoUnicidade(ex)) {
                throw new ErroProtocolo(op, "409", Mensagens.E409_usuario_em_uso);
            }
            throw ex;
        }
        return JsonUtil.resposta(op, "200", Mensagens.USUARIO_ATUALIZADO);
    }

    private JsonObject remover(String op, JsonObject pedido, Usuario alvo) throws SQLException {
        if (op.equals("delete_user") && !alvo.senha().confere(JsonUtil.texto(pedido, "password"))) {
            throw new ErroProtocolo(op, "403", Mensagens.E403_senha_incorreta);
        }
        protegerUltimoAdmin(op, alvo);
        usuarios.remover(alvo.id());
        return JsonUtil.resposta(op, "200", Mensagens.USUARIO_REMOVIDO);
    }

    private void protegerUltimoAdmin(String op, Usuario alvo) throws SQLException {
        if (alvo.admin() && usuarios.contarAdmins() == 1) {
            throw new ErroProtocolo(op, "403", Mensagens.E403_ultimo_admin);
        }
    }

    static String valor(JsonObject pedido, String campo, String anterior) {
        String valor = JsonUtil.texto(pedido, campo);
        return valor.isEmpty() ? anterior : valor;
    }
}
