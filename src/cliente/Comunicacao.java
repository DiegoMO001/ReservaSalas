package cliente;

import com.google.gson.JsonObject;
import comum.JsonUtil;
import comum.Linhas;
import comum.RegistroMensagens;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class Comunicacao implements AutoCloseable {
    private final String ip;
    private final int porta;
    private final Consumer<String> log;
    private long troca;
    private Socket socket;
    private BufferedReader entrada;
    private BufferedWriter saida;

    public Comunicacao(String ip, int porta) {
        this(ip, porta, System.out::println);
    }

    public Comunicacao(String ip, int porta, Consumer<String> log) {
        this.ip = ip;
        this.porta = porta;
        this.log = log;
    }

    public synchronized void conectar() throws IOException {
        close();
        Socket novo = new Socket();
        try {
            novo.connect(new InetSocketAddress(ip, porta), 5000);
            novo.setSoTimeout(30_000);
            entrada = new BufferedReader(new InputStreamReader(novo.getInputStream(), StandardCharsets.UTF_8));
            saida = new BufferedWriter(new OutputStreamWriter(novo.getOutputStream(), StandardCharsets.UTF_8));
            socket = novo;
            log.accept("CONEXAO aberta com " + ip + ":" + porta);
        } catch (IOException ex) {
            novo.close();
            throw ex;
        }
    }

    public synchronized JsonObject enviar(JsonObject pedido) throws IOException {
        String mensagem = JsonUtil.escrever(pedido) + "\n";
        if (mensagem.getBytes(StandardCharsets.UTF_8).length > Linhas.LIMITE_REQUISICAO) {
            throw new IOException("Mensagem excede o tamanho maximo");
        }
        long id = ++troca;
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                if (socket == null) {
                    conectar();
                }
                saida.write(mensagem);
                saida.flush();
                log.accept("ENVIADO C -> S troca=" + id + " tentativa=" + (tentativa + 1) + " " + RegistroMensagens.mensagem(pedido));
                String linha = entrada.readLine();
                if (linha == null) {
                    throw new EOFException("Conexao encerrada pelo servidor");
                }
                JsonObject resposta = JsonUtil.lerObjeto(linha);
                log.accept("RECEBIDO S -> C troca=" + id + " " + RegistroMensagens.mensagem(resposta));
                if (!JsonUtil.ehString(resposta.get("status")) || !JsonUtil.ehString(resposta.get("message"))
                        || !JsonUtil.ehString(resposta.get("op"))
                        || (!JsonUtil.texto(resposta, "op").equals(JsonUtil.texto(pedido, "op") + "_response")
                        && !JsonUtil.texto(resposta, "op").equals("error"))) {
                    throw new IOException("Resposta invalida do servidor");
                }
                return resposta;
            } catch (EOFException | SocketException ex) {
                close();
                log.accept("SEM RESPOSTA troca=" + id + (tentativa == 0
                        ? "; reconectando e reenviando a mesma solicitacao" : "; falha apos reconexao"));
                if (tentativa == 1) {
                    throw ex;
                }
            } catch (IOException ex) {
                close();
                log.accept("FALHA troca=" + id + "; resposta nao confirmada (" + ex.getClass().getSimpleName() + ")");
                throw ex;
            }
        }
        throw new IOException("Sem resposta do servidor");
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ex) {
            }
        }
        socket = null;
        entrada = null;
        saida = null;
    }
}
