package servidor;

import comum.RegistroMensagens;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Scanner;
import servidor.dao.BancoDAO;
import servidor.services.Aplicacao;

public final class ServidorMain {
    private ServidorMain() {
    }

    public static void main(String[] args) {
        Scanner entrada = new Scanner(System.in);
        int porta = lerPorta(entrada);
        RegistroMensagens log = new RegistroMensagens(Path.of("servidor-log.txt"), System.out, "SERVIDOR");
        try (FileChannel arquivo = FileChannel.open(Path.of("sistema.db.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = arquivo.tryLock()) {
            if (lock == null) {
                System.out.println("Ja existe um servidor usando este banco");
                return;
            }
            BancoDAO banco = new BancoDAO();
            banco.inicializar();
            try (ServidorTCP servidor = new ServidorTCP(porta, new Aplicacao(banco), 300_000, log)) {
                Thread encerramento = new Thread(servidor::close, "encerramento-servidor");
                Runtime.getRuntime().addShutdownHook(encerramento);
                try {
                    servidor.iniciar();
                    log.accept("Servidor na porta " + servidor.porta() + ". Digite sair para encerrar.");
                    while (entrada.hasNextLine()) {
                        if (entrada.nextLine().trim().equalsIgnoreCase("sair")) {
                            break;
                        }
                        System.out.println("Digite sair para encerrar.");
                    }
                } finally {
                    servidor.close();
                    try {
                        Runtime.getRuntime().removeShutdownHook(encerramento);
                    } catch (IllegalStateException ex) {
                    }
                }
            }
            log.accept("Servidor encerrado");
        } catch (SQLException | IOException | RuntimeException ex) {
            log.accept("Nao foi possivel iniciar ou executar o servidor: " + ex.getClass().getSimpleName());
        }
    }

    private static int lerPorta(Scanner entrada) {
        while (true) {
            System.out.print("Porta [12345]: ");
            if (!entrada.hasNextLine()) {
                return 12345;
            }
            String valor = entrada.nextLine().trim();
            if (valor.isEmpty()) {
                return 12345;
            }
            try {
                int porta = Integer.parseInt(valor);
                if (porta >= 1024 && porta <= 65535) {
                    return porta;
                }
            } catch (NumberFormatException ex) {
            }
            System.out.println("Porta invalida. Use 1024 a 65535.");
        }
    }
}
