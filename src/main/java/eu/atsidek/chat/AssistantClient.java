package eu.atsidek.chat;

/**
 * Siaura sąsaja skambučiui į Python {@code assistant} serviso vidinį chat endpoint'ą (B1,
 * ADR-0003) - atskirta nuo {@link ChatController}, kad testuose nereikėtų realaus tinklo (žr.
 * {@code ChatControllerTest}, kur šis bean'as pakeistas mock'u).
 */
public interface AssistantClient {

    /**
     * @param contextToken ADR-0003 kontekstinis žetonas - siunčiamas Python servisui NEPAKEISTAS;
     *                     Python jo nei tikrina, nei dekoduoja, tik grąžina atgal ant kiekvieno
     *                     įrankio kvietimo
     * @param message      vartotojo žinutės tekstas
     * @return Python pusės grąžintas atsakymas (B1 - minimalus stub/ack, pilnas atsakymo
     *         formavimas - B4)
     */
    Object chat(String contextToken, String message);
}
