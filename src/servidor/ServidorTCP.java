package servidor;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import servidor.services.Aplicacao;

public final class ServidorTCP implements AutoCloseable {
    private final ServerSocket escuta;
    private final Aplicacao aplicacao;
    private final int timeout;
    private final Consumer<String> log;
    private final ExecutorService conexoes = Executors.newCachedThreadPool();
    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean fechado = new AtomicBoolean();
    private Thread aceitador;

    public ServidorTCP(int porta, Aplicacao aplicacao) throws IOException {
        this(porta, aplicacao, 300_000, System.out::println);
    }

    public ServidorTCP(int porta, Aplicacao aplicacao, int timeout, Consumer<String> log) throws IOException {
        this.aplicacao = aplicacao;
        this.timeout = timeout;
        this.log = log;
        escuta = new ServerSocket(porta);
    }

    public synchronized void iniciar() {
        if (aceitador != null || fechado.get()) {
            throw new IllegalStateException("Servidor ja iniciado ou encerrado");
        }
        aceitador = new Thread(this::aceitar, "aceitador-tcp");
        aceitador.start();
    }

    public int porta() {
        return escuta.getLocalPort();
    }

    private void aceitar() {
        while (!fechado.get()) {
            try {
                Socket socket = escuta.accept();
                synchronized (this) {
                    if (fechado.get()) {
                        socket.close();
                        break;
                    }
                    try {
                        socket.setSoTimeout(timeout);
                        ClientHandler[] referencia = new ClientHandler[1];
                        referencia[0] = new ClientHandler(socket, aplicacao, log, () -> handlers.remove(referencia[0]));
                        handlers.add(referencia[0]);
                        conexoes.submit(referencia[0]);
                    } catch (IOException | RuntimeException ex) {
                        socket.close();
                        throw ex;
                    }
                }
            } catch (IOException | RuntimeException ex) {
                if (!fechado.get()) {
                    log.accept("Falha ao aceitar conexao");
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!fechado.compareAndSet(false, true)) {
                return;
            }
            try {
                escuta.close();
            } catch (IOException ex) {
            }
            handlers.forEach(ClientHandler::parar);
            conexoes.shutdown();
        }
        try {
            while (!conexoes.awaitTermination(1, TimeUnit.SECONDS)) {
            }
            if (aceitador != null) {
                aceitador.join();
            }
        } catch (InterruptedException ex) {
            handlers.forEach(ClientHandler::fechar);
            conexoes.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
