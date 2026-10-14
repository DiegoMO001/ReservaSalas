package comum;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Datas {
    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    public static final Clock RELOGIO = Clock.system(FUSO);
    public static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private Datas() {
    }

    public static LocalDateTime agora() {
        return LocalDateTime.now(RELOGIO);
    }

    public static LocalDateTime combinar(String data, String hora) {
        return LocalDateTime.of(LocalDate.parse(data), LocalTime.parse(hora));
    }

    public static String formatar(LocalDateTime instante) {
        return instante.format(DATA_HORA);
    }
}
