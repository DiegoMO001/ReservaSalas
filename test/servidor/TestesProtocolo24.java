package servidor;

import cliente.ClienteCLI;
import cliente.Comunicacao;
import com.google.gson.JsonObject;
import comum.JsonUtil;
import comum.Mensagens;
import comum.RegistroMensagens;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import servidor.dao.BancoDAO;
import servidor.dao.SessionDAO;
import servidor.services.Aplicacao;

public final class TestesProtocolo24 {
    private static int verificacoes;

    public static void main(String[] args) throws Exception {
        Path raiz = Path.of(args.length == 0 ? "build/test-data" : args[0]);
        Files.createDirectories(raiz);
        Path pasta = Files.createTempDirectory(raiz, "protocolo24-");
        BancoDAO banco = new BancoDAO("jdbc:sqlite:" + pasta.resolve("usuarios.db").toAbsolutePath());
        banco.inicializar();
        Aplicacao aplicacao = new Aplicacao(banco);
        logsEExclusao(aplicacao, banco, pasta);
        logoutAposInatividade(aplicacao);
        clienteSoSaiComResposta();
        System.out.println("Protocolo 2.4, logout e logs: " + verificacoes + " verificacoes aprovadas.");
    }

    private static void logsEExclusao(Aplicacao aplicacao, BancoDAO banco, Path pasta) throws Exception {
        ByteArrayOutputStream telaCliente = new ByteArrayOutputStream();
        ByteArrayOutputStream telaServidor = new ByteArrayOutputStream();
        Path logCliente = pasta.resolve("cliente-log.txt");
        Path logServidor = pasta.resolve("servidor-log.txt");
        String token;
        String tokenAdmin;
        try (PrintStream outC = new PrintStream(telaCliente, true, StandardCharsets.UTF_8);
                PrintStream outS = new PrintStream(telaServidor, true, StandardCharsets.UTF_8);
                ServidorTCP servidor = new ServidorTCP(0, aplicacao, 300_000,
                        new RegistroMensagens(logServidor, outS, "SERVIDOR"))) {
            servidor.iniciar();
            try (Comunicacao c = new Comunicacao("127.0.0.1", servidor.porta(), new RegistroMensagens(logCliente, outC, "CLIENTE"))) {
                c.conectar();
                status(c, req("register", "user", "institucional", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "201");
                token = texto(status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "200"), "token");
                status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "!"), "400");
                status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "errada"), "401");
                status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "409");
                JsonObject vazio = status(c, req("update_user", "token", token, "user", "", "password", ""), "400");
                check(texto(vazio, "message").equals(Mensagens.E400_nada_para_atualizar), "mensagem exata de atualizacao vazia");
                JsonObject senha = status(c, req("delete_user", "token", token, "password", "errada"), "403");
                check(texto(senha, "message").equals("Senha incorreta"), "senha incorreta retorna 403");
                status(c, req("read_user", "token", token), "200");
                status(c, req("delete_user", "token", token, "password", "Segredo987"), "200");
                check(new SessionDAO(banco).buscar(token).isEmpty(), "delete_user apaga sessao do banco");
                status(c, req("read_user", "token", token), "401");
                status(c, req("register", "user", "institucional", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "201");
                String novo = texto(status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "200"), "token");
                check(!novo.equals(token), "novo cadastro nao herda token antigo");
                tokenAdmin = texto(status(c, req("login", "email", "admin@campus.com", "password", "admin123"), "200"), "token");
                JsonObject vazioAdmin = status(c, req("admin_update_user", "token", tokenAdmin, "target_user", "institucional", "user", "", "password", "", "role", ""), "400");
                check(texto(vazioAdmin, "message").equals(Mensagens.E400_nada_para_atualizar), "admin vazio retorna mensagem exata");
                status(c, req("admin_delete_user", "token", tokenAdmin, "target_user", "institucional"), "200");
                check(new SessionDAO(banco).buscar(novo).isEmpty(), "admin_delete_user apaga sessao do banco");
                status(c, req("register", "user", "institucional", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "201");
                status(c, req("logout", "token", tokenAdmin), "200");
            }
        }
        String arquivoC = Files.readString(logCliente);
        String arquivoS = Files.readString(logServidor);
        check(arquivoC.equals(telaCliente.toString(StandardCharsets.UTF_8)), "arquivo e console cliente identicos");
        check(arquivoS.equals(telaServidor.toString(StandardCharsets.UTF_8)), "arquivo e console servidor identicos");
        check(arquivoC.contains("ENVIADO C -> S") && arquivoC.contains("RECEBIDO S -> C"), "ambas as direcoes no cliente");
        check(arquivoS.contains("RECEBIDO C -> S") && arquivoS.contains("ENVIADO S -> C"), "ambas as direcoes no servidor");
        check(arquivoC.lines().filter(l -> l.contains("ENVIADO C -> S")).count()
                == arquivoC.lines().filter(l -> l.contains("RECEBIDO S -> C")).count(), "cada requisicao tem retorno no log cliente");
        check(arquivoS.lines().filter(l -> l.contains("RECEBIDO C -> S")).count()
                == arquivoS.lines().filter(l -> l.contains("ENVIADO S -> C")).count(), "cada requisicao tem retorno no log servidor");
        for (String log : List.of(arquivoC, arquivoS)) {
            check(!log.contains("Segredo987") && !log.contains("admin123") && !log.contains(token) && !log.contains(tokenAdmin), "segredos protegidos no log");
            check(log.contains("[oculto]") && log.contains("login_response") && log.contains("logout_response"), "mensagens reconheciveis e senha oculta");
        }
        try (PrintStream saida = new PrintStream(new ByteArrayOutputStream())) {
            new RegistroMensagens(logCliente, saida, "CLIENTE").accept("SEGUNDA EXECUCAO");
            check(Files.readString(logCliente).startsWith(arquivoC) && Files.readString(logCliente).contains("SEGUNDA EXECUCAO"), "reiniciar acrescenta sem apagar log");
            ByteArrayOutputStream aviso = new ByteArrayOutputStream();
            new RegistroMensagens(pasta, new PrintStream(aviso), "TESTE").accept("evento");
            check(aviso.toString(StandardCharsets.UTF_8).contains("Nao foi possivel gravar"), "falha de escrita visivel sem interromper programa");
        }
    }

    private static void logoutAposInatividade(Aplicacao aplicacao) throws Exception {
        CountDownLatch fechou = new CountDownLatch(1);
        List<String> eventos = new ArrayList<>();
        try (ServidorTCP servidor = new ServidorTCP(0, aplicacao, 150, linha -> {
            if (linha.contains("inatividade")) fechou.countDown();
        })) {
            servidor.iniciar();
            try (Comunicacao c = new Comunicacao("127.0.0.1", servidor.porta(), eventos::add)) {
                String token = texto(status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "200"), "token");
                check(fechou.await(5, TimeUnit.SECONDS), "servidor fechou socket por inatividade");
                status(c, req("logout", "token", token), "200");
                check(eventos.stream().anyMatch(l -> l.contains("reconectando")), "logout reconecta e reenvia");
                status(c, req("read_user", "token", token), "401");
                String novo = texto(status(c, req("login", "email", "ana_teste@alunos.meu-campus.edu.br", "password", "Segredo987"), "200"), "token");
                status(c, req("logout", "token", novo), "200");
            }
        }
    }

    private static void clienteSoSaiComResposta() throws Exception {
        String token = "a".repeat(64);
        var pool = Executors.newSingleThreadExecutor();
        ByteArrayOutputStream tela = new ByteArrayOutputStream();
        List<JsonObject> pedidos = new ArrayList<>();
        try (ServerSocket escuta = new ServerSocket(0)) {
            escuta.setSoTimeout(10_000);
            var servidor = pool.submit(() -> {
                try {
                    int logouts = 0;
                    try (Socket socket = escuta.accept()) {
                        socket.setSoTimeout(10_000);
                        BufferedReader leitor = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String linha;
                        while ((linha = leitor.readLine()) != null) {
                            JsonObject pedido = JsonUtil.lerObjeto(linha);
                            pedidos.add(pedido);
                            String op = texto(pedido, "op");
                            JsonObject resposta;
                            if (op.equals("login")) {
                                resposta = JsonUtil.resposta(op, "200", Mensagens.LOGIN);
                                resposta.addProperty("token", token);
                                resposta.addProperty("role", "user");
                            } else if (op.equals("logout")) {
                                if (++logouts == 2) break; // simula resposta perdida e socket encerrado
                                resposta = JsonUtil.resposta(op, "500", Mensagens.E500);
                            } else {
                                resposta = JsonUtil.resposta(op, "200", Mensagens.CONSULTA);
                                resposta.addProperty("user", "teste");
                                resposta.addProperty("email", "teste@email.com");
                                resposta.addProperty("role", "user");
                            }
                            socket.getOutputStream().write((JsonUtil.escrever(resposta) + "\n").getBytes(StandardCharsets.UTF_8));
                            socket.getOutputStream().flush();
                        }
                    }
                    try (Socket novo = escuta.accept()) {
                        novo.setSoTimeout(10_000);
                        BufferedReader leitor = new BufferedReader(new InputStreamReader(novo.getInputStream(), StandardCharsets.UTF_8));
                        pedidos.add(JsonUtil.lerObjeto(leitor.readLine()));
                        novo.getOutputStream().write((JsonUtil.escrever(JsonUtil.resposta("logout", "401", Mensagens.E401_token)) + "\n").getBytes(StandardCharsets.UTF_8));
                        novo.getOutputStream().flush();
                    }
                    return null;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            try (PrintStream saida = new PrintStream(tela, true, StandardCharsets.UTF_8);
                    Comunicacao c = new Comunicacao("127.0.0.1", escuta.getLocalPort(), saida::println)) {
                String roteiro = String.join("\n", "2", "teste@email.com", "abc", "0", "n", "9", "12", "0", "");
                new ClienteCLI(new Scanner(roteiro), saida, c, null).executar();
            }
            servidor.get(15, TimeUnit.SECONDS);
            String texto = tela.toString(StandardCharsets.UTF_8);
            check(texto.contains("Logout nao confirmado. Sessao mantida"), "sair com 500 preserva sessao");
            check(texto.contains("user: teste"), "conta continua acessivel apos falha no logout");
            check(!texto.contains("Entrada encerrada") && !texto.contains("Opcao invalida"), "cliente encerra apenas com resposta definitiva");
            List<JsonObject> logouts = pedidos.stream().filter(p -> texto(p, "op").equals("logout")).toList();
            check(logouts.size() == 3, "logout recusado, nova tentativa e reenvio apos perda de resposta");
            check(logouts.stream().allMatch(p -> texto(p, "token").equals(token)), "todas as tentativas conservam o mesmo token");
            check(texto.contains("401 - Token invalido ou expirado"), "logout_response 401 confirma fim da sessao");
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(15, TimeUnit.SECONDS), "simulador encerrado");
        }
    }

    private static JsonObject req(String op, String... campos) {
        JsonObject pedido = JsonUtil.requisicao(op);
        for (int i = 0; i < campos.length; i += 2) pedido.addProperty(campos[i], campos[i + 1]);
        return pedido;
    }

    private static JsonObject status(Comunicacao c, JsonObject pedido, String esperado) throws Exception {
        JsonObject resposta = c.enviar(pedido);
        check(texto(resposta, "status").equals(esperado), texto(pedido, "op") + " esperado " + esperado + ": " + resposta);
        return resposta;
    }

    private static String texto(JsonObject o, String campo) { return JsonUtil.texto(o, campo); }

    private static void check(boolean ok, String mensagem) {
        if (!ok) throw new AssertionError(mensagem);
        verificacoes++;
    }
}
