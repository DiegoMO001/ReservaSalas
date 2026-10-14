package cliente;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comum.ErroProtocolo;
import comum.JsonUtil;
import comum.Mensagens;
import comum.Validacao;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;

public final class ClienteCLI {
    private static final Map<String, String> NOMES = Map.ofEntries(
            Map.entry("user", "Usuario"), Map.entry("password", "Senha"), Map.entry("email", "Email"),
            Map.entry("target_user", "Usuario alvo"), Map.entry("role", "Perfil (user/admin)"));
    private final Scanner entrada;
    private final PrintStream saida;
    private final Comunicacao comunicacao;
    private final Console console;
    private String token;
    private String role;

    public ClienteCLI(Scanner entrada, PrintStream saida, Comunicacao comunicacao, Console console) {
        this.entrada = entrada;
        this.saida = saida;
        this.comunicacao = comunicacao;
        this.console = console;
    }

    public void executar() {
        try {
            while (true) {
                if (token == null) {
                    saida.println("1 Cadastrar\n2 Login\n0 Sair");
                    int opcao = numero();
                    if (opcao == 0) {
                        break;
                    }
                    if (opcao == 1 || opcao == 2) {
                        operacao(opcao == 1 ? "register" : "login");
                    } else {
                        saida.println("Opcao invalida");
                    }
                } else {
                    atualizarPerfil();
                    if (token == null) {
                        continue;
                    }
                    saida.println("Minha conta: 9 Ver | 10 Atualizar | 11 Remover | 12 Logout | 0 Sair");
                    if (admin()) {
                        saida.println("Admin - Usuarios: 24 Listar | 25 Ler | 26 Atualizar | 27 Remover");
                    }
                    int opcao = numero();
                    if (opcao == 0) {
                        operacao("logout");
                        if (token != null) {
                            saida.println("Logout nao confirmado. Sessao mantida; tente novamente antes de sair.");
                            continue;
                        }
                        break;
                    }
                    String op = switch (opcao) {
                        case 9 -> "read_user";
                        case 10 -> "update_user";
                        case 11 -> "delete_user";
                        case 12 -> "logout";
                        case 24 -> "admin_list_users";
                        case 25 -> "admin_read_user";
                        case 26 -> "admin_update_user";
                        case 27 -> "admin_delete_user";
                        default -> null;
                    };
                    if (op == null || (opcao >= 20 && !admin())) {
                        saida.println("Opcao invalida");
                    } else {
                        operacao(op);
                    }
                }
            }
        } catch (NoSuchElementException ex) {
            saida.println("Entrada encerrada");
        } finally {
            if (token != null) {
                try {
                    JsonObject resposta = comunicacao.enviar(pedido("logout"));
                    tratarSessao("logout", resposta);
                    saida.println(texto(resposta, "status") + " - " + texto(resposta, "message"));
                } catch (IOException | RuntimeException ex) {
                    saida.println("Nao foi possivel confirmar o logout");
                }
            }
            comunicacao.close();
            saida.println("Cliente encerrado");
        }
    }

    private void atualizarPerfil() {
        try {
            JsonObject resposta = comunicacao.enviar(pedido("read_user"));
            if (texto(resposta, "status").equals("200")) {
                role = texto(resposta, "role");
            } else {
                tratarSessao("read_user", resposta);
                saida.println(texto(resposta, "message"));
            }
        } catch (IOException | RuntimeException ex) {
            saida.println("Nao foi possivel atualizar o perfil. Tente a operacao desejada.");
        }
    }

    private void operacao(String op) {
        JsonObject anterior = null;
        while (true) {
            JsonObject requisicao = formulario(op, anterior);
            try {
                Validacao.validar(requisicao);
            } catch (ErroProtocolo ex) {
                saida.println(ex.getMessage());
                if (!confirmar("Corrigir os dados?")) {
                    return;
                }
                anterior = requisicao;
                continue;
            }
            while (true) {
                JsonObject resposta;
                try {
                    resposta = comunicacao.enviar(requisicao);
                } catch (IOException | RuntimeException ex) {
                    saida.println("Falha de comunicacao. A resposta nao foi confirmada.");
                    if (confirmar("Tentar novamente?")) {
                        continue;
                    }
                    return;
                }
                String status = texto(resposta, "status");
                saida.println(status + " - " + texto(resposta, "message"));
                tratarSessao(op, resposta);
                if (status.equals("200") || status.equals("201")) {
                    mostrar(resposta);
                    return;
                }
                if (status.equals("400") && confirmar("Corrigir os dados?")) {
                    anterior = requisicao;
                    break;
                }
                if (status.equals("500") && confirmar("Tentar novamente?")) {
                    continue;
                }
                if (status.equals("404")) {
                    recarregar(op);
                } else if (status.equals("409")) {
                    alternativa(op);
                }
                return;
            }
        }
    }

    private JsonObject formulario(String op, JsonObject anterior) {
        JsonObject pedido = pedido(op);
        List<String> campos = switch (op) {
            case "register" -> List.of("user", "email", "password");
            case "login" -> List.of("email", "password");
            case "update_user" -> List.of("~user", "~password");
            case "delete_user" -> List.of("password");
            case "admin_read_user", "admin_delete_user" -> List.of("target_user");
            case "admin_update_user" -> List.of("target_user", "~user", "~password", "~role");
            default -> List.of();
        };
        if (op.startsWith("update_") || op.equals("admin_update_user")) {
            saida.println("Campos de alteracao: Enter mantem o valor atual.");
        }
        if (anterior != null) {
            saida.println("Enter reutiliza o valor entre colchetes. Digite ! para limpar a escolha.");
        }
        for (String definicao : campos) {
            boolean vazio = definicao.startsWith("~");
            String campo = vazio ? definicao.substring(1) : definicao;
            String padrao = null;
            if (anterior != null && anterior.has(campo)) {
                padrao = texto(anterior, campo);
            }
            while (true) {
                String valor = lerCampo(campo, padrao, vazio);
                if (Validacao.campo(campo, valor, vazio)) {
                    pedido.addProperty(campo, valor);
                    break;
                }
                saida.println("Campo em formato invalido. Digite novamente.");
            }
        }
        return pedido;
    }

    private String lerCampo(String campo, String padrao, boolean vazio) {
        String rotulo = NOMES.getOrDefault(campo, campo);
        if (vazio) {
            rotulo += " (Enter nao altera)";
        }
        if (padrao != null && !padrao.isEmpty()) {
            rotulo += " [" + (campo.equals("password") ? "senha mantida" : padrao) + "]";
        }
        String valor;
        if (campo.equals("password") && console != null) {
            char[] caracteres = console.readPassword("%s: ", rotulo);
            if (caracteres == null) {
                throw new NoSuchElementException();
            }
            valor = new String(caracteres);
            Arrays.fill(caracteres, '\0');
        } else {
            saida.print(rotulo + ": ");
            valor = entrada.nextLine();
        }
        if (valor.equals("!")) {
            return "";
        }
        return valor.isEmpty() && padrao != null ? padrao : valor;
    }

    private void tratarSessao(String op, JsonObject resposta) {
        String status = texto(resposta, "status");
        if (status.equals("401")) {
            token = null;
            role = null;
        } else if (status.equals("200") && (op.equals("logout") || op.equals("delete_user"))) {
            token = null;
            role = null;
        } else if (status.equals("200") && op.equals("login")) {
            token = texto(resposta, "token");
            role = texto(resposta, "role");
        } else if (status.equals("200") && op.equals("read_user")) {
            role = texto(resposta, "role");
        }
    }

    private void recarregar(String op) {
        if (token != null && op.startsWith("admin_")) {
            consultar(pedido("admin_list_users"));
        }
    }

    private void alternativa(String op) {
        if (op.equals("login")) {
            saida.println("Use as credenciais corretas; se houver sessao ativa, faca logout no outro cliente ou aguarde 30 minutos sem uso.");
        } else if (op.equals("register")) {
            saida.println("Escolha Cadastrar com outro usuario/email ou Login para usar a conta existente.");
        } else {
            saida.println("Escolha Atualizar novamente e informe outro nome de usuario.");
        }
    }

    private void consultar(JsonObject pedido) {
        try {
            JsonObject resposta = comunicacao.enviar(pedido);
            tratarSessao(texto(pedido, "op"), resposta);
            saida.println(texto(resposta, "message"));
            if (texto(resposta, "status").equals("200")) {
                mostrar(resposta);
            }
        } catch (IOException | RuntimeException ex) {
            saida.println("Nao foi possivel recarregar a listagem");
        }
    }

    private void mostrar(JsonObject resposta) {
        if (resposta.has("users")) {
            tabela(resposta.getAsJsonArray("users"));
            return;
        }
        resposta.entrySet().stream().filter(e -> !Set.of("op", "status", "message", "token").contains(e.getKey()))
                .forEach(e -> saida.println(e.getKey() + ": " + exibicao(e.getValue())));
    }

    private void tabela(JsonArray linhas) {
        LinkedHashSet<String> colunas = new LinkedHashSet<>();
        linhas.forEach(linha -> colunas.addAll(linha.getAsJsonObject().keySet()));
        if (!colunas.isEmpty()) {
            List<String> nomes = List.copyOf(colunas);
            int[] larguras = nomes.stream().mapToInt(String::length).toArray();
            for (JsonElement linha : linhas) {
                for (int i = 0; i < nomes.size(); i++) {
                    larguras[i] = Math.max(larguras[i], exibicao(linha.getAsJsonObject().get(nomes.get(i))).length());
                }
            }
            imprimirLinha(nomes, larguras);
            for (JsonElement linha : linhas) {
                imprimirLinha(nomes.stream().map(nome -> exibicao(linha.getAsJsonObject().get(nome))).toList(), larguras);
            }
        }
        saida.println("Total: " + linhas.size());
    }

    private void imprimirLinha(List<String> valores, int[] larguras) {
        for (int i = 0; i < valores.size(); i++) {
            saida.printf("%-" + larguras[i] + "s  ", valores.get(i));
        }
        saida.println();
    }

    private static String exibicao(JsonElement valor) {
        return valor == null ? "" : valor.getAsString();
    }

    private JsonObject pedido(String op) {
        JsonObject pedido = JsonUtil.requisicao(op);
        if (!op.equals("register") && !op.equals("login")) {
            pedido.addProperty("token", token);
        }
        return pedido;
    }

    private int numero() {
        saida.print("Opcao: ");
        try {
            return Integer.parseInt(entrada.nextLine().trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private boolean confirmar(String pergunta) {
        saida.print(pergunta + " [s/N]: ");
        return entrada.nextLine().trim().equalsIgnoreCase("s");
    }

    private boolean admin() {
        return "admin".equals(role);
    }

    private static String texto(JsonObject objeto, String chave) {
        return JsonUtil.texto(objeto, chave);
    }
}
