package comum;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.DateTimeException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class Validacao {
    public static final Set<String> OPERACOES = Set.of(
            "register", "login", "logout", "read_user", "update_user", "delete_user",
            "admin_list_users", "admin_read_user", "admin_update_user", "admin_delete_user");
    private static final Map<String, String> REGEX = Map.ofEntries(
            Map.entry("op", "^[a-z_]{3,30}$"),
            Map.entry("status", "^[0-9]{3}$"),
            Map.entry("token", "^[a-f0-9]{64}$"),
            Map.entry("user", "^[a-z]{1,30}$"),
            Map.entry("target_user", "^[a-z]{1,30}$"),
            Map.entry("password", "^[A-Za-z0-9]{1,20}$"),
            Map.entry("email", "^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$"),
            Map.entry("role", "^(user|admin)$"),
            Map.entry("count", "^[0-9]{1,10}$"),
            Map.entry("created_at", "^[0-9]{4}-[0-9]{2}-[0-9]{2} ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$"));
    private static final Map<String, Pattern> PADROES = REGEX.entrySet().stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Pattern.compile(e.getValue())));

    private Validacao() {
    }

    public static boolean campo(String nome, String valor, boolean aceitaVazio) {
        if (valor == null) {
            return false;
        }
        if (aceitaVazio && valor.isEmpty()) {
            return true;
        }
        Pattern padrao = PADROES.get(nome);
        if (padrao == null || !padrao.matcher(valor).matches()) {
            return false;
        }
        try {
            if (nome.equals("created_at")) {
                Datas.combinar(valor.substring(0, 10), valor.substring(11));
            }
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }

    public static String validar(JsonObject pedido) {
        if (pedido == null || !JsonUtil.ehString(pedido.get("op"))) {
            throw new ErroProtocolo(null, "400", Mensagens.E400_requisicao);
        }
        String op = JsonUtil.texto(pedido, "op");
        if (!campo("op", op, false) || !OPERACOES.contains(op)) {
            throw new ErroProtocolo(null, "400", Mensagens.E400_operacao);
        }
        if (!op.equals("register") && !op.equals("login")) {
            exigir(pedido, op, Mensagens.E400_token, false, "token");
        }
        switch (op) {
            case "register" -> exigir(pedido, op, Mensagens.E400_cadastro, false, "user", "email", "password");
            case "login" -> exigir(pedido, op, Mensagens.E400_credenciais, false, "email", "password");
            case "delete_user" -> exigir(pedido, op, Mensagens.E400_senha, false, "password");
            case "update_user", "admin_update_user" -> {
                String erro = Mensagens.E400_dados;
                if (pedido.has("email")) {
                    falhar(op, erro);
                }
                if (op.equals("admin_update_user")) {
                    exigir(pedido, op, erro, false, "target_user");
                    exigir(pedido, op, erro, true, "role");
                }
                exigir(pedido, op, erro, true, "user", "password");
                if (JsonUtil.texto(pedido, "user").isEmpty() && JsonUtil.texto(pedido, "password").isEmpty()
                        && (op.equals("update_user") || JsonUtil.texto(pedido, "role").isEmpty())) {
                    falhar(op, Mensagens.E400_nada_para_atualizar);
                }
            }
            case "admin_read_user", "admin_delete_user" ->
                exigir(pedido, op, Mensagens.E400_dados, false, "target_user");
            default -> {
            }
        }
        return op;
    }

    private static void exigir(JsonObject pedido, String op, String erro, boolean vazio, String... campos) {
        for (String nome : campos) {
            JsonElement valor = pedido.get(nome);
            if (!JsonUtil.ehString(valor) || !campo(nome, valor.getAsString(), vazio)) {
                falhar(op, erro);
            }
        }
    }

    private static void falhar(String op, String erro) {
        throw new ErroProtocolo(op, "400", erro);
    }
}
