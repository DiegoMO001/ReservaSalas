package comum;

import com.google.gson.JsonObject;

public final class ErroProtocolo extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String operacao;
    private final String status;

    public ErroProtocolo(String operacao, String status, String mensagem) {
        super(mensagem);
        this.operacao = operacao;
        this.status = status;
    }

    public JsonObject resposta() {
        return JsonUtil.resposta(operacao, status, getMessage());
    }
}
