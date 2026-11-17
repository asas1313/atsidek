package eu.atsidek.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Visiems šablonams: ar rodoma nauja pokalbio UI (B6.6) ar senas /asistentas kelias (žr.
 * {@code asistentas.html}/{@code taupymo-tikslai.html}, {@code atsidek.chat.agent-enabled}).
 */
@ControllerAdvice
public class ChatModelAdvice {

    private final boolean agentChatEnabled;

    public ChatModelAdvice(@Value("${atsidek.chat.agent-enabled:false}") boolean agentChatEnabled) {
        this.agentChatEnabled = agentChatEnabled;
    }

    @ModelAttribute("agentChatEnabled")
    boolean agentChatEnabled() {
        return agentChatEnabled;
    }
}
