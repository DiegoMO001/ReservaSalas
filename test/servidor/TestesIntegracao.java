package servidor;

import cliente.ClienteCLI;
import cliente.Comunicacao;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comum.Datas;
import comum.JsonUtil;
import comum.Mensagens;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import servidor.dao.BancoDAO;
import servidor.services.Aplicacao;

public final class TestesIntegracao {
    private static int verificacoes;
    private static String admin;
    private static String joao;
    private static String maria;

    private TestesIntegracao() {
    }

    public static void main(String[] args) throws Exception {
        Path pasta = Path.of(args.length == 0 ? "build/test-data" : args[0]);
        Files.createDirectories(pasta);
        BancoDAO banco = new BancoDAO("jdbc:sqlite:" + Files.createTempFile(pasta, "integracao-", ".db").toAbsolutePath());
        banco.inicializar();
        Relogio relogio = new Relogio(Instant.parse("2030-01-10T15:00:00Z"));
        Aplicacao aplicacao = new Aplicacao(banco, relogio);
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        try (ServidorTCP servidor = new ServidorTCP(0, aplicacao, 300_000, logs::add)) {
            servidor.iniciar();
            try (Comunicacao cliente = cliente(servidor.porta())) {
                contas(cliente);
                concorrencia(servidor.porta());
                protocolo(servidor.porta());
                grandeListagem(cliente);
                clienteCLI(servidor.porta());
                clienteAdmin(servidor.porta(), cliente);
                operacoesRemovidas(cliente);
                expiracao(cliente, relogio);
            }
            logs.forEach(linha -> check(!linha.contains("password=") && !linha.contains(admin)
                    && !linha.contains(joao) && !linha.contains(maria), "log sem senha ou token completo"));
        }
        reconexao(aplicacao, logs);
        System.out.println("Integracao TCP e CLI: " + verificacoes + " verificacoes aprovadas.");
    }

    private static void contas(Comunicacao cliente) throws Exception {
        esperar(cliente, pedido("register", null, "user", "joao", "email", "joao@email.com", "password", "senha123", "role", "admin"), "201", Mensagens.CADASTRO);
        esperar(cliente, pedido("register", null, "user", "maria", "email", "maria@email.com", "password", "senha123"), "201", Mensagens.CADASTRO);
        esperar(cliente, pedido("register", null, "user", "joao", "email", "outro@email.com", "password", "senha123"), "409", Mensagens.E409_cadastrado);
        esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "errada"), "401", Mensagens.E401_credenciais);
        esperar(cliente, pedido("login", null, "email", "inexistente@email.com", "password", "errada"), "401", Mensagens.E401_credenciais);
        JsonObject login = esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "senha123"), "200", Mensagens.LOGIN);
        joao = texto(login, "token");
        check(texto(login, "role").equals("user"), "register ignora role admin");
        admin = texto(esperar(cliente, pedido("login", null, "email", "admin@campus.com", "password", "admin123"), "200", Mensagens.LOGIN), "token");
        maria = texto(esperar(cliente, pedido("login", null, "email", "maria@email.com", "password", "senha123"), "200", Mensagens.LOGIN), "token");
        esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "errada"), "401", Mensagens.E401_credenciais);
        esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "senha123"), "409", Mensagens.E409_sessao_ativa);
        JsonObject dados = esperar(cliente, pedido("read_user", joao, "user", "admin"), "200", Mensagens.CONSULTA);
        check(texto(dados, "user").equals("joao") && !dados.has("password") && !dados.has("password_hash") && !dados.has("salt"), "autor pelo token e resposta sem senha");
        esperar(cliente, pedido("update_user", joao, "user", "joaonovo", "password", "nova123"), "200", Mensagens.USUARIO_ATUALIZADO);
        check(texto(esperar(cliente, pedido("read_user", joao), "200", Mensagens.CONSULTA), "user").equals("joaonovo"), "renomear preserva token");
        esperar(cliente, pedido("update_user", joao, "user", "", "password", ""), "400", Mensagens.E400_nada_para_atualizar);
        esperar(cliente, pedido("update_user", joao, "user", "maria", "password", ""), "409", Mensagens.E409_usuario_em_uso);
        esperar(cliente, pedido("update_user", joao, "user", "", "password", "", "email", "x@y.com"), "400", Mensagens.E400_dados);
        esperar(cliente, pedido("delete_user", joao, "password", "errada"), "403", Mensagens.E403_senha_incorreta);
        esperar(cliente, pedido("read_user", joao), "200", Mensagens.CONSULTA);
        esperar(cliente, pedido("delete_user", admin, "password", "errada"), "403", Mensagens.E403_senha_incorreta);
        esperar(cliente, pedido("delete_user", admin, "password", "admin123"), "403", Mensagens.E403_ultimo_admin);
        esperar(cliente, pedido("admin_update_user", admin, "target_user", "admin", "user", "", "password", "", "role", "user"), "403", Mensagens.E403_ultimo_admin);
        esperar(cliente, pedido("admin_delete_user", admin, "target_user", "admin"), "403", Mensagens.E403_ultimo_admin);
        esperar(cliente, pedido("admin_list_users", joao), "403", Mensagens.E403_permissao);
        esperar(cliente, pedido("admin_read_user", admin, "target_user", "ninguem"), "404", Mensagens.E404_usuario);
        esperar(cliente, pedido("admin_read_user", joao, "target_user", "ninguem"), "403", Mensagens.E403_permissao);
        esperar(cliente, pedido("admin_update_user", admin, "target_user", "maria", "user", "", "password", "", "role", "admin"), "200", Mensagens.USUARIO_ATUALIZADO);
        esperar(cliente, pedido("admin_list_users", maria), "200", Mensagens.CONSULTA);
        esperar(cliente, pedido("admin_update_user", admin, "target_user", "maria", "user", "", "password", "", "role", "user"), "200", Mensagens.USUARIO_ATUALIZADO);
        esperar(cliente, pedido("admin_list_users", maria), "403", Mensagens.E403_permissao);
        esperar(cliente, pedido("register", null, "user", "temporario", "email", "temporario@email.com", "password", "abc"), "201", Mensagens.CADASTRO);
        esperar(cliente, pedido("admin_read_user", admin, "target_user", "temporario"), "200", Mensagens.CONSULTA);
        esperar(cliente, pedido("admin_update_user", admin, "target_user", "temporario", "user", "tempnovo", "password", "nova", "role", ""), "200", Mensagens.USUARIO_ATUALIZADO);
        String tokenTemp = texto(esperar(cliente, pedido("login", null, "email", "temporario@email.com", "password", "nova"), "200", Mensagens.LOGIN), "token");
        esperar(cliente, pedido("admin_delete_user", admin, "target_user", "tempnovo"), "200", Mensagens.USUARIO_REMOVIDO);
        esperar(cliente, pedido("read_user", tokenTemp), "401", Mensagens.E401_token);
        esperar(cliente, pedido("logout", joao), "200", Mensagens.LOGOUT);
        esperar(cliente, pedido("logout", joao), "401", Mensagens.E401_token);
        esperar(cliente, pedido("read_user", joao), "401", Mensagens.E401_token);
        esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "senha123"), "401", Mensagens.E401_credenciais);
        String anterior = joao;
        joao = texto(esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "nova123"), "200", Mensagens.LOGIN), "token");
        check(!joao.equals(anterior), "token nao reutilizado");
        System.out.println("Contas: cadastro, consulta, login, logout, atualizacao e exclusao validados.");
    }

    private static void concorrencia(int porta) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (String op : List.of("register", "login")) {
                CountDownLatch largada = new CountDownLatch(1);
                List<Future<JsonObject>> resultados = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    resultados.add(pool.submit(() -> {
                        try (Comunicacao cliente = cliente(porta)) {
                            JsonObject requisicao = pedido(op, null, "email", "paralelo@email.com", "password", "abc");
                            if (op.equals("register")) {
                                requisicao.addProperty("user", "paralelo");
                            }
                            largada.await();
                            return cliente.enviar(requisicao);
                        }
                    }));
                }
                largada.countDown();
                int sucessos = 0;
                for (Future<JsonObject> futuro : resultados) {
                    JsonObject resposta = futuro.get(20, TimeUnit.SECONDS);
                    String status = texto(resposta, "status");
                    if (status.equals(op.equals("register") ? "201" : "200")) {
                        sucessos++;
                    } else {
                        check(status.equals("409"), op + " concorrente deve retornar conflito");
                    }
                }
                check(sucessos == 1, "apenas um " + op + " aceito em oito conexoes concorrentes");
            }
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "pool concorrente encerrado");
        }
        System.out.println("Concorrencia TCP: cadastro unico e sessao unica preservados.");
    }

    private static void protocolo(int porta) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", porta)) {
            socket.setSoTimeout(10000);
            BufferedReader leitor = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            for (String invalida : List.of("{", "[]", "null", "{\"op\":3}", "{\"op\":\"LOGIN\"}", "{op:'login'}")) {
                enviarLinha(socket, invalida);
                JsonObject resposta = JsonUtil.lerObjeto(leitor.readLine());
                check(texto(resposta, "op").equals("error") && texto(resposta, "status").equals("400"), "JSON/op invalidos respondidos");
            }
            String base = JsonUtil.escrever(pedido("read_user", joao));
            enviarLinha(socket, base + " ".repeat(8191 - base.getBytes(StandardCharsets.UTF_8).length));
            check(texto(JsonUtil.lerObjeto(leitor.readLine()), "status").equals("200"), "8192 bytes incluindo LF aceitos");
            enviarLinha(socket, base + " ".repeat(8192 - base.getBytes(StandardCharsets.UTF_8).length));
            check(texto(JsonUtil.lerObjeto(leitor.readLine()), "message").equals(Mensagens.E400_tamanho), "8193 bytes rejeitados");
            socket.getOutputStream().write(new byte[]{(byte) 0xC3, (byte) 0x28, 10});
            socket.getOutputStream().flush();
            check(texto(JsonUtil.lerObjeto(leitor.readLine()), "message").equals(Mensagens.E400_requisicao), "UTF8 invalido rejeitado");
            enviarLinha(socket, base + "\n" + base + "\n" + base);
            for (int i = 0; i < 3; i++) {
                check(texto(JsonUtil.lerObjeto(leitor.readLine()), "status").equals("200"), "uma resposta por linha apos erro");
            }
            String unicode = "{\"op\":\"read_user\",\"token\":\"" + joao + "\",\"extra\":\"" + "\u00e9".repeat(4200) + "\"}";
            enviarLinha(socket, unicode);
            check(texto(JsonUtil.lerObjeto(leitor.readLine()), "message").equals(Mensagens.E400_tamanho), "limite em bytes UTF8 e nao caracteres");
        }
    }

    private static void grandeListagem(Comunicacao cliente) throws Exception {
        for (int i = 0; i < 160; i++) {
            String nome = "listagem" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            esperar(cliente, pedido("register", null, "user", nome, "email", nome + "@email.com", "password", "abc"), "201", Mensagens.CADASTRO);
        }
        JsonObject resposta = esperar(cliente, pedido("admin_list_users", admin), "200", Mensagens.CONSULTA);
        check(JsonUtil.escrever(resposta).getBytes(StandardCharsets.UTF_8).length > 8192, "cliente recebe listagem maior que 8192 bytes");
        check(Integer.parseInt(texto(resposta, "count")) == resposta.getAsJsonArray("users").size(), "count corresponde a listagem");
        String anterior = "";
        for (JsonElement elemento : resposta.getAsJsonArray("users")) {
            String atual = texto(elemento.getAsJsonObject(), "user");
            check(anterior.compareTo(atual) <= 0, "usuarios ordenados por nome");
            anterior = atual;
        }
    }

    private static void clienteCLI(int porta) throws Exception {
        String roteiro = String.join("\n", "1", "ciclo", "ciclo_teste@alunos.utfpr.edu.br", "senha123", "2", "ciclo_teste@alunos.utfpr.edu.br", "senha123",
                "9", "10", "", "", "n", "9", "10", "ciclonovo", "nova123", "9", "12", "2", "ciclo_teste@alunos.utfpr.edu.br", "nova123", "11", "errada",
                "9", "11", "nova123", "0", "");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (Comunicacao comunicacao = cliente(porta); PrintStream saida = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            new ClienteCLI(new Scanner(roteiro), saida, comunicacao, null).executar();
        }
        String tela = buffer.toString(StandardCharsets.UTF_8);
        for (String mensagem : List.of(Mensagens.CADASTRO, Mensagens.LOGIN, Mensagens.CONSULTA,
                Mensagens.USUARIO_ATUALIZADO, Mensagens.LOGOUT, Mensagens.E403_senha_incorreta, Mensagens.USUARIO_REMOVIDO)) {
            check(tela.contains(mensagem), "cliente CLI executa " + mensagem);
        }
        check(!tela.contains("Salas:") && !tela.contains("Reservas:"), "menu comum somente usuarios");
        check(tela.contains("user: ciclonovo"), "CLI le cadastro atualizado");
        check(!tela.contains("Opcao invalida") && !tela.contains("Entrada encerrada"), "roteiro completo do cliente sem desvio");
        check(tela.chars().allMatch(c -> c < 128), "console sem acentos e emojis");
        try (Comunicacao cliente = cliente(porta)) {
            esperar(cliente, pedido("login", null, "email", "ciclo_teste@alunos.utfpr.edu.br", "password", "nova123"), "401", Mensagens.E401_credenciais);
        }
        System.out.println("Itens a-l: cliente CLI real executou cadastro, leitura, login, logout, atualizacao e exclusao por TCP.");
    }

    private static void expiracao(Comunicacao cliente, Relogio relogio) throws Exception {
        relogio.avancar(1800);
        esperar(cliente, pedido("read_user", joao), "401", Mensagens.E401_token);
        esperar(cliente, pedido("login", null, "email", "joao@email.com", "password", "nova123"), "200", Mensagens.LOGIN);
        esperar(cliente, pedido("read_user", joao), "401", Mensagens.E401_token);
        esperar(cliente, pedido("update_user", "b".repeat(64), "user", "invalido1", "password", ""), "400", Mensagens.E400_dados);
        esperar(cliente, pedido("read_user", "b".repeat(64)), "401", Mensagens.E401_token);
        esperar(cliente, pedido("update_user", "x", "user", "invalido1"), "400", Mensagens.E400_token);
    }

    private static void clienteAdmin(int porta, Comunicacao controle) throws Exception {
        esperar(controle, pedido("logout", admin), "200", Mensagens.LOGOUT);
        String roteiro = String.join("\n", "1", "administrado", "administrado@email.com", "abc", "2", "admin@campus.com", "admin123",
                "24", "25", "administrado", "26", "administrado", "novonome", "nova", "", "25", "novonome",
                "27", "novonome", "25", "ausente", "12", "0", "");
        String tela = rodarCLI(porta, roteiro);
        for (String mensagem : List.of(Mensagens.CADASTRO, Mensagens.LOGIN, Mensagens.CONSULTA,
                Mensagens.USUARIO_ATUALIZADO, Mensagens.USUARIO_REMOVIDO, Mensagens.E404_usuario, Mensagens.LOGOUT,
                "user: novonome", "Total:")) {
            check(tela.contains(mensagem), "CLI admin: " + mensagem);
        }
        check(!tela.contains("Opcao invalida") && !tela.contains("Entrada encerrada"), "CLI admin completo");
        check(!tela.contains("Salas:") && !tela.contains("Reservas:") && !tela.contains("room_id"), "menu admin somente usuarios");
        admin = texto(esperar(controle, pedido("login", null, "email", "admin@campus.com", "password", "admin123"), "200", Mensagens.LOGIN), "token");
    }

    private static void operacoesRemovidas(Comunicacao cliente) throws Exception {
        for (String op : List.of("create_room", "list_rooms", "read_room", "update_room", "delete_room", "check_availability",
                "create_reservation", "list_reservations", "read_reservation", "update_reservation", "delete_reservation")) {
            JsonObject resposta = cliente.enviar(pedido(op, admin));
            check(texto(resposta, "op").equals("error") && texto(resposta, "status").equals("400")
                    && texto(resposta, "message").equals(Mensagens.E400_operacao), "operacao removida: " + op);
        }
    }

    private static String rodarCLI(int porta, String roteiro) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (Comunicacao comunicacao = cliente(porta); PrintStream saida = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            new ClienteCLI(new Scanner(roteiro), saida, comunicacao, null).executar();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void reconexao(Aplicacao aplicacao, List<String> logs) throws Exception {
        CountDownLatch expirou = new CountDownLatch(1);
        try (ServidorTCP servidor = new ServidorTCP(0, aplicacao, 150, texto -> {
            logs.add(texto);
            if (texto.contains("inatividade")) {
                expirou.countDown();
            }
        })) {
            servidor.iniciar();
            try (Comunicacao cliente = cliente(servidor.porta())) {
                String token = texto(esperar(cliente, pedido("login", null, "email", "maria@email.com", "password", "senha123"), "200", Mensagens.LOGIN), "token");
                check(expirou.await(5, TimeUnit.SECONDS), "conexao encerrada por timeout reduzido no teste");
                esperar(cliente, pedido("read_user", token), "200", Mensagens.CONSULTA);
                esperar(cliente, pedido("logout", token), "200", Mensagens.LOGOUT);
            }
        }
    }

    private static Comunicacao cliente(int porta) throws Exception {
        Comunicacao cliente = new Comunicacao("127.0.0.1", porta, linha -> {});
        cliente.conectar();
        return cliente;
    }

    private static JsonObject pedido(String op, String token, String... campos) {
        JsonObject pedido = JsonUtil.requisicao(op);
        if (token != null) {
            pedido.addProperty("token", token);
        }
        for (int i = 0; i < campos.length; i += 2) {
            pedido.addProperty(campos[i], campos[i + 1]);
        }
        return pedido;
    }

    private static JsonObject esperar(Comunicacao cliente, JsonObject pedido, String status, String mensagem) throws Exception {
        JsonObject resposta = cliente.enviar(pedido);
        check(texto(resposta, "status").equals(status) && texto(resposta, "message").equals(mensagem),
                texto(pedido, "op") + " esperado=" + status + "/" + mensagem + " recebido=" + resposta);
        check(texto(resposta, "op").equals(texto(pedido, "op") + "_response"), "nome da resposta");
        strings(resposta);
        return resposta;
    }

    private static void strings(JsonElement elemento) {
        if (elemento.isJsonObject()) {
            elemento.getAsJsonObject().entrySet().forEach(e -> strings(e.getValue()));
        } else if (elemento.isJsonArray()) {
            elemento.getAsJsonArray().forEach(TestesIntegracao::strings);
        } else {
            check(JsonUtil.ehString(elemento), "valor escalar string no protocolo");
        }
    }

    private static void enviarLinha(Socket socket, String texto) throws Exception {
        socket.getOutputStream().write((texto + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String texto(JsonObject objeto, String campo) {
        return JsonUtil.texto(objeto, campo);
    }

    private static void check(boolean condicao, String mensagem) {
        if (!condicao) {
            throw new AssertionError(mensagem);
        }
        verificacoes++;
    }

    private static final class Relogio extends Clock {
        private final AtomicReference<Instant> instante;

        private Relogio(Instant instante) {
            this.instante = new AtomicReference<>(instante);
        }

        void avancar(long segundos) {
            instante.updateAndGet(valor -> valor.plusSeconds(segundos));
        }

        @Override
        public ZoneId getZone() {
            return Datas.FUSO;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instante.get();
        }
    }
}
