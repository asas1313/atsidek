package eu.atsidek.bank.web;

import eu.atsidek.bank.Category;
import eu.atsidek.bank.CurrentUser;
import eu.atsidek.bank.ImportService;
import eu.atsidek.bank.ReviewService;
import eu.atsidek.bank.SummaryService;
import eu.atsidek.bank.parse.StatementException;
import eu.atsidek.bank.store.BankStore;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Trys pagrindiniai ekranai: įkėlimas, peržiūra ir patvirtinimas, rezultatas; plius importai, taisyklės, nustatymai. */
@Controller
public class BankController {

    private final ImportService imports;
    private final ReviewService review;
    private final SummaryService summaries;
    private final BankStore store;
    private final CurrentUser user;

    public BankController(ImportService imports, ReviewService review, SummaryService summaries, BankStore store,
                          CurrentUser user) {
        this.imports = imports;
        this.review = review;
        this.summaries = summaries;
        this.store = store;
        this.user = user;
    }

    // ---- 1. įkėlimas ir importų istorija

    @GetMapping("/importas")
    public String upload(Model model) {
        model.addAttribute("accounts", store.accounts(user.id()));
        model.addAttribute("imports", store.imports(user.id()));
        return "bank/upload";
    }

    @PostMapping("/importas")
    public String doUpload(@RequestParam("file") List<MultipartFile> files,
                           @RequestParam(value = "accountId", required = false) Long accountId,
                           Model model, RedirectAttributes redirect) throws IOException {
        List<ImportService.Report> reports = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            String name = f.getOriginalFilename() == null ? "failas" : f.getOriginalFilename();
            try {
                reports.add(imports.importFile(name, f.getBytes(), accountId));
            } catch (StatementException e) {
                failures.add(e.getMessage());
            }
        }
        if (reports.isEmpty() && failures.isEmpty()) failures.add("Nepasirinkai failo.");
        if (reports.size() == 1 && failures.isEmpty()) {
            redirect.addFlashAttribute("report", reports.get(0));
            return "redirect:/importai/" + reports.get(0).importId();
        }
        model.addAttribute("reports", reports);
        model.addAttribute("failures", failures);
        model.addAttribute("accounts", store.accounts(user.id()));
        model.addAttribute("imports", store.imports(user.id()));
        return "bank/upload";
    }

    @PostMapping("/importai/{id}/istrinti")
    public String deleteImport(@PathVariable String id) {
        store.deleteImport(user.id(), id);
        return "redirect:/importas";
    }

    // ---- 2. peržiūra ir patvirtinimas

    @GetMapping("/importai/{id}")
    public String reviewPage(@PathVariable String id, Model model) {
        if (!store.importExists(user.id(), id)) return "redirect:/importas";
        model.addAttribute("importId", id);
        model.addAttribute("groups", review.groups(id));
        model.addAttribute("categories", Category.values());
        return "bank/review";
    }

    @PostMapping("/importai/{id}/patvirtinti")
    public String confirm(@PathVariable String id, @RequestParam MultiValueMap<String, String> p,
                          @RequestParam(value = "all", required = false) String all) {
        boolean confirmAll = all != null;
        List<ReviewService.Decision> decisions = new ArrayList<>();
        for (int i = 0; p.containsKey("ids_" + i); i++) {
            List<Long> ids = Arrays.stream(p.getFirst("ids_" + i).split(","))
                    .map(String::trim).filter(s -> s.matches("\\d{1,18}")).map(Long::valueOf).toList();
            Category cat;
            try {
                cat = Category.valueOf(p.getFirst("cat_" + i));
            } catch (IllegalArgumentException | NullPointerException e) {
                continue;
            }
            decisions.add(new ReviewService.Decision(ids, cat, confirmAll || p.containsKey("ok_" + i),
                    p.containsKey("always_" + i)));
        }
        review.apply(decisions);
        return "redirect:/suvestine";
    }

    // ---- 3. rezultatas

    @GetMapping("/suvestine")
    public String summary(@RequestParam(value = "year", required = false) Integer year, Model model) {
        int y = year != null ? year : LocalDate.now().getYear();
        SummaryService.Summary s = summaries.summary(y);
        model.addAttribute("s", s);
        model.addAttribute("pct", s.bracket1().signum() == 0 ? 0
                : Math.min(100, s.ytdIncome().multiply(java.math.BigDecimal.valueOf(100)).divide(s.bracket1(), 0,
                java.math.RoundingMode.DOWN).intValue()));
        return "bank/summary";
    }

    // ---- taisyklės ir nustatymai

    @GetMapping("/taisykles")
    public String rules(Model model) {
        model.addAttribute("rules", store.rules(user.id()));
        model.addAttribute("categories", Category.values());
        return "bank/rules";
    }

    @PostMapping("/taisykles")
    public String addRule(@RequestParam String mode, @RequestParam String text, @RequestParam String category) {
        try {
            review.addTextRule("PREFIX".equals(mode), text, Category.valueOf(category));
        } catch (IllegalArgumentException ignored) {
            // nežinoma kategorija – taisyklė nekuriama
        }
        return "redirect:/taisykles";
    }

    @PostMapping("/taisykles/{id}/istrinti")
    public String deleteRule(@PathVariable long id) {
        store.deleteRule(user.id(), id);
        return "redirect:/taisykles";
    }

    @GetMapping("/nustatymai")
    public String settings(@RequestParam(value = "year", required = false) Integer year, Model model) {
        int y = year != null ? year : LocalDate.now().getYear();
        model.addAttribute("accounts", store.accounts(user.id()));
        model.addAttribute("year", y);
        model.addAttribute("psdFrom", summaries.psdFrom(y));
        model.addAttribute("psdTo", summaries.psdTo(y));
        return "bank/settings";
    }

    @PostMapping("/nustatymai/psd")
    public String savePsd(@RequestParam(value = "year", required = false) Integer year,
                          @RequestParam int psdFrom, @RequestParam int psdTo) {
        int y = year != null ? year : LocalDate.now().getYear();
        store.setSetting(user.id(), "psd_from_" + y, String.valueOf(Math.max(1, Math.min(12, psdFrom))));
        store.setSetting(user.id(), "psd_to_" + y, String.valueOf(Math.max(1, Math.min(12, psdTo))));
        return "redirect:/nustatymai?year=" + y;
    }

    @PostMapping("/nustatymai/istrinti-viska")
    public String deleteAll(RedirectAttributes redirect) {
        store.deleteAll(user.id());
        redirect.addFlashAttribute("deleted", true);
        return "redirect:/nustatymai";
    }
}
