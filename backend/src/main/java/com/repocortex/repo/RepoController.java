package com.repocortex.repo;

import com.repocortex.repo.dto.RepoResponse;
import com.repocortex.repo.dto.SubmitRepoRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoService repoService;

    public RepoController(RepoService repoService) {
        this.repoService = repoService;
    }

    @PostMapping
    public RepoResponse submit(@Valid @RequestBody SubmitRepoRequest request) {
        return toResponse(repoService.submit(request.url()));
    }

    @GetMapping("/recent")
    public List<RepoResponse> recent() {
        return repoService.recent().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{owner}/{name}")
    public RepoResponse get(@PathVariable String owner, @PathVariable String name) {
        return toResponse(repoService.get(owner, name));
    }

    private RepoResponse toResponse(Repository repo) {
        return RepoResponse.from(repo, repoService.isChatReady(repo));
    }
}
