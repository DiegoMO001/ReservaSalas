package comum;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.function.Consumer;

/** Registro local: nao acrescenta mensagens ao protocolo TCP. */
public final class RegistroMensagens implements Consumer<String> {
    private static final Set<String> CAMPOS = Set.of("op", "status", "message", "user", "email",
            "target_user", "role", "created_at", "count");
    private final Path arquivo;
    private final PrintStream console;
    private final String origem;
    private boolean avisouFalha;

    public RegistroMensagens(Path arquivo, PrintStream console, String origem) {
        this.arquivo = arquivo.toAbsolutePath();
        this.console = console;
        this.origem = origem + " pid=" + ProcessHandle.current().pid();
        accept("LOG iniciado em " + this.arquivo);
    }

    @Override
    public synchronized void accept(String evento) {
        String linha = Datas.formatar(Datas.agora()) + " [" + origem + "] " + evento.replace('\r', ' ').replace('\n', ' ');
        console.println(linha);
        try {
            Files.writeString(arquivo, linha + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException ex) {
            if (!avisouFalha) {
                console.println("Nao foi possivel gravar o log em " + arquivo + ". As mensagens continuam no console.");
                avisouFalha = true;
            }
        }
    }

    public static String mensagem(JsonObject objeto) {
        return objeto == null ? "[mensagem invalida; conteudo omitido]" : JsonUtil.escrever(filtrar(objeto, true));
    }

    private static JsonObject filtrar(JsonObject objeto, boolean incluirLista) {
        JsonObject seguro = new JsonObject();
        for (var campo : objeto.entrySet()) {
            String nome = campo.getKey();
            JsonElement valor = campo.getValue();
            if (nome.equals("password") || nome.equals("password_hash") || nome.equals("salt")) {
                seguro.addProperty(nome, "[oculto]");
            } else if (nome.equals("token")) {
                String token = JsonUtil.ehString(valor) ? valor.getAsString() : "";
                seguro.addProperty(nome, token.matches("[a-f0-9]{64}") ? token.substring(0, 8) + "..." : "[oculto]");
            } else if (CAMPOS.contains(nome)) {
                seguro.add(nome, valor.isJsonPrimitive() || valor.isJsonNull() ? valor : new com.google.gson.JsonPrimitive("[tipo invalido]"));
            } else if (incluirLista && nome.equals("users") && valor.isJsonArray()) {
                JsonArray usuarios = new JsonArray();
                for (JsonElement usuario : valor.getAsJsonArray()) {
                    if (usuario.isJsonObject()) {
                        usuarios.add(filtrar(usuario.getAsJsonObject(), false));
                    }
                }
                seguro.add("users", usuarios);
            }
        }
        return seguro;
    }
}
