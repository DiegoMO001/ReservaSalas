package servidor.dao;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comum.ErroProtocolo;
import comum.JsonUtil;
import comum.Mensagens;
import comum.Validacao;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import servidor.modelo.SenhaHash;
import servidor.modelo.Sessao;
import servidor.modelo.Usuario;

public final class TestesUsuarios {
    private static int verificacoes;
    private static final Instant INSTANTE = Instant.parse("2030-01-10T15:00:00Z");
    private static final LocalDateTime AGORA = LocalDateTime.of(2030, 1, 10, 12, 0);
    private static final String TOKEN = "a".repeat(64);

    private TestesUsuarios() {
    }

    public static void main(String[] args) throws Exception {
        validarProtocolo();
        validarSeguranca();
        Path pasta = Path.of(args.length == 0 ? "build/test-data" : args[0]).toAbsolutePath();
        Files.createDirectories(pasta);
        Path arquivo = Files.createTempFile(pasta, "usuarios-", ".db");
        BancoDAO banco = new BancoDAO("jdbc:sqlite:" + arquivo);
        banco.inicializar();
        validarBanco(banco);
        validarPersistencia(banco);
        validarConcorrencia(banco);
        validarMigracao(banco);
        System.out.println("Usuarios e persistencia: " + verificacoes + " verificacoes aprovadas.");
    }

    private static void validarProtocolo() {
        for (String invalido : List.of("", "null", "[]", "true", "1", "{} {}", "{op:'login'}",
                "{\"op\":\"login\",}", "/* comentario */ {}", "{\"op\":\"login\"")) {
            erro(() -> JsonUtil.lerObjeto(invalido), "error", Mensagens.E400_requisicao);
        }
        erro(() -> Validacao.validar(JsonUtil.lerObjeto("{}")), "error", Mensagens.E400_requisicao);
        erro(() -> Validacao.validar(JsonUtil.lerObjeto("{\"op\":4}")), "error", Mensagens.E400_requisicao);
        erro(() -> Validacao.validar(JsonUtil.requisicao("LOGIN")), "error", Mensagens.E400_operacao);
        check(Validacao.campo("email", "joao.silva@email.com", false), "email valido");
        for (String email : List.of("joao@empresa.com.br", "maria@alunos.utfpr.edu.br", "ana@meu-provedor.com.br",
                "joao_silva@alunos.utfpr.edu.br", "ana-silva@nivel2.meu-provedor.edu.br")) {
            check(Validacao.campo("email", email, false), "email v2.1: " + email);
        }
        for (String email : List.of("joao@email.c", "joao@@email.com", "joao@dominio_com.br", "@email.com", "joao@email..com")) {
            check(!Validacao.campo("email", email, false), "email invalido: " + email);
        }
        check(!Validacao.campo("email", "Joao@email.com", false), "email maiusculo");
        check(!Validacao.campo("user", "joao1", false), "user numerico");
        check(!Validacao.campo("password", "a!", false), "senha com simbolo");
        check(Validacao.campo("password", "", true), "senha vazia em atualizacao");
        check(!Validacao.campo("password", "", false), "senha vazia em criacao");
        check(!Validacao.campo("user", "joao\n", false), "quebra de linha no campo");
        check(!Validacao.campo("created_at", "2030-02-30 10:00:00", false), "created_at invalido");
        check(Validacao.campo("created_at", "2032-02-29 10:00:00", false), "created_at valido");
        for (String op : List.of("logout", "read_user", "admin_list_users")) {
            valido(pedido(op));
            JsonObject semToken = pedido(op);
            semToken.remove("token");
            erroValidacao(semToken, Mensagens.E400_token);
            semToken.add("token", null);
            erroValidacao(semToken, Mensagens.E400_token);
            semToken.addProperty("token", "X".repeat(64));
            erroValidacao(semToken, Mensagens.E400_token);
        }
        JsonObject register = pedido("register", "user", "joao", "email", "joao@email.com", "password", "abc123");
        register.addProperty("role", "admin");
        valido(register);
        register.addProperty("password", 123);
        erroValidacao(register, Mensagens.E400_cadastro);
        valido(pedido("login", "email", "joao@email.com", "password", "abc"));
        JsonObject update = pedido("update_user", "user", "", "password", "");
        erroValidacao(update, Mensagens.E400_nada_para_atualizar);
        update.add("email", null);
        erroValidacao(update, Mensagens.E400_dados);
        update.remove("email");
        update.add("password", null);
        erroValidacao(update, Mensagens.E400_dados);
        erroValidacao(pedido("admin_update_user", "target_user", "maria", "user", "", "password", "", "role", ""), Mensagens.E400_nada_para_atualizar);
        valido(pedido("admin_read_user", "target_user", "maria"));
        valido(pedido("admin_delete_user", "target_user", "maria"));
        valido(pedido("delete_user", "password", "abc"));
        erroValidacao(pedido("delete_user", "password", "!"), Mensagens.E400_senha);
        JsonObject desconhecido = pedido("read_user");
        desconhecido.add("ignorado", new com.google.gson.JsonArray());
        valido(desconhecido);
        String resposta = JsonUtil.escrever(JsonUtil.resposta("read_user", "200", Mensagens.CONSULTA));
        check(resposta.startsWith("{\"op\":\"read_user_response\""), "op primeiro");
        check(!resposta.contains("\n") && !resposta.contains("\r"), "JSON compacto");
        check(JsonUtil.ehString(JsonUtil.lerObjeto(resposta).get("status")), "status string");
    }

    private static void validarSeguranca() {
        SenhaHash primeira = SenhaHash.criar("abc123");
        SenhaHash segunda = SenhaHash.criar("abc123");
        check(primeira.confere("abc123") && !primeira.confere("abc124"), "verificar hash");
        check(!primeira.hash().equals(segunda.hash()) && !primeira.salt().equals(segunda.salt()), "salt aleatorio");
        check(primeira.hash().matches("[a-f0-9]{64}") && primeira.salt().matches("[a-f0-9]{64}"), "hash e salt em hexadecimal");
        check(!primeira.toString().contains(primeira.hash()), "hash protegido em toString");
        String token = SenhaHash.novoToken();
        check(Validacao.campo("token", token, false), "token seguro no formato correto");
        check(!token.equals(SenhaHash.novoToken()), "tokens distintos");
    }

    private static void validarBanco(BancoDAO banco) throws Exception {
        try (Connection conexao = banco.abrir(); Statement comando = conexao.createStatement()) {
            try (ResultSet resultado = comando.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                List<String> tabelas = new ArrayList<>();
                while (resultado.next()) {
                    tabelas.add(resultado.getString(1));
                }
                check(tabelas.equals(List.of("sessions", "users")), "banco novo contem somente usuarios e sessoes");
            }
            for (String[] pragma : new String[][]{{"foreign_keys", "1"}, {"busy_timeout", "5000"}, {"journal_mode", "wal"}}) {
                try (ResultSet resultado = comando.executeQuery("PRAGMA " + pragma[0])) {
                    check(resultado.next() && resultado.getString(1).equals(pragma[1]), "PRAGMA " + pragma[0]);
                }
            }
        }
        UserDAO usuarios = new UserDAO(banco);
        Usuario admin = usuarios.buscarPorUser("admin").orElseThrow();
        check(admin.admin() && admin.senha().confere("admin123"), "admin inicial");
        check(admin.email().equals("admin@campus.com"), "email admin");
        banco.inicializar();
        check(usuarios.listar().size() == 1, "inicializacao idempotente");
        usuarios.atualizar(admin.id(), "administrador", admin.senha(), "admin");
        banco.inicializar();
        check(usuarios.listar().size() == 1 && usuarios.buscarPorUser("admin").isEmpty(), "admin renomeado preservado");
    }

    private static void validarPersistencia(BancoDAO banco) throws Exception {
        UserDAO usuarios = new UserDAO(banco);
        SessionDAO sessoes = new SessionDAO(banco);
        long joao = usuarios.cadastrar("joao", "joao@email.com", SenhaHash.criar("abc"), AGORA);
        long maria = usuarios.cadastrar("maria", "maria@email.com", SenhaHash.criar("abc"), AGORA);
        Usuario usuario = usuarios.buscarPorId(joao).orElseThrow();
        check(!usuario.admin(), "cadastro nasce user");
        somenteStrings(usuario.publico(true));
        check(!JsonUtil.escrever(usuario.publico(true)).contains("password"), "sem senha em resposta");
        check(!usuario.publico(true).has("salt"), "sem salt em resposta");
        falhaUnica(() -> usuarios.cadastrar("joao", "outro@email.com", SenhaHash.criar("abc"), AGORA));
        falhaUnica(() -> usuarios.cadastrar("outro", "joao@email.com", SenhaHash.criar("abc"), AGORA));
        Sessao sessao = sessoes.criarSeLivre(joao, INSTANTE).orElseThrow();
        check(sessoes.criarSeLivre(joao, INSTANTE.plusSeconds(1)).isEmpty(), "sessao unica");
        check(sessoes.autenticarERenovar(sessao.token(), INSTANTE.plusSeconds(1799)).isPresent(), "renovacao antes de expirar");
        check(sessoes.autenticarERenovar(sessao.token(), INSTANTE.plusSeconds(1800)).isPresent(), "renovacao estende validade");
        usuarios.atualizar(joao, "joaonovo", usuario.senha(), "admin");
        Usuario atual = sessoes.autenticarERenovar(sessao.token(), INSTANTE.plusSeconds(1801)).orElseThrow();
        check(atual.admin() && atual.user().equals("joaonovo"), "role e user atuais sem perder sessao");
        check(usuarios.contarAdmins() == 2, "contagem admins");
        check(sessoes.autenticarERenovar(sessao.token(), INSTANTE.plusSeconds(3601)).isEmpty(), "expira exatamente em 30 minutos");
        check(sessoes.buscar(sessao.token()).orElseThrow().invalidated(), "expiracao invalida token");
        Sessao nova = sessoes.criarSeLivre(joao, INSTANTE.plusSeconds(3602)).orElseThrow();
        check(!nova.token().equals(sessao.token()), "token expirado nao reutilizado");
        check(sessoes.invalidar(nova.token()) && !sessoes.invalidar(nova.token()), "logout nao repetivel");
        check(sessoes.autenticarERenovar(nova.token(), INSTANTE.plusSeconds(3603)).isEmpty(), "logout persiste");
        Sessao excluir = sessoes.criarSeLivre(maria, INSTANTE).orElseThrow();
        usuarios.remover(maria);

        check(sessoes.buscar(excluir.token()).isEmpty(), "sessao apagada apos exclusao");
        check(sessoes.autenticarERenovar(excluir.token(), INSTANTE).isEmpty(), "excluido nao autentica");
        long recriado = usuarios.cadastrar("maria", "maria@email.com", SenhaHash.criar("nova"), AGORA);
        check(recriado != maria && usuarios.buscarPorId(recriado).isPresent(), "nome e email livres com nova identidade");
        Sessao expiradaSemUso = sessoes.criarSeLivre(joao, INSTANTE.plusSeconds(5000)).orElseThrow();
        Sessao aposExpirar = sessoes.criarSeLivre(joao, INSTANTE.plusSeconds(6800)).orElseThrow();
        check(sessoes.buscar(expiradaSemUso.token()).orElseThrow().invalidated(), "login expira sessao anterior sem consulta");
        check(sessoes.invalidarDoUsuario(joao) == 1 && sessoes.buscar(aposExpirar.token()).orElseThrow().invalidated(), "invalidar sessoes por usuario");
        check(!aposExpirar.toString().contains(aposExpirar.token()), "log oculta token completo");
    }

    private static void validarMigracao(BancoDAO banco) throws Exception {
        UserDAO usuarios = new UserDAO(banco);
        SessionDAO sessoes = new SessionDAO(banco);
        long id = usuarios.cadastrar("legado", "legado@email.com", SenhaHash.criar("abc"), AGORA);
        Sessao sessao = sessoes.criarSeLivre(id, INSTANTE).orElseThrow();
        try (Connection conexao = banco.abrir(); Statement comando = conexao.createStatement()) {
            comando.executeUpdate("DROP TRIGGER invalidate_user_sessions");
            comando.executeUpdate("CREATE TRIGGER invalidate_user_sessions BEFORE DELETE ON users BEGIN UPDATE sessions SET invalidated = 1 WHERE user_id = OLD.id; END");
            comando.executeUpdate("INSERT INTO sessions VALUES ('" + "f".repeat(64) + "', NULL, 0, 1)");
        }
        banco.inicializar();
        check(sessoes.buscar("f".repeat(64)).isEmpty(), "migracao limpa sessoes orfas antigas");
        check(sessoes.autenticarERenovar(sessao.token(), INSTANTE).isPresent(), "migracao preserva conta e sessao ativa");
        usuarios.remover(id);
        check(sessoes.buscar(sessao.token()).isEmpty(), "trigger antigo migrado apaga sessao");
    }

    private static void validarConcorrencia(BancoDAO banco) throws Exception {
        UserDAO usuarios = new UserDAO(banco);
        SessionDAO sessoes = new SessionDAO(banco);
        long id = usuarios.cadastrar("concorrente", "concorrente@email.com", SenhaHash.criar("abc"), AGORA);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch inicio = new CountDownLatch(1);
            List<Future<Boolean>> resultados = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                resultados.add(pool.submit(() -> {
                    inicio.await();
                    return sessoes.criarSeLivre(id, INSTANTE).isPresent();
                }));
            }
            inicio.countDown();
            int criadas = 0;
            for (Future<Boolean> resultado : resultados) {
                criadas += resultado.get(15, TimeUnit.SECONDS) ? 1 : 0;
            }
            check(criadas == 1, "8 logins simultaneos criam apenas 1 sessao");
            CountDownLatch cadastro = new CountDownLatch(1);
            resultados.clear();
            for (int i = 0; i < 8; i++) {
                resultados.add(pool.submit(() -> {
                    cadastro.await();
                    try {
                        usuarios.cadastrar("duplicado", "duplicado@email.com", SenhaHash.criar("abc"), AGORA);
                        return true;
                    } catch (SQLException ex) {
                        if (!BancoDAO.violacaoUnicidade(ex)) {
                            throw ex;
                        }
                        return false;
                    }
                }));
            }
            cadastro.countDown();
            int cadastrados = 0;
            for (Future<Boolean> resultado : resultados) {
                cadastrados += resultado.get(15, TimeUnit.SECONDS) ? 1 : 0;
            }
            check(cadastrados == 1, "8 cadastros simultaneos respeitam unicidade");
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(10, TimeUnit.SECONDS), "threads encerradas");
        }
    }

    private static JsonObject pedido(String op, String... campos) {
        JsonObject objeto = JsonUtil.requisicao(op);
        if (!op.equals("register") && !op.equals("login")) {
            objeto.addProperty("token", TOKEN);
        }
        for (int i = 0; i < campos.length; i += 2) {
            objeto.addProperty(campos[i], campos[i + 1]);
        }
        return objeto;
    }

    private static void valido(JsonObject pedido) {
        check(Validacao.validar(pedido).equals(JsonUtil.texto(pedido, "op")), "pedido valido");
    }

    private static void erroValidacao(JsonObject pedido, String mensagem) {
        erro(() -> Validacao.validar(pedido), JsonUtil.texto(pedido, "op") + "_response", mensagem);
    }

    private static void erro(Runnable acao, String op, String mensagem) {
        try {
            acao.run();
            throw new AssertionError("Erro esperado: " + mensagem);
        } catch (ErroProtocolo ex) {
            check(JsonUtil.texto(ex.resposta(), "op").equals(op)
                    && JsonUtil.texto(ex.resposta(), "status").equals("400")
                    && ex.getMessage().equals(mensagem), "precedencia e mensagem de erro");
        }
    }

    private static void somenteStrings(JsonElement elemento) {
        if (elemento.isJsonObject()) {
            elemento.getAsJsonObject().entrySet().forEach(e -> somenteStrings(e.getValue()));
        } else if (elemento.isJsonArray()) {
            elemento.getAsJsonArray().forEach(TestesUsuarios::somenteStrings);
        } else {
            check(JsonUtil.ehString(elemento), "valor do protocolo deve ser string");
        }
    }

    private static void falhaUnica(AcaoSQL acao) throws Exception {
        try {
            acao.executar();
            throw new AssertionError("Violacao de unicidade esperada");
        } catch (SQLException ex) {
            check(BancoDAO.violacaoUnicidade(ex), "constraint UNIQUE");
        }
    }

    private static void check(boolean condicao, String mensagem) {
        if (!condicao) {
            throw new AssertionError(mensagem);
        }
        verificacoes++;
    }

    @FunctionalInterface
    private interface AcaoSQL {
        void executar() throws SQLException;
    }
}
