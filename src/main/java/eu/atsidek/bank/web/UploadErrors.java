package eu.atsidek.bank.web;

import eu.atsidek.bank.CurrentUser;
import eu.atsidek.bank.store.BankStore;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/** 413: per didelis failas – lietuviškas pranešimas ekrane (daugiausia 10 MB). */
@ControllerAdvice
public class UploadErrors {

    private final BankStore store;
    private final CurrentUser user;

    public UploadErrors(BankStore store, CurrentUser user) {
        this.store = store;
        this.user = user;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView tooLarge() {
        ModelAndView mv = new ModelAndView("bank/upload");
        mv.setStatus(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        mv.addObject("failures", List.of("Failas per didelis (daugiausia 10 MB)."));
        mv.addObject("accounts", store.accounts(user.id()));
        mv.addObject("imports", store.imports(user.id()));
        return mv;
    }
}
