package comum;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.util.ArrayList;
import java.util.List;

public final class JsonUtil {
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private JsonUtil() {
    }

    public static JsonObject lerObjeto(String texto) {
        try {
            JsonElement elemento = GSON.fromJson(texto, JsonElement.class);
            if (elemento == null || !elemento.isJsonObject()) {
                throw new JsonParseException(Mensagens.E400_requisicao);
            }
            return elemento.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new ErroProtocolo(null, "400", Mensagens.E400_requisicao);
        }
    }

    public static String escrever(JsonElement objeto) {
        return GSON.toJson(objeto);
    }

    public static JsonObject requisicao(String op) {
        JsonObject resultado = new JsonObject();
        resultado.addProperty("op", op);
        return resultado;
    }

    public static JsonObject resposta(String op, String status, String mensagem) {
        JsonObject resultado = requisicao(op == null ? "error" : op + "_response");
        resultado.addProperty("status", status);
        resultado.addProperty("message", mensagem);
        return resultado;
    }

    public static boolean ehString(JsonElement elemento) {
        return elemento != null && elemento.isJsonPrimitive()
                && elemento.getAsJsonPrimitive().isString();
    }

    public static String texto(JsonObject objeto, String chave) {
        return objeto.get(chave).getAsString();
    }

    public static JsonArray array(List<String> valores) {
        JsonArray resultado = new JsonArray();
        valores.forEach(resultado::add);
        return resultado;
    }

    public static List<String> strings(JsonArray array) {
        List<String> resultado = new ArrayList<>();
        for (JsonElement elemento : array) {
            if (!ehString(elemento)) {
                throw new IllegalArgumentException(Mensagens.E400_dados);
            }
            resultado.add(elemento.getAsString());
        }
        return List.copyOf(resultado);
    }

    public static List<String> lerStrings(String texto) {
        JsonElement elemento = GSON.fromJson(texto, JsonElement.class);
        if (elemento == null || !elemento.isJsonArray()) {
            throw new IllegalArgumentException(Mensagens.E400_dados);
        }
        return strings(elemento.getAsJsonArray());
    }
}
