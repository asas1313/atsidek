package eu.atsidek.bank;

import org.springframework.stereotype.Component;

/**
 * Šiame etape paskyrų nėra: visi duomenys priklauso vienam vartotojui. Visi užklausų filtrai jau eina per
 * {@code user_id}, todėl vėliau pakaks šią klasę pakeisti sesijos vartotoju.
 */
@Component
public class CurrentUser {
    public String id() {
        return "local";
    }
}
