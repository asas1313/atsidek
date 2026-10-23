package lt.atsidek.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** MVP: el. pašto adresai kaupiami CSV faile. DB atsiras kartu su paskyromis (mokama dalis). */
@Component
public class SignupStore {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,\"]+@[^\\s@,\"]+\\.[^\\s@,\"]{2,}$");

    private final Path file;

    public SignupStore(@Value("${atsidek.signups-file:data/signups.csv}") String path) {
        this.file = Path.of(path);
    }

    public static boolean isValid(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email.trim()).matches();
    }

    public synchronized void add(String email) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = Instant.now() + "," + email.trim().toLowerCase() + System.lineSeparator();
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
