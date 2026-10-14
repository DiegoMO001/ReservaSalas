package servidor.services;

import com.google.gson.JsonObject;
import comum.Datas;
import comum.ErroProtocolo;
import comum.JsonUtil;
import comum.Mensagens;
import comum.Validacao;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import servidor.Locks;
import servidor.dao.BancoDAO;
import servidor.modelo.Usuario;

public final class Aplicacao {
    public record Resultado(JsonObject resposta, String autor) {
    }

    private static final Set<String> ALTERAM_CONTAS = Set.of("register", "update_user", "delete_user",
            "admin_update_user", "admin_delete_user");
    private final Locks locks = new Locks();
    private final ContaService contas;

    public Aplicacao(BancoDAO banco) {
        this(banco, Datas.RELOGIO);
    }

    public Aplicacao(BancoDAO banco, Clock relogio) {
        contas = new ContaService(banco, locks, relogio.withZone(Datas.FUSO));
    }

    public Resultado processar(JsonObject pedido) {
        String op = null;
        String user = null;
        try {
            op = Validacao.validar(pedido);
            Lock lock = ALTERAM_CONTAS.contains(op) ? locks.contas.writeLock() : locks.contas.readLock();
            lock.lock();
            try {
                Usuario autor = null;
                if (!op.equals("register") && !op.equals("login")) {
                    autor = contas.autenticar(op, JsonUtil.texto(pedido, "token"));
                    user = autor.user();
                    if (op.startsWith("admin_") && !autor.admin()) {
                        throw new ErroProtocolo(op, "403", Mensagens.E403_permissao);
                    }
                }
                JsonObject resposta = contas.executar(op, pedido, autor);
                if (op.equals("login") && JsonUtil.texto(resposta, "status").equals("200")) {
                    user = contas.userDoLogin(pedido);
                } else if (op.equals("register")) {
                    user = JsonUtil.texto(pedido, "user");
                }
                return new Resultado(resposta, user);
            } finally {
                lock.unlock();
            }
        } catch (ErroProtocolo ex) {
            return new Resultado(ex.resposta(), user);
        } catch (SQLException | RuntimeException ex) {
            return new Resultado(JsonUtil.resposta(op, "500", Mensagens.E500), user);
        }
    }
}
