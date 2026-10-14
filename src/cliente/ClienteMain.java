package cliente;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;
import comum.RegistroMensagens;

public final class ClienteMain {
    private ClienteMain() {
    }

    public static void main(String[] args) {
        Scanner entrada = new Scanner(System.in);
        System.out.print("IP [127.0.0.1]: ");
        if (!entrada.hasNextLine()) {
            return;
        }
        String ip = entrada.nextLine().trim();
        if (ip.isEmpty()) {
            ip = "127.0.0.1";
        }
        int porta;
        while (true) {
            System.out.print("Porta [12345]: ");
            if (!entrada.hasNextLine()) {
                return;
            }
            String valor = entrada.nextLine().trim();
            try {
                porta = valor.isEmpty() ? 12345 : Integer.parseInt(valor);
                if (porta >= 1024 && porta <= 65535) {
                    break;
                }
            } catch (NumberFormatException ex) {
            }
            System.out.println("Porta invalida. Use 1024 a 65535.");
        }
        RegistroMensagens log = new RegistroMensagens(Path.of("cliente-log.txt"), System.out, "CLIENTE");
        try (Comunicacao comunicacao = new Comunicacao(ip, porta, log)) {
            comunicacao.conectar();
            new ClienteCLI(entrada, System.out, comunicacao, System.console()).executar();
        } catch (IOException ex) {
            log.accept("Nao foi possivel conectar ao servidor. Confira IP, porta e servidor.");
        } finally {
            log.accept("Cliente encerrado");
        }
    }
}
