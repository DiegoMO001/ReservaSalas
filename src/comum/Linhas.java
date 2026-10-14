package comum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class Linhas {
    public static final int LIMITE_REQUISICAO = 8192;

    public record Linha(byte[] bytes, boolean excedida) {
        public String texto() throws CharacterCodingException {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private Linhas() {
    }

    public static Linha ler(InputStream entrada) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean excedida = false;
        int tamanho = 0;
        int valor;
        while ((valor = entrada.read()) != -1) {
            if (tamanho < LIMITE_REQUISICAO + 1) {
                tamanho++;
            }
            if (tamanho > LIMITE_REQUISICAO) {
                excedida = true;
            }
            if (valor == '\n') {
                return new Linha(bytes.toByteArray(), excedida);
            }
            if (tamanho < LIMITE_REQUISICAO) {
                bytes.write(valor);
            }
        }
        return null;
    }
}
