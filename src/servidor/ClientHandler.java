package servidor;

import com.google.gson.JsonObject;
import comum.ErroProtocolo;
import comum.JsonUtil;
import comum.Linhas;
import comum.Mensagens;
import comum.RegistroMensagens;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import servidor.services.Aplicacao;

public final class ClientHandler implements Runnable {
    private static final AtomicLong TROCAS = new AtomicLong();
    private final Socket socket;
    private final Aplicacao aplicacao;
    private final Consumer<String> log;
    private final Runnable aoFechar;
    private volatile boolean parando;

    public ClientHandler(Socket socket, Aplicacao aplicacao, Consumer<String> log, Runnable aoFechar) {
        this.socket = socket;
        this.aplicacao = aplicacao;
        this.log = log;
        this.aoFechar = aoFechar;
    }

    @Override
    public void run() {
        try (socket;
                BufferedInputStream entrada = new BufferedInputStream(socket.getInputStream());
                BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!parando) {
                Linhas.Linha linha = Linhas.ler(entrada);
                if (linha == null) {
                    break;
                }
                JsonObject pedido = null;
                String contexto = "conexao=" + socket.getRemoteSocketAddress() + " troca=" + TROCAS.incrementAndGet();
                Aplicacao.Resultado resultado;
                try {
                    if (linha.excedida()) {
                        throw new ErroProtocolo(null, "400", Mensagens.E400_tamanho);
                    }
                    pedido = JsonUtil.lerObjeto(linha.texto());
                    log.accept("RECEBIDO C -> S " + contexto + " " + RegistroMensagens.mensagem(pedido));
                    resultado = aplicacao.processar(pedido);
                } catch (CharacterCodingException ex) {
                    resultado = new Aplicacao.Resultado(JsonUtil.resposta(null, "400", Mensagens.E400_requisicao), null);
                } catch (ErroProtocolo ex) {
                    resultado = new Aplicacao.Resultado(ex.resposta(), null);
                } catch (StackOverflowError ex) {
                    resultado = new Aplicacao.Resultado(JsonUtil.resposta(null, "400", Mensagens.E400_requisicao), null);
                }
                if (pedido == null) {
                    log.accept("RECEBIDO C -> S " + contexto + " " + RegistroMensagens.mensagem(null));
                }
                saida.write(JsonUtil.escrever(resultado.resposta()));
                saida.write('\n');
                saida.flush();
                log.accept("ENVIADO S -> C " + contexto + " user=" + (resultado.autor() == null ? "-" : resultado.autor())
                        + " " + RegistroMensagens.mensagem(resultado.resposta()));
            }
        } catch (SocketTimeoutException ex) {
            log.accept("Conexao encerrada por inatividade");
        } catch (IOException ex) {
            if (!parando) {
                log.accept("Conexao encerrada");
            }
        } finally {
            aoFechar.run();
        }
    }

    public void parar() {
        parando = true;
        try {
            socket.shutdownInput();
        } catch (IOException ex) {
        }
    }

    public void fechar() {
        try {
            socket.close();
        } catch (IOException ex) {
        }
    }
}
