package io.github.davifmn.ledger.participant;

import io.github.davifmn.ledger.common.ApiException;
import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.participant.ParticipantService.Participant;
import io.github.davifmn.ledger.participant.ParticipantService.ParticipantView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ParticipantController {

    public record CreateParticipant(
            @NotBlank @Pattern(regexp = ParticipantService.ID_PATTERN) String id,
            @NotBlank @Size(max = 70) String name) {
    }

    private final ParticipantService participants;
    private final AccountService accounts;

    public ParticipantController(ParticipantService participants, AccountService accounts) {
        this.participants = participants;
        this.accounts = accounts;
    }

    @PostMapping("/participants")
    ResponseEntity<Participant> create(@Valid @RequestBody CreateParticipant request) {
        Participant p = participants.create(request.id(), request.name());
        return ResponseEntity.created(URI.create("/api/participants/" + p.id())).body(p);
    }

    @GetMapping("/participants")
    List<ParticipantView> list() {
        return participants.list();
    }

    @GetMapping("/participants/{id}")
    ParticipantView get(@PathVariable String id) {
        return participants.view(id)
                .orElseThrow(() -> ApiException.notFound("participant " + id + " does not exist"));
    }

    @GetMapping("/accounts/{id}/statement")
    AccountService.Statement statement(@PathVariable long id,
                                       @RequestParam(defaultValue = "0") long afterId,
                                       @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            throw ApiException.badRequest("limit must be between 1 and 500");
        }
        return accounts.statement(id, afterId, limit)
                .orElseThrow(() -> ApiException.notFound("account " + id + " does not exist"));
    }
}
