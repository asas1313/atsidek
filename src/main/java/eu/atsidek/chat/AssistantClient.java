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
     * @param tier         {@code Tier.name()} ("FREE"/"PREMIUM") - B7 wiring: Python NIEKADA
     *                     nedekoduoja žetono, tad tier'ą jam pasako TIK šis atskiras laukas (ne
     *                     saugumo sprendimui - tai tik dienos limito kvota, žr.
     *                     {@code chat_limiter.py} - tikra teisių patikra tebevyksta Java pusėje
     *                     prie kiekvieno įrankio kvietimo, per patį kontekstinį žetoną)
     * @param key          stabilus vartotojo identifikatorius dienos limito skaitliukui
     *                     ({@code chat_limiter.ChatLimiter}) - {@code null} anonimui
     * @param history      ankstesnių šio pokalbio žinučių sąrašas, TOKS, kokį Python grąžino
     *                     praeitą kartą ({@code AgentLoopResult.messages} - šis servisas jo
     *                     turinio neinterpretuoja, tik laiko {@link jakarta.servlet.http.HttpSession}
     *                     ir grąžina atgal nepakeistą, B5 sprendimas 7) - {@code null} pirmai
     *                     žinutei
     * @return Python pusės grąžintas atsakymas (B1 - minimalus stub/ack, pilnas atsakymo
     *         formavimas - B4, tikras agento ciklas - B7)
     */
    Object chat(String contextToken, String message, String tier, String key, Object history);
}
