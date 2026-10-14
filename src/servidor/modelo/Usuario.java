package servidor.modelo;

import com.google.gson.JsonObject;
import comum.Datas;
import java.time.LocalDateTime;

public record Usuario(long id, String user, String email, String role,
        SenhaHash senha, LocalDateTime createdAt) {

    public boolean admin() {
        return role.equals("admin");
    }

    public JsonObject publico(boolean detalhado) {
        JsonObject objeto = new JsonObject();
        objeto.addProperty("user", user);
        objeto.addProperty("email", email);
        objeto.addProperty("role", role);
        if (detalhado) {
            objeto.addProperty("created_at", Datas.formatar(createdAt));
        }
        return objeto;
    }
}
